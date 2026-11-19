package app.jidian.tap.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import app.jidian.tap.JidianApp
import app.jidian.tap.MainActivity
import app.jidian.tap.core.ClickConfig
import app.jidian.tap.core.JidianLog
import app.jidian.tap.core.RegionShape
import app.jidian.tap.core.StopReason
import app.jidian.tap.overlay.ControlPodView
import app.jidian.tap.overlay.OverlayPanelView
import app.jidian.tap.overlay.RegionHandleView
import app.jidian.tap.overlay.RegionPickerView
import app.jidian.tap.util.Notifications

/**
 * 悬浮窗前台服务：承载 52dp 控制圆盘、展开面板与全屏框选层。
 *
 * - 前台通知（specialUse 类型）常驻，带「停止连点」「打开疾点」两个 action；
 * - 三个 overlay 窗口分别独立挂到 WindowManager（不塞进同一个窗口做复杂布局）：
 *   圆盘、面板、框选层，按需增删，全部主线程操作；
 * - 引擎状态 100Hz/10 采样一次（100ms），只把变化推给圆盘，不 collect 每次点击；
 * - 位置落盘到 SharedPreferences "jidian_overlay"，下次启动恢复。
 *
 * 静态入口 [show] / [hide] / [pickRegion] 供 UI 与磁贴调用，[isRunning] 供 UI 读。
 */
class FloatService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayPrefs: SharedPreferences
    private val mainHandler = Handler(Looper.getMainLooper())

    private var podView: ControlPodView? = null
    private var podParams: WindowManager.LayoutParams? = null
    private var panelView: OverlayPanelView? = null
    private var scrimView: View? = null
    private var pickerView: RegionPickerView? = null
    private var handleView: RegionHandleView? = null
    private var handleParams: WindowManager.LayoutParams? = null

    private var sampling = false
    private var lastEngineRunning = false
    private var draggingPod = false
    private var handleDragging = false

    /**
     * 拖动圆盘时如果引擎正在跑，先暂停、松手再恢复。
     *
     * 拖动需要把圆盘窗口临时铺满全屏；无障碍注入的点击会命中这个全屏窗口，
     * 可能反过来触发轻点/长按（甚至把连点停掉）。暂停几百毫秒比出这种怪行为划算，
     * 而且拖动本来就不该一边拖一边点。
     */
    private var resumeEngineAfterPodDrag = false

    /** 配置变化（含落点手柄拖动落盘）都会走这里刷新手柄几何。 */
    private val configListener: (ClickConfig) -> Unit = {
        mainHandler.post { refreshHandle() }
    }

    // ------------------------------------------------------------------ 生命周期

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayPrefs = getSharedPreferences(OVERLAY_PREFS, Context.MODE_PRIVATE)
        Notifications.ensureChannel(this)
        runCatching { JidianApp.from(this).config.addListener(configListener) }

        val runningNow = runCatching { JidianApp.from(this).engine.isRunning }.getOrDefault(false)
        lastEngineRunning = runningNow
        val intervalMs = runCatching { JidianApp.from(this).config.snapshot().intervalMs }
            .getOrDefault(ClickConfig.Default.intervalMs)
        if (!startForegroundCompat(Notifications.build(this, runningNow, intervalMs))) {
            // 进不了前台态就不要留下无主窗口
            isRunning = false
            stopSelf()
            return
        }
        startSampling()
        JidianLog.info(TAG, "悬浮控制台服务启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        // 「停止」必须先于悬浮窗权限检查执行：
        // 用户没给悬浮窗权限时，通知栏的停止 action 是唯一的外部停止入口，
        // 不能被「没有悬浮窗权限 → 直接 end service」的守卫顺手拦掉。
        if (action == ACTION_STOP_ENGINE) {
            stopEngineFromNotification()
            if (podView == null) stopSelf()
            return Service.START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            // 权限被撤销：不叠加任何窗口
            notifyOverlayMissing()
            stopSelf()
            return Service.START_NOT_STICKY
        }
        when (action) {
            ACTION_HIDE -> {
                JidianLog.info(TAG, "收到隐藏指令")
                stopSelf()
                return Service.START_NOT_STICKY
            }

            ACTION_PICK_REGION -> {
                if (!ensurePod()) {
                    stopSelf()
                    return Service.START_NOT_STICKY
                }
                collapsePanel()
                closeHandle()
                enterPicker()
            }

            else -> {
                // ACTION_SHOW，或系统重启后的空 intent。
                // 先挂落点手柄、再挂圆盘：同类型 overlay 后添加的在上层，
                // 这样两者重叠时圆盘仍然可点，不会被手柄挡住。
                refreshHandle()
                if (!ensurePod()) {
                    stopSelf()
                    return Service.START_NOT_STICKY
                }
            }
        }
        startSampling()
        return Service.START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 屏幕方向/尺寸变化：依赖屏幕坐标的浮层收掉，圆盘夹回可视区
        if (pickerView != null) closePicker()
        if (panelView != null || scrimView != null) collapsePanel()
        if (draggingPod) {
            val dragView = podView
            if (dragView != null) finishPodDrag(dragView.dragLeft(), dragView.dragTop())
        }
        closeHandle()
        refreshHandle()
        val view = podView ?: return
        val params = podParams ?: return
        val size = dp(POD_SIZE_DP)
        view.setMetrics(size.toFloat(), screenWidth(), screenHeight())
        clampPodParams(params)
        try {
            windowManager.updateViewLayout(view, params)
        } catch (t: Throwable) {
            JidianLog.warn(TAG, "横竖屏后更新圆盘位置失败：${t.message}")
        }
        mainHandler.removeCallbacks(podReclampRunnable)
        mainHandler.postDelayed(podReclampRunnable, CONFIG_RECLAMP_DELAY_MS)
    }

    override fun onDestroy() {
        isRunning = false
        stopSampling()
        mainHandler.removeCallbacks(podReclampRunnable)
        runCatching { JidianApp.from(this).config.removeListener(configListener) }
        closePicker()
        collapsePanel()
        closeHandle()
        val pod = podView
        podView = null
        podParams = null
        removeWindow(pod)
        try {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
        }
        JidianLog.info(TAG, "悬浮控制台服务停止")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------ 采样

    /** 屏幕配置变化后系统指标可能延迟更新，稍后再夹一次 */
    private val podReclampRunnable = Runnable { applyPodClamp() }

    private val sampleRunnable = object : Runnable {
        override fun run() {
            if (!sampling) return
            sampleEngine()
            mainHandler.postDelayed(this, SAMPLE_INTERVAL_MS)
        }
    }

    private fun startSampling() {
        if (sampling) return
        sampling = true
        mainHandler.removeCallbacks(sampleRunnable)
        mainHandler.post(sampleRunnable)
    }

    private fun stopSampling() {
        sampling = false
        mainHandler.removeCallbacks(sampleRunnable)
    }

    /** 250ms 采样一次引擎状态：圆盘/面板自己比较变化，没变不重绘。 */
    private fun sampleEngine() {
        val status = runCatching { JidianApp.from(this).engine.status.value }.getOrNull() ?: return
        podView?.applyEngineState(status.running)
        panelView?.setRunning(status.running)
        if (status.running != lastEngineRunning) {
            lastEngineRunning = status.running
            updateNotification(status.running)
            applyHandleRunning(status.running)
        }
    }

    private fun updateNotification(running: Boolean) {
        val intervalMs = runCatching { JidianApp.from(this).config.snapshot().intervalMs }
            .getOrDefault(ClickConfig.Default.intervalMs)
        Notifications.update(this, running, intervalMs)
    }

    // ------------------------------------------------------------------ 圆盘

    /** 确保圆盘窗口存在；失败返回 false（不要继续叠加） */
    private fun ensurePod(): Boolean {
        if (podView != null) return true
        if (!Settings.canDrawOverlays(this)) return false

        val view = ControlPodView(this)
        view.listener = podListener
        val size = dp(POD_SIZE_DP)
        view.setMetrics(size.toFloat(), screenWidth(), screenHeight())
        val params = newOverlayParams(
            size,
            size,
            FLAG_BASE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        )
        val savedX = overlayPrefs.getInt(KEY_POD_X, -1)
        val savedY = overlayPrefs.getInt(KEY_POD_Y, -1)
        params.x = if (savedX >= 0) savedX else screenWidth() - size - dp(8f)
        params.y = if (savedY >= 0) savedY else (screenHeight() * 0.42f).toInt()
        clampPodParams(params)

        if (!addWindow(view, params, "pod")) return false
        podView = view
        podParams = params
        // 首帧前先同步一次状态，避免闪一下待命三角
        runCatching { JidianApp.from(this).engine.status.value }
            .getOrNull()
            ?.let { view.applyEngineState(it.running) }
        return true
    }

    private fun applyPodClamp() {
        val view = podView ?: return
        val params = podParams ?: return
        val size = dp(POD_SIZE_DP)
        view.setMetrics(size.toFloat(), screenWidth(), screenHeight())
        clampPodParams(params)
        try {
            windowManager.updateViewLayout(view, params)
        } catch (t: Throwable) {
            JidianLog.warn(TAG, "更新圆盘位置失败：${t.message}")
        }
    }

    private fun clampPodParams(params: WindowManager.LayoutParams) {
        val size = dp(POD_SIZE_DP)
        params.x = params.x.coerceIn(0, (screenWidth() - size).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (screenHeight() - size).coerceAtLeast(0))
    }

    private val podListener = object : ControlPodView.Listener {
        override fun onToggle() {
            toggleEngine()
        }

        override fun onExpandRequested() {
            if (panelView != null) {
                collapsePanel()
            } else {
                expandPanel()
            }
        }

        override fun onDragBegin() {
            beginPodDrag()
        }

        override fun onDragFinished(left: Int, top: Int) {
            finishPodDrag(left, top)
        }
    }

    /**
     * 拖动开始：把圆盘窗口临时铺满全屏。
     *
     * 之后手指的所有位移都由 [ControlPodView] 用屏幕绝对坐标换算成内部偏移，
     * 期间不再调用 updateViewLayout —— 这是「跟手」的关键：
     * 小窗每帧重排既贵，又会造成「窗口追手指、触摸坐标追窗口」的反馈抖动。
     */
    private fun beginPodDrag() {
        val view = podView ?: return
        val params = podParams ?: return
        if (draggingPod) return
        draggingPod = true
        // 先暂停引擎再铺屏：在途批次最多再落 1 批，不会持续打在全屏窗口上
        val engine = runCatching { JidianApp.from(this).engine }.getOrNull()
        resumeEngineAfterPodDrag = engine?.isRunning == true
        if (resumeEngineAfterPodDrag) {
            runCatching { engine?.stop(StopReason.USER) }
            showToast("拖动中已暂停连点")
        }
        val originX = params.x
        val originY = params.y
        params.width = WindowManager.LayoutParams.MATCH_PARENT
        params.height = WindowManager.LayoutParams.MATCH_PARENT
        params.x = 0
        params.y = 0
        try {
            windowManager.updateViewLayout(view, params)
        } catch (t: Throwable) {
            JidianLog.warn(TAG, "进入拖动模式失败：${t.message}")
        }
        view.beginDrag(originX, originY)
    }

    /** 拖动结束：缩回 52dp 窗口落到最终位置并持久化。 */
    private fun finishPodDrag(left: Int, top: Int) {
        val view = podView ?: return
        val params = podParams ?: return
        if (!draggingPod) return
        draggingPod = false
        val size = dp(POD_SIZE_DP)
        params.width = size
        params.height = size
        params.x = left
        params.y = top
        clampPodParams(params)
        try {
            windowManager.updateViewLayout(view, params)
        } catch (t: Throwable) {
            JidianLog.warn(TAG, "退出拖动模式失败：${t.message}")
        }
        view.endDrag()
        overlayPrefs.edit()
            .putInt(KEY_POD_X, params.x)
            .putInt(KEY_POD_Y, params.y)
            .apply()
        // 窗口已缩回 52dp 再恢复连点，避免恢复后的点击又打在全屏窗口上
        if (resumeEngineAfterPodDrag) {
            resumeEngineAfterPodDrag = false
            val engine = runCatching { JidianApp.from(this).engine }.getOrNull()
            if (engine != null && TapAccessibilityService.isConnected) {
                runCatching { engine.start("overlay") }
                showToast("已恢复连点")
                // 立刻把落点手柄切回「不可触摸」，否则恢复后的头 250ms 点击会被手柄吃掉
                sampleEngine()
            } else {
                showToast("无障碍未就绪，连点未恢复")
            }
        }
    }

    // ------------------------------------------------------------------ 面板

    private fun expandPanel() {
        if (panelView != null) return
        if (!Settings.canDrawOverlays(this)) {
            notifyOverlayMissing()
            return
        }
        if (!ensurePod()) return
        // 先挂全屏透明的「点外部收起」层，再挂面板 → 面板在上层、圆盘被其覆盖但
        // 点圆盘区域同样会落到 scrim 上，效果就是「点圆盘或面板外收起」。
        addScrimWindow()
        if (!addPanelWindow()) {
            // 面板没挂上就撤掉 scrim，否则整屏触摸被这层吞掉
            val scrim = scrimView
            scrimView = null
            removeWindow(scrim)
        }
    }

    private fun addScrimWindow() {
        if (scrimView != null) return
        val view = View(this)
        view.setBackgroundColor(Color.TRANSPARENT)
        view.setOnClickListener { collapsePanel() }
        val params = newOverlayParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            FLAG_BASE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        )
        if (addWindow(view, params, "scrim")) {
            scrimView = view
        }
    }

    private fun addPanelWindow(): Boolean {
        if (panelView != null) return true
        val podWin = podParams ?: return false
        val panelWidth = dp(OverlayPanelView.PANEL_WIDTH_DP)
        val panelHeight = dp(OverlayPanelView.PANEL_HEIGHT_DP)
        val view = OverlayPanelView(this)
        view.listener = panelListener
        val params = newOverlayParams(
            panelWidth,
            panelHeight,
            FLAG_BASE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        )
        val podSize = dp(POD_SIZE_DP)
        val gap = dp(8f)
        var panelX = podWin.x + podSize / 2 - panelWidth / 2
        var panelY = podWin.y - panelHeight - gap
        if (panelY < 0) panelY = podWin.y + podSize + gap
        panelX = panelX.coerceIn(0, (screenWidth() - panelWidth).coerceAtLeast(0))
        panelY = panelY.coerceIn(0, (screenHeight() - panelHeight).coerceAtLeast(0))
        params.x = panelX
        params.y = panelY
        if (addWindow(view, params, "panel")) {
            panelView = view
            return true
        }
        return false
    }

    private fun collapsePanel() {
        val panel = panelView
        panelView = null
        removeWindow(panel)
        val scrim = scrimView
        scrimView = null
        removeWindow(scrim)
    }

    private val panelListener = object : OverlayPanelView.Listener {
        override fun onPickRegion() {
            collapsePanel()
            if (!Settings.canDrawOverlays(this@FloatService)) {
                notifyOverlayMissing()
                return
            }
            enterPicker()
        }

        override fun onOpenApp() {
            collapsePanel()
            launchMain()
        }

        override fun onToggle() {
            toggleEngine()
            // 启停是「一次性的决定」：按完就收起面板，别让全屏 scrim 继续挡着目标 App
            collapsePanel()
        }
    }

    // ------------------------------------------------------------------ 落点手柄

    /**
     * 手柄窗口几何：窗口 = 落点区域 + 12dp 内边距，中心即真正的点击中心。
     * 贴边时窗口被夹进屏幕，返回的 cx/cy 也用夹过之后的中心，保证「看到哪就点到哪」。
     */
    private data class HandleGeometry(
        val cx: Int,
        val cy: Int,
        val regionW: Int,
        val regionH: Int,
        val winW: Int,
        val winH: Int,
        val x: Int,
        val y: Int,
    )

    private fun currentConfig(): ClickConfig =
        runCatching { JidianApp.from(this).config.snapshot() }.getOrDefault(ClickConfig.Default)

    private fun computeHandleGeometry(
        cfg: ClickConfig,
        overrideCx: Int? = null,
        overrideCy: Int? = null,
    ): HandleGeometry {
        val sw = screenWidth()
        val sh = screenHeight()
        val pad = dp(RegionHandleView.PAD_DP)
        val minSide = dp(MIN_HANDLE_SIDE_DP)
        val rw = cfg.regionW.coerceIn(minSide, sw)
        val rh = cfg.regionH.coerceIn(minSide, sh)
        val winW = minOf(sw, maxOf(rw + pad * 2, minSide + pad * 2))
        val winH = minOf(sh, maxOf(rh + pad * 2, minSide + pad * 2))
        val rawCx = overrideCx ?: if (cfg.regionConfigured) cfg.regionCx else sw / 2
        val rawCy = overrideCy ?: if (cfg.regionConfigured) cfg.regionCy else sh / 2
        val x = (rawCx - winW / 2).coerceIn(0, (sw - winW).coerceAtLeast(0))
        val y = (rawCy - winH / 2).coerceIn(0, (sh - winH).coerceAtLeast(0))
        return HandleGeometry(
            cx = x + winW / 2,
            cy = y + winH / 2,
            regionW = rw,
            regionH = rh,
            winW = winW,
            winH = winH,
            x = x,
            y = y,
        )
    }

    /**
     * 按当前配置增删/更新落点手柄。
     *
     * 引擎运行时手柄会加 FLAG_NOT_TOUCHABLE：注入的点击直接穿过去落在目标 App 上，
     * 手柄只剩一层很淡的指示框；停止时恢复可拖动。
     */
    private fun refreshHandle(cfg: ClickConfig = currentConfig()) {
        if (!Settings.canDrawOverlays(this)) {
            closeHandle()
            return
        }
        // 框选是全屏覆盖层，先让位；框选结束后会重新刷新
        if (!cfg.showRegionHandle || pickerView != null) {
            closeHandle()
            return
        }
        if (handleDragging) return
        val geom = computeHandleGeometry(cfg)
        val view = handleView
        if (view == null) {
            createHandle(cfg, geom)
        } else {
            updateHandle(cfg, geom, view)
        }
    }

    private fun createHandle(cfg: ClickConfig, geom: HandleGeometry) {
        val view = RegionHandleView(this)
        view.listener = handleListener
        view.setMetrics(geom.winW, geom.winH, screenWidth(), screenHeight())
        view.setRegion(cfg.regionShape, geom.regionW, geom.regionH)
        val running = runCatching { JidianApp.from(this).engine.isRunning }.getOrDefault(false)
        val params = newOverlayParams(
            geom.winW,
            geom.winH,
            FLAG_BASE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                (if (running) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0),
        )
        params.x = geom.x
        params.y = geom.y
        if (!addWindow(view, params, "handle")) return
        view.setRunning(running)
        handleView = view
        handleParams = params
        JidianLog.info(TAG, "落点手柄已显示（${geom.regionW}×${geom.regionH} @${geom.cx},${geom.cy}）")
        if (!handleHintShown) {
            handleHintShown = true
            showToast("拖动半透明落点框可定位，长按展开面板")
        }
    }

    private fun updateHandle(cfg: ClickConfig, geom: HandleGeometry, view: RegionHandleView) {
        val params = handleParams ?: return
        view.setMetrics(geom.winW, geom.winH, screenWidth(), screenHeight())
        view.setRegion(cfg.regionShape, geom.regionW, geom.regionH)
        var changed = false
        if (params.width != geom.winW) {
            params.width = geom.winW
            changed = true
        }
        if (params.height != geom.winH) {
            params.height = geom.winH
            changed = true
        }
        if (params.x != geom.x) {
            params.x = geom.x
            changed = true
        }
        if (params.y != geom.y) {
            params.y = geom.y
            changed = true
        }
        if (changed) {
            try {
                windowManager.updateViewLayout(view, params)
            } catch (t: Throwable) {
                JidianLog.warn(TAG, "更新落点手柄失败：${t.message}")
            }
        }
    }

    private fun applyHandleRunning(running: Boolean) {
        val view = handleView ?: return
        val params = handleParams ?: return
        view.setRunning(running)
        val flags = if (running) {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        if (flags == params.flags) return
        params.flags = flags
        try {
            windowManager.updateViewLayout(view, params)
        } catch (t: Throwable) {
            JidianLog.warn(TAG, "切换落点手柄触摸状态失败：${t.message}")
        }
    }

    private fun closeHandle() {
        val view = handleView
        handleView = null
        handleParams = null
        // 转屏 / 服务销毁可能正好打断一次手柄拖动：不清标志的话 refreshHandle 会永远跳过重建
        handleDragging = false
        removeWindow(view)
    }

    private val handleListener = object : RegionHandleView.Listener {
        override fun onDragBegin() {
            val view = handleView ?: return
            val params = handleParams ?: return
            if (handleDragging) return
            handleDragging = true
            val originX = params.x
            val originY = params.y
            params.width = WindowManager.LayoutParams.MATCH_PARENT
            params.height = WindowManager.LayoutParams.MATCH_PARENT
            params.x = 0
            params.y = 0
            try {
                windowManager.updateViewLayout(view, params)
            } catch (t: Throwable) {
                JidianLog.warn(TAG, "进入手柄拖动模式失败：${t.message}")
            }
            view.beginDrag(originX, originY)
        }

        override fun onDragFinished(centerX: Int, centerY: Int) {
            finishHandleDrag(centerX, centerY)
        }

        override fun onOpenPanel() {
            expandPanel()
        }
    }

    private fun finishHandleDrag(centerX: Int, centerY: Int) {
        val view = handleView ?: return
        val params = handleParams ?: return
        if (!handleDragging) return
        handleDragging = false
        val cfg = currentConfig()
        val geom = computeHandleGeometry(cfg, centerX, centerY)
        params.width = geom.winW
        params.height = geom.winH
        params.x = geom.x
        params.y = geom.y
        try {
            windowManager.updateViewLayout(view, params)
        } catch (t: Throwable) {
            JidianLog.warn(TAG, "退出手柄拖动模式失败：${t.message}")
        }
        view.endDrag()
        view.setMetrics(geom.winW, geom.winH, screenWidth(), screenHeight())
        // 写入区域中心 → 引擎下一次点击就用新位置；listener 会再刷新一次几何（幂等）
        runCatching {
            JidianApp.from(this).config.update {
                it.copy(regionCx = geom.cx, regionCy = geom.cy)
            }
        }.onFailure { JidianLog.error(TAG, "保存落点手柄位置失败：${it.message}") }
        JidianLog.info(TAG, "落点手柄移动到 (${geom.cx}, ${geom.cy})")
    }

    // ------------------------------------------------------------------ 框选

    private fun enterPicker() {
        if (pickerView != null) return
        if (!Settings.canDrawOverlays(this)) {
            notifyOverlayMissing()
            return
        }
        val config = runCatching { JidianApp.from(this).config.snapshot() }
            .getOrDefault(ClickConfig.Default)
        val view = RegionPickerView(
            this,
            config.regionShape,
            config.regionCx,
            config.regionCy,
            config.regionW,
            config.regionH,
        )
        view.listener = pickerListener
        val params = newOverlayParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            FLAG_BASE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        )
        if (addWindow(view, params, "picker")) {
            pickerView = view
            JidianLog.info(TAG, "进入框选态")
        }
    }

    private fun closePicker() {
        val view = pickerView
        pickerView = null
        removeWindow(view)
    }

    private val pickerListener = object : RegionPickerView.Listener {
        override fun onConfirm(shape: RegionShape, centerX: Int, centerY: Int, width: Int, height: Int) {
            try {
                JidianApp.from(this@FloatService).config.update {
                    it.copy(
                        regionShape = shape,
                        regionCx = centerX,
                        regionCy = centerY,
                        regionW = width,
                        regionH = height,
                    )
                }
                JidianLog.info(
                    TAG,
                    "落点已更新：${shape.name} ${width}×${height} @($centerX, $centerY)",
                )
            } catch (t: Throwable) {
                JidianLog.error(TAG, "保存落点失败：${t.message}")
            }
            closePicker()
            refreshHandle()
        }

        override fun onCancel() {
            closePicker()
            refreshHandle()
            JidianLog.info(TAG, "取消框选")
        }
    }

    // ------------------------------------------------------------------ 引擎 / 主界面

    private fun toggleEngine() {
        val wasRunning = runCatching { JidianApp.from(this).engine.isRunning }.getOrDefault(false)
        runCatching { JidianApp.from(this).engine.toggle("overlay") }
            .onFailure { JidianLog.error(TAG, "切换连点失败：${it.message}") }
        val runningNow = runCatching { JidianApp.from(this).engine.isRunning }.getOrDefault(false)
        when {
            runningNow -> {
                JidianLog.info(TAG, "悬浮窗启动连点")
                showToast("已开始 · 轻点圆盘停止")
            }

            wasRunning -> {
                JidianLog.info(TAG, "悬浮窗停止连点")
                showToast("已停止")
            }

            else -> {
                JidianLog.warn(TAG, "连点启动失败：无障碍服务未就绪")
                showToast("无障碍服务未开启")
            }
        }
        sampleEngine()
    }

    private fun stopEngineFromNotification() {
        // 通知栏的停止是用户明确意图，不能被「拖动结束自动恢复」翻回来
        resumeEngineAfterPodDrag = false
        runCatching { JidianApp.from(this).engine.stop(StopReason.USER) }
            .onFailure { JidianLog.error(TAG, "停止连点失败：${it.message}") }
        JidianLog.info(TAG, "通知栏停止连点")
        sampleEngine()
    }

    private fun launchMain() {
        try {
            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
        } catch (t: Throwable) {
            JidianLog.error(TAG, "打开主界面失败：${t.message}")
        }
    }

    private fun showToast(text: String) {
        try {
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
        }
    }

    private fun notifyOverlayMissing() {
        JidianLog.warn(TAG, "悬浮窗未授权")
        showToast("悬浮窗未授权")
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (t: Throwable) {
            JidianLog.error(TAG, "打开悬浮窗权限设置失败：${t.message}")
        }
    }

    // ------------------------------------------------------------------ 窗口工具

    private fun startForegroundCompat(notification: Notification): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    Notifications.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(Notifications.NOTIFICATION_ID, notification)
            }
            true
        } catch (t: Throwable) {
            JidianLog.error(TAG, "前台服务启动失败：${t.javaClass.simpleName} ${t.message}")
            false
        }
    }

    private fun newOverlayParams(
        width: Int,
        height: Int,
        flags: Int,
    ): WindowManager.LayoutParams {
        val params = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            } catch (_: Throwable) {
                // 个别 ROM 不接受，退化为默认模式
            }
        }
        return params
    }

    private fun addWindow(view: View, params: WindowManager.LayoutParams, tag: String): Boolean {
        return try {
            windowManager.addView(view, params)
            true
        } catch (t: Throwable) {
            JidianLog.error(TAG, "添加悬浮窗失败（$tag）：${t.javaClass.simpleName} ${t.message}")
            false
        }
    }

    /** 幂等移除：窗口可能已经被系统回收 */
    private fun removeWindow(view: View?) {
        if (view == null) return
        try {
            windowManager.removeViewImmediate(view)
        } catch (_: Throwable) {
        }
    }

    private fun screenWidth(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { windowManager.currentWindowMetrics.bounds.width() }
                .getOrNull()
                ?.let { if (it > 0) return it }
        }
        return resources.displayMetrics.widthPixels
    }

    private fun screenHeight(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { windowManager.currentWindowMetrics.bounds.height() }
                .getOrNull()
                ?.let { if (it > 0) return it }
        }
        return resources.displayMetrics.heightPixels
    }

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        const val ACTION_SHOW = "app.jidian.tap.action.SHOW"
        const val ACTION_HIDE = "app.jidian.tap.action.HIDE"
        const val ACTION_PICK_REGION = "app.jidian.tap.action.PICK_REGION"
        const val ACTION_STOP_ENGINE = "app.jidian.tap.action.STOP_ENGINE"

        private const val TAG = "FloatService"
        private const val SAMPLE_INTERVAL_MS = 250L
        private const val CONFIG_RECLAMP_DELAY_MS = 350L
        /** 圆盘窗口边长（dp）。视觉圆盘比窗口再小一圈，见 ControlPodView。 */
        private const val POD_SIZE_DP = 52f

        /** 落点手柄最小边长（dp），避免用户把区域调太小后手柄抓不住。 */
        private const val MIN_HANDLE_SIDE_DP = 48f

        private const val OVERLAY_PREFS = "jidian_overlay"
        private const val KEY_POD_X = "x"
        private const val KEY_POD_Y = "y"

        /** 所有悬浮窗公共 flags：铺满屏幕坐标系（含状态栏/导航栏区域） */
        private val FLAG_BASE = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        @Volatile
        var isRunning: Boolean = false
            private set

        /** 本次进程内只提示一次拖动技巧，避免每次显示悬浮窗都弹 Toast。 */
        private var handleHintShown = false

        /** 显示控制圆盘（先检查悬浮窗权限）。 */
        fun show(context: Context) {
            if (!requestOverlayPermission(context)) return
            start(context, ACTION_SHOW)
        }

        /** 隐藏并停止前台服务（圆盘/面板/框选层一并移除）。 */
        fun hide(context: Context) {
            try {
                context.stopService(Intent(context, FloatService::class.java))
            } catch (t: Throwable) {
                JidianLog.warn(TAG, "隐藏悬浮窗失败：${t.message}")
            }
        }

        /** 进入框选态：服务没跑就先拉起，再进入。 */
        fun pickRegion(context: Context) {
            if (!requestOverlayPermission(context)) return
            start(context, ACTION_PICK_REGION)
        }

        private fun start(context: Context, action: String) {
            val intent = Intent(context, FloatService::class.java).setAction(action)
            try {
                context.startForegroundService(intent)
            } catch (t: Throwable) {
                try {
                    context.startService(intent)
                } catch (t2: Throwable) {
                    JidianLog.error(TAG, "启动悬浮服务失败：${t2.message}")
                }
            }
        }

        /** true = 已有悬浮窗权限；false = 已提示并尝试打开系统设置。 */
        private fun requestOverlayPermission(context: Context): Boolean {
            if (Settings.canDrawOverlays(context)) return true
            JidianLog.warn(TAG, "缺少悬浮窗权限")
            try {
                Toast.makeText(context, "悬浮窗未授权", Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {
            }
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                )
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (t: Throwable) {
                JidianLog.error(TAG, "打开悬浮窗权限设置失败：${t.message}")
            }
            return false
        }
    }
}
