package app.jidian.tap.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.view.WindowManager
import app.jidian.tap.service.TapAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * 疾点连点引擎：独立线程 + 手势回调链，是所有点击行为的唯一出口。
 *
 * 设计要点：
 * 1. **独立线程**：自带 `HandlerThread("tap-engine")`，不占主线程；线程提到 THREAD_PRIORITY_DISPLAY。
 * 2. **回调链驱动**：每轮手势在 GestureResultCallback 里立刻排下一轮，用 SystemClock.uptimeMillis()
 *    对齐目标时刻（Handler.postAtTime），所以节奏不会随派发延迟累积漂移；抖动只影响每轮步长。
 * 3. **热路径零锁**：配置在 [latestConfig] 里做一次 volatile 替换，热路径只读它，不加锁、不打日志；
 *    状态流按 ≤20Hz 节流 emit（计数原子累计，不会丢）。
 * 4. **自愈**：派发后挂看门狗；回调超时则清标志、计 recoveries 并重排一轮。连续派发失败或无障碍断开
 *    直接停止，不让引擎空转。
 * 5. 引擎跟随进程生命周期（在 [app.jidian.tap.JidianApp] 构造），与 Activity / 前台服务解耦，
 *    这样关掉界面甚至没有悬浮窗也能继续跑。
 *
 * 模式实现：
 * - SINGLE：1px 位移、1–8ms 的短 Stroke；
 * - DOUBLE：**一个** GestureDescription 里放两个 Stroke（startTime 0 与 doubleGapMs），少一轮 IPC；
 * - LONG_PRESS：单 Stroke，时长 longPressMs；
 * - SWIPE：起点 → 中点 → 终点三采样点，时长 swipeDurationMs。
 */
class TapEngine(context: Context, configStore: ConfigStore) : TapEngineApi {

    private val appContext: Context = context.applicationContext
    private val store: ConfigStore = configStore
    private val powerManager: PowerManager? = runCatching {
        appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
    }.getOrNull()

    private val engineThread = HandlerThread(THREAD_NAME)
    private val handler: Handler

    /** 熄屏 / 亮屏：引擎没挂在 Activity 或前台服务上，所以自己监听屏幕状态。 */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> onScreenInteractiveChanged(false)
                Intent.ACTION_SCREEN_ON -> onScreenInteractiveChanged(true)
            }
        }
    }

    /** 配置热更新监听：只换 volatile 快照，不打断在途手势，下一轮自然生效。 */
    private val configListener: (ClickConfig) -> Unit = { updated ->
        latestConfig = updated.safe()
        publishStatus(force = true)
    }

    private val stateLock = Any()

    /** 热路径唯一读取的配置（始终是 sanitize 过的快照）。 */
    @Volatile
    private var latestConfig: ClickConfig = store.snapshot().safe()

    @Volatile
    private var running = false

    @Volatile
    private var waitingForService = false

    /** 用户在无障碍未就绪时按过启动；服务连上后据此自动进入运行。 */
    @Volatile
    private var pendingStart = false

    @Volatile
    private var sessionStartedElapsed = 0L

    @Volatile
    private var lastStopReason: String? = null

    /** 已派发、还没收到回调：看门狗靠它判断手势是否卡死。 */
    @Volatile
    private var dispatching = false

    /** 每派发一轮 +1；回调带序号回来，过期回调（停止/自愈之后才到）直接丢弃。 */
    private val gestureSeq = AtomicLong(0L)

    private val sessionTaps = AtomicLong(0L)
    private val totalTaps = AtomicLong(0L)
    private val dropped = AtomicLong(0L)
    private val gestureTimeouts = AtomicInteger(0)
    private val recoveries = AtomicInteger(0)

    /** 下一次点击的目标时刻（uptimeMillis）；0 表示立即。 */
    @Volatile
    private var nextTargetUptime = 0L

    /** 状态流节流时间戳：计数变化最多 20Hz emit。 */
    @Volatile
    private var lastStatusUptime = 0L

    // ── 以下状态只在引擎线程读写 ──────────────────────────────────────────────
    private var consecutiveFailures = 0
    private var consecutiveCancels = 0
    private var buildFailures = 0
    private var screenW = 0
    private var screenH = 0
    private var screenOrientation = Int.MIN_VALUE
    private val random = Random()

    /**
     * 系统允许的 GestureDescription 最大 Stroke 数（框架固定为 10）。
     * 合并连点用满它，把一次 dispatchGesture 往返摊到 10 次点击上。
     */
    private val maxStrokes: Int = runCatching { GestureDescription.getMaxStrokeCount() }
        .getOrDefault(DEFAULT_MAX_STROKES)
        .coerceAtLeast(1)

    /**
     * 合并连点在当前会话内被系统连续取消后自动降级：整批点击都落不下去时，
     * 再合并只会更糟，退回「一次手势一次点击」至少保证能用。
     */
    @Volatile
    private var batchingBlocked = false

    private val priorityRunnable = Runnable {
        // 手势派发对时序敏感，把引擎线程提到显示优先级；失败也不影响正确性
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) }
    }
    private val dispatchRunnable = Runnable { dispatchNext() }
    private val watchdogRunnable = Runnable { onWatchdog() }

    private val _status = MutableStateFlow(buildStatus())
    override val status: StateFlow<EngineStatus> = _status.asStateFlow()

    override val isRunning: Boolean
        get() = running

    init {
        engineThread.start()
        handler = Handler(engineThread.looper)
        handler.post(priorityRunnable)
        store.addListener(configListener)
        registerScreenReceiver()
        JidianLog.info(
            TAG,
            "引擎就绪（无障碍${if (TapAccessibilityService.isConnected) "已" else "未"}连接）",
        )
    }

    // ── TapEngineApi ─────────────────────────────────────────────────────────

    override fun start(source: String): Boolean {
        if (!TapAccessibilityService.isConnected) {
            synchronized(stateLock) {
                running = false
                waitingForService = true
                pendingStart = true
                sessionStartedElapsed = 0L
            }
            publishStatus(force = true)
            if (TapAccessibilityService.isConnected) {
                // 竞态兜底：置等待标志的瞬间服务可能刚好连上（onAccessibilityConnected 已跑过）
                return start(source)
            }
            JidianLog.info(TAG, "等待无障碍服务（来源 $source）：开启无障碍后会自动开始")
            return false
        }
        val config = store.snapshot().safe()
        latestConfig = config
        if (config.pauseOnScreenOff && !isScreenInteractive()) {
            synchronized(stateLock) {
                running = false
                waitingForService = false
                pendingStart = false
            }
            lastStopReason = StopReason.SCREEN_OFF
            publishStatus(force = true)
            JidianLog.info(TAG, "屏幕未点亮，按设定不启动连点")
            return false
        }
        synchronized(stateLock) {
            if (running) return true
            running = true
            waitingForService = false
            pendingStart = false
            sessionTaps.set(0L)
            batchingBlocked = false
            consecutiveCancels = 0
            sessionStartedElapsed = SystemClock.elapsedRealtime()
            lastStopReason = null
            nextTargetUptime = 0L
            consecutiveFailures = 0
            buildFailures = 0
        }
        gestureSeq.incrementAndGet()
        JidianLog.info(TAG, "开始连点（${config.mode.name}，间隔 ${config.intervalMs}ms）")
        publishStatus(force = true)
        handler.removeCallbacks(dispatchRunnable)
        handler.removeCallbacks(watchdogRunnable)
        handler.postAtTime(dispatchRunnable, SystemClock.uptimeMillis())
        return true
    }

    override fun stop(reason: String) {
        var wasActive = false
        synchronized(stateLock) {
            wasActive = running || waitingForService || pendingStart
            running = false
            waitingForService = false
            pendingStart = false
            sessionStartedElapsed = 0L
            if (wasActive) lastStopReason = reason
        }
        gestureSeq.incrementAndGet()
        dispatching = false
        handler.removeCallbacks(dispatchRunnable)
        handler.removeCallbacks(watchdogRunnable)
        if (!wasActive) return
        JidianLog.info(TAG, "停止连点（${reasonText(reason)}）")
        publishStatus(force = true)
    }

    override fun toggle(source: String): Boolean {
        return if (running || waitingForService) {
            stop(StopReason.USER)
            false
        } else {
            start(source)
        }
    }

    override fun onAccessibilityConnected() {
        var autoStart = false
        synchronized(stateLock) {
            if (waitingForService && pendingStart && !running) {
                autoStart = true
                waitingForService = false
                pendingStart = false
            }
        }
        publishStatus(force = true)
        if (autoStart) {
            JidianLog.info(TAG, "无障碍服务已就绪，自动开始连点")
            start("accessibility")
        }
    }

    override fun onAccessibilityDisconnected() {
        val wasRunning = running
        synchronized(stateLock) {
            waitingForService = false
            pendingStart = false
        }
        if (wasRunning) {
            stop(StopReason.SERVICE_LOST)
        } else {
            publishStatus(force = true)
        }
    }

    override fun onScreenInteractiveChanged(interactive: Boolean) {
        if (interactive) return
        if (!running) return
        if (!latestConfig.pauseOnScreenOff) return
        stop(StopReason.SCREEN_OFF)
    }

    override fun refreshConfig() {
        latestConfig = store.snapshot().safe()
        publishStatus(force = true)
    }

    // ── 派发链（只在引擎线程执行）──────────────────────────────────────────────

    private fun dispatchNext() {
        if (!running || dispatching) return
        val cfg = latestConfig
        ensureScreenSize()
        val built = buildGesture(cfg)
        if (built == null) {
            dropped.incrementAndGet()
            publishStatus(force = false)
            postNext()
            return
        }
        val service = TapAccessibilityService.instance
        if (service == null) {
            JidianLog.warn(TAG, "无障碍服务不可用，连点中止")
            stop(StopReason.SERVICE_LOST)
            return
        }
        val now = SystemClock.uptimeMillis()
        // 目标时刻按上一轮目标累加，保证长跑不漂；已经落后就对齐到当前时刻，避免瞬间补点
        val scheduledAt = if (nextTargetUptime <= 0L) now else max(nextTargetUptime, now)
        // 批次里有 taps 次点击就顺延 taps 个间隔；双击模式仍以「一对」为单位走一个间隔
        val step = if (cfg.mode == TapMode.SINGLE) {
            built.taps.toLong() * Geometry.nextIntervalMs(cfg.intervalMs, cfg.intervalJitterPct, random)
        } else {
            Geometry.nextIntervalMs(cfg.intervalMs, cfg.intervalJitterPct, random)
        }
        nextTargetUptime = scheduledAt + step
        val seq = gestureSeq.incrementAndGet()
        dispatching = true
        val timeoutMs = max(MIN_WATCHDOG_MS, built.durationMs + WATCHDOG_MARGIN_MS)
        handler.removeCallbacks(watchdogRunnable)
        handler.postAtTime(watchdogRunnable, now + timeoutMs)
        val callback = GestureDoneCallback(seq)
        val accepted = try {
            service.dispatchGesture(built.gesture, callback, handler)
        } catch (t: Throwable) {
            JidianLog.error(TAG, "手势派发异常：${t.message}")
            false
        }
        if (!accepted) {
            dispatching = false
            handler.removeCallbacks(watchdogRunnable)
            dropped.incrementAndGet()
            consecutiveFailures += 1
            if (consecutiveFailures == 1) {
                JidianLog.warn(TAG, "手势派发被系统拒绝，正在重试")
            }
            publishStatus(force = false)
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                JidianLog.error(TAG, "连续 $consecutiveFailures 次派发失败，停止连点")
                stop(StopReason.SERVICE_LOST)
            } else if (running) {
                postNext()
            }
            return
        }
        if (!running) {
            // 停止请求恰好落在派发瞬间：本轮不再计数、不再续链（在途手势由系统自行结束）
            dispatching = false
            handler.removeCallbacks(watchdogRunnable)
            return
        }
        consecutiveFailures = 0
        val taps = built.taps.toLong()
        val session = sessionTaps.addAndGet(taps)
        totalTaps.addAndGet(taps)
        publishStatus(force = false)
        if (cfg.limitCount > 0 && session >= cfg.limitCount) {
            stop(StopReason.LIMIT_REACHED)
        }
    }

    private fun postNext() {
        if (!running) return
        val now = SystemClock.uptimeMillis()
        val target = if (nextTargetUptime <= 0L) now else max(nextTargetUptime, now)
        handler.removeCallbacks(dispatchRunnable)
        handler.postAtTime(dispatchRunnable, target)
    }

    /** 手势收尾：只有「当前轮」的回调才推进链条，过期回调一律丢弃。 */
    private fun onGestureFinished(seq: Long, cancelled: Boolean) {
        if (seq != gestureSeq.get()) return
        if (!dispatching) return
        dispatching = false
        handler.removeCallbacks(watchdogRunnable)
        if (cancelled) {
            // 被系统取消（别的服务抢手势、屏幕切换等）：这一轮没有真正落下，记一次丢弃
            dropped.incrementAndGet()
            consecutiveCancels += 1
            if (consecutiveCancels >= MAX_CONSECUTIVE_CANCELS && latestConfig.batchTaps && !batchingBlocked) {
                batchingBlocked = true
                JidianLog.warn(TAG, "合并连点连续 $consecutiveCancels 次被系统取消，本会话降级为单次派发")
            }
            publishStatus(force = false)
        } else {
            consecutiveCancels = 0
        }
        if (!running) return
        postNext()
    }

    /** 看门狗：回调迟迟不来就原地恢复，绝不每轮写日志。 */
    private fun onWatchdog() {
        if (!running) {
            dispatching = false
            return
        }
        if (!dispatching) return
        dispatching = false
        consecutiveFailures = 0
        recoveries.incrementAndGet()
        val timeouts = gestureTimeouts.incrementAndGet()
        if (timeouts <= TIMEOUT_WARN_BUDGET || timeouts % TIMEOUT_WARN_EVERY == 0) {
            JidianLog.warn(TAG, "手势回调超时，引擎已自愈（累计 $timeouts 次）")
        }
        publishStatus(force = true)
        if (TapAccessibilityService.instance == null) {
            stop(StopReason.SERVICE_LOST)
            return
        }
        postNext()
    }

    // ── 手势构建 ─────────────────────────────────────────────────────────────

    /**
     * 一次派发的完整内容：手势本身、其中包含的点击次数、手势总时长。
     *
     * 手势总时长要按实际批次算（`最后一个 Stroke 的 startTime + 触点时长`），
     * 看门狗阈值依赖它；长按 / 滑动仍然用各自配置时长。
     */
    private class BuiltGesture(
        val gesture: GestureDescription,
        val taps: Int,
        val durationMs: Long,
    )

    private fun buildGesture(cfg: ClickConfig): BuiltGesture? {
        val w = screenW
        val h = screenH
        return try {
            val builder = GestureDescription.Builder()
            when (cfg.mode) {
                TapMode.SINGLE -> buildSingleBatch(cfg, builder, w, h)

                TapMode.DOUBLE -> {
                    // 双击的关键：两个 Stroke 放进同一个 GestureDescription，只走一次 IPC
                    val p = Geometry.samplePoint(cfg, w, h, random)
                    val tapMs = tapDurationMs(cfg)
                    builder.addStroke(pointStroke(p, 0L, tapMs, w, h))
                    builder.addStroke(pointStroke(p, cfg.doubleGapMs, tapMs, w, h))
                    BuiltGesture(builder.build(), taps = 2, durationMs = cfg.doubleGapMs + tapMs)
                }

                TapMode.LONG_PRESS -> {
                    val p = Geometry.samplePoint(cfg, w, h, random)
                    builder.addStroke(pointStroke(p, 0L, cfg.longPressMs, w, h))
                    BuiltGesture(builder.build(), taps = 1, durationMs = cfg.longPressMs)
                }

                TapMode.SWIPE -> {
                    val start = Geometry.samplePoint(cfg, w, h, random)
                    val end = Geometry.swipeEnd(start, cfg.swipeDx, cfg.swipeDy, w, h)
                    builder.addStroke(swipeStroke(start, end, cfg.swipeDurationMs))
                    BuiltGesture(builder.build(), taps = 1, durationMs = cfg.swipeDurationMs)
                }
            }
        } catch (t: Throwable) {
            // 构建失败极其少见（配置已经 sanitize），只记前几次，避免故障时刷日志
            if (buildFailures < MAX_BUILD_FAILURE_LOGS) {
                buildFailures += 1
                JidianLog.error(TAG, "构建手势失败：${t.message}")
            }
            null
        }
    }

    /**
     * 单击批次：把 1–10 次点击放进同一个 GestureDescription。
     *
     * 每批最多 [maxStrokes] 个 Stroke、总跨度不超过 [Geometry.BATCH_MAX_SPAN_MS]，
     * 所以 1ms 档一批 10 次（10ms），20ms 档一批 3 次（40ms）——停止时最多残留几十毫秒。
     * 每次点击仍各自在随机范围内采样落点。
     */
    private fun buildSingleBatch(
        cfg: ClickConfig,
        builder: GestureDescription.Builder,
        w: Int,
        h: Int,
    ): BuiltGesture {
        val starts = if (cfg.batchTaps && !batchingBlocked) {
            Geometry.planBatchStarts(cfg.intervalMs, cfg.intervalJitterPct, maxStrokes, random)
        } else {
            listOf(0L)
        }
        // 点击上限要卡在批内，不能一批冲过上限太多
        val remaining = if (cfg.limitCount > 0) {
            (cfg.limitCount - sessionTaps.get()).coerceAtLeast(1L)
        } else {
            Long.MAX_VALUE
        }
        val count = minOf(starts.size.toLong(), remaining).toInt().coerceAtLeast(1)
        val tapMs = tapDurationMs(cfg)
        var last = 0L
        for (index in 0 until count) {
            val at = starts[index]
            val p = Geometry.samplePoint(cfg, w, h, random)
            builder.addStroke(pointStroke(p, at, tapMs, w, h))
            last = at
        }
        return BuiltGesture(builder.build(), taps = count, durationMs = last + tapMs)
    }

    /**
     * 点按 Stroke：终点偏移 1px。
     * 单点 Path 在部分 ROM 上会被系统判成空手势而丢弃；1px 远小于 touch slop，
     * 单击/长按的判定完全不受影响。
     */
    private fun pointStroke(
        p: Geometry.Point,
        startMs: Long,
        durationMs: Long,
        w: Int,
        h: Int,
    ): GestureDescription.StrokeDescription {
        val dx = if (p.x < w - 1f) 1f else -1f
        val path = Path()
        path.moveTo(p.x, p.y)
        path.lineTo(p.x + dx, p.y)
        return GestureDescription.StrokeDescription(path, startMs, durationMs.coerceAtLeast(1L))
    }

    /** 滑动 Stroke：起点 → 中点 → 终点三个采样点，轨迹比两点更顺滑。 */
    private fun swipeStroke(
        start: Geometry.Point,
        end: Geometry.Point,
        durationMs: Long,
    ): GestureDescription.StrokeDescription {
        val path = Path()
        path.moveTo(start.x, start.y)
        path.lineTo((start.x + end.x) * 0.5f, (start.y + end.y) * 0.5f)
        path.lineTo(end.x, end.y)
        return GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(1L))
    }

    /**
     * 触点时长：常规 1–8ms（短到只被当作一次点击，又留一点随机性降低特征）。
     *
     * 极限节奏（间隔 ≤ [ClickConfig.ULTRA_FAST_THRESHOLD_MS]）压到 1ms：
     * 触点时长不能超过节奏本身，否则批次里相邻 Stroke 会挤在一起。
     */
    private fun tapDurationMs(cfg: ClickConfig): Long =
        if (cfg.ultraFast) TAP_DURATION_MIN_MS
        else TAP_DURATION_MIN_MS +
            random.nextInt((TAP_DURATION_MAX_MS - TAP_DURATION_MIN_MS + 1).toInt()).toLong()

    // ── 屏幕尺寸 ─────────────────────────────────────────────────────────────

    /** 每次派发前校对屏幕：转屏后原地重新取，采样坐标不会用旧尺寸。 */
    private fun ensureScreenSize() {
        val orientation = runCatching { appContext.resources.configuration.orientation }
            .getOrDefault(screenOrientation)
        if (screenW >= 2 && screenH >= 2 && orientation == screenOrientation) return
        val changed = screenW >= 2 && orientation != screenOrientation
        screenOrientation = orientation
        updateScreenSize()
        if (changed) JidianLog.info(TAG, "屏幕尺寸更新为 ${screenW}×${screenH}")
    }

    @Suppress("DEPRECATION")
    private fun updateScreenSize() {
        val wm = runCatching {
            appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        }.getOrNull()
        if (wm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = runCatching { wm.currentWindowMetrics.bounds }.getOrNull()
                if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                    screenW = bounds.width()
                    screenH = bounds.height()
                    return
                }
            }
            runCatching {
                val point = Point()
                wm.defaultDisplay.getRealSize(point)
                if (point.x > 0 && point.y > 0) {
                    screenW = point.x
                    screenH = point.y
                    return
                }
            }
        }
        val dm = appContext.resources.displayMetrics
        screenW = if (dm.widthPixels > 0) dm.widthPixels else FALLBACK_SCREEN_W
        screenH = if (dm.heightPixels > 0) dm.heightPixels else FALLBACK_SCREEN_H
    }

    /** 当前是否亮屏；查询失败按亮屏处理，避免误拦启动。 */
    private fun isScreenInteractive(): Boolean =
        runCatching { powerManager?.isInteractive ?: true }.getOrDefault(true)

    // ── 状态流 / 广播 / 杂项 ─────────────────────────────────────────────────

    private fun buildStatus(): EngineStatus = EngineStatus(
        running = running,
        sessionTaps = sessionTaps.get(),
        totalTaps = totalTaps.get(),
        sessionStartedElapsed = sessionStartedElapsed,
        lastStopReason = lastStopReason,
        gestureTimeouts = gestureTimeouts.get(),
        recoveries = recoveries.get(),
        dropped = dropped.get(),
        accessibilityReady = TapAccessibilityService.isConnected,
        waitingForService = waitingForService,
        targetCps = latestConfig.targetCps,
    )

    /** 计数变化节流到 ≤20Hz；start/stop/自愈等关键节点用 force 立即同步。 */
    private fun publishStatus(force: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastStatusUptime < STATUS_MIN_INTERVAL_MS) return
        lastStatusUptime = now
        _status.value = buildStatus()
    }

    private fun registerScreenReceiver() {
        runCatching {
            // SCREEN_ON / SCREEN_OFF 是系统受保护广播，Android 14 的动态接收器导出规则不适用，
            // 直接用最简单的注册方式即可（跨版本最稳，不依赖 androidx 的兼容注册）。
            val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
            filter.addAction(Intent.ACTION_SCREEN_OFF)
            appContext.registerReceiver(screenReceiver, filter)
        }.onFailure { JidianLog.warn(TAG, "注册屏幕状态广播失败：${it.message}") }
    }

    private fun reasonText(reason: String): String = when (reason) {
        StopReason.USER -> "用户停止"
        StopReason.SCREEN_OFF -> "熄屏"
        StopReason.LIMIT_REACHED -> "达到点击上限"
        StopReason.SERVICE_LOST -> "无障碍服务断开"
        StopReason.SHUTDOWN -> "进程退出"
        else -> reason
    }

    /** 手势回调只关心序号；同一个回调对象不跨轮复用，避免过期回调串轮。 */
    private inner class GestureDoneCallback(private val seq: Long) :
        AccessibilityService.GestureResultCallback() {

        override fun onCompleted(gestureDescription: GestureDescription?) {
            onGestureFinished(seq, cancelled = false)
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            onGestureFinished(seq, cancelled = true)
        }
    }

    private companion object {
        private const val TAG = "Engine"
        private const val THREAD_NAME = "tap-engine"

        /** 触点时长范围（毫秒）。 */
        private const val TAP_DURATION_MIN_MS = 1L
        private const val TAP_DURATION_MAX_MS = 8L

        /** 状态流节流：两次 emit 至少间隔 50ms（≤20Hz）。 */
        private const val STATUS_MIN_INTERVAL_MS = 50L

        /** 看门狗下限；实际阈值 = max(下限, 手势时长 + 余量)，长按/滑动也不会误判。 */
        private const val MIN_WATCHDOG_MS = 2_500L
        private const val WATCHDOG_MARGIN_MS = 1_500L

        /** 连续派发失败达到该次数即停：说明服务已不可用。 */
        private const val MAX_CONSECUTIVE_FAILURES = 3

        /** 超时警告的日志预算：前 3 次全记，之后每 20 次记一条，避免刷屏。 */
        private const val TIMEOUT_WARN_BUDGET = 3
        private const val TIMEOUT_WARN_EVERY = 20

        private const val MAX_BUILD_FAILURE_LOGS = 3

        /** 合并连点连续被取消多少次后降级（本会话内改回单次派发）。 */
        private const val MAX_CONSECUTIVE_CANCELS = 5

        /** getMaxStrokeCount() 取不到时的兜底值（框架常量就是 10）。 */
        private const val DEFAULT_MAX_STROKES = 10

        /** 所有系统接口都取不到尺寸时的兜底值。 */
        private const val FALLBACK_SCREEN_W = 1080
        private const val FALLBACK_SCREEN_H = 2400
    }
}
