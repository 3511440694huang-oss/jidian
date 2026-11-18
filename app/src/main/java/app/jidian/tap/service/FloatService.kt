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

    private var sampling = false
    private var lastEngineRunning = false
    private var pendingDx = 0
    private var pendingDy = 0
    private var dragFlushScheduled = false

    // ------------------------------------------------------------------ 生命周期

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayPrefs = getSharedPreferences(OVERLAY_PREFS, Context.MODE_PRIVATE)
        Notifications.ensureChannel(this)

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
                enterPicker()
            }

            else -> {
                // ACTION_SHOW，或系统重启后的空 intent：确保圆盘在
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
        val view = podView ?: return
        val params = podParams ?: return
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
        closePicker()
        collapsePanel()
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

        override fun onDragBy(dx: Int, dy: Int) {
            // 触摸事件比屏幕刷新快：先把位移攒起来，每帧只 updateViewLayout 一次，
            // 避免每个 MOVE 都走一次 WindowManager IPC 造成拖拽发涩。
            pendingDx += dx
            pendingDy += dy
            if (!dragFlushScheduled) {
                dragFlushScheduled = true
                mainHandler.post(dragFlushRunnable)
            }
        }

        override fun onDragFinished() {
            mainHandler.removeCallbacks(dragFlushRunnable)
            dragFlushScheduled = false
            flushDrag()
            val params = podParams ?: return
            applyPodClamp()
            overlayPrefs.edit()
                .putInt(KEY_POD_X, params.x)
                .putInt(KEY_POD_Y, params.y)
                .apply()
        }
    }

    private val dragFlushRunnable = Runnable {
        dragFlushScheduled = false
        flushDrag()
    }

    private fun flushDrag() {
        if (pendingDx == 0 && pendingDy == 0) return
        val view = podView ?: run {
            pendingDx = 0
            pendingDy = 0
            return
        }
        val params = podParams ?: return
        params.x += pendingDx
        params.y += pendingDy
        pendingDx = 0
        pendingDy = 0
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Throwable) {
            // 窗口可能正在被系统移除，忽略
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
        }

        override fun onCancel() {
            closePicker()
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

        private const val OVERLAY_PREFS = "jidian_overlay"
        private const val KEY_POD_X = "x"
        private const val KEY_POD_Y = "y"

        /** 所有悬浮窗公共 flags：铺满屏幕坐标系（含状态栏/导航栏区域） */
        private val FLAG_BASE = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        @Volatile
        var isRunning: Boolean = false
            private set

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
