package app.jidian.tap.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import app.jidian.tap.JidianApp
import app.jidian.tap.core.JidianLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 疾点唯一的无障碍服务：只做手势派发，不读屏。
 *
 * 职责边界：
 *  - 维护全局单例引用 [instance]，供引擎 / 悬浮窗 / UI 判断服务是否就绪；
 *  - 生命周期回调转告 [app.jidian.tap.core.TapEngine]（连上可自动开始，断开立即停）；
 *  - 不处理任何窗口事件（manifest 里 canRetrieveWindowContent=false）。
 *
 * 服务配置（canPerformGestures=true 等）由 manifest + res/xml/accessibility_service_config.xml 提供。
 */
class TapAccessibilityService : AccessibilityService() {

    /** 断开只通知一次：onUnbind 与 onDestroy 可能先后都到。 */
    private val released = AtomicBoolean(false)

    override fun onServiceConnected() {
        super.onServiceConnected()
        released.set(false)
        instance = this
        JidianLog.info(TAG, "无障碍服务已连接")
        notifyEngine(connected = true)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        handleDisconnected("onUnbind")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        handleDisconnected("onDestroy")
        super.onDestroy()
    }

    /** 不读屏，也不关心任何窗口事件。 */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    /** 疾点不打断已有手势，中断回调保持空实现。 */
    override fun onInterrupt() = Unit

    private fun handleDisconnected(where: String) {
        if (!released.compareAndSet(false, true)) return
        // 先清引用再通知引擎，保证引擎读到的 accessibilityReady 已是 false
        instance = null
        JidianLog.info(TAG, "无障碍服务已断开（$where）")
        notifyEngine(connected = false)
    }

    private fun notifyEngine(connected: Boolean) {
        val app = runCatching { JidianApp.from(applicationContext) }.getOrNull()
        if (app == null) {
            JidianLog.warn(TAG, "引擎尚未就绪，跳过无障碍状态通知")
            return
        }
        runCatching {
            if (connected) app.engine.onAccessibilityConnected() else app.engine.onAccessibilityDisconnected()
        }.onFailure { JidianLog.error(TAG, "通知引擎失败：${it.message}") }
    }

    companion object {
        private const val TAG = "Accessibility"

        /** 本服务的短类名，少量 ROM 的启用列表只写短名时用于兜底匹配。 */
        private const val SHORT_CLASS_NAME = "TapAccessibilityService"

        @Volatile
        var instance: TapAccessibilityService? = null
            private set

        /** 服务是否已连接（真正可派发手势）。 */
        val isConnected: Boolean
            get() = instance != null

        /**
         * 无障碍服务是否在系统里被启用。
         *
         * 以 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES 为准：服务启用但进程刚重启、
         * 尚未回调 onServiceConnected 时也要能给出正确结果。
         * 启用项可能是「包名/完整类名」或「包名/.短类名」，统一交给 ComponentName.unflattenFromString 解析。
         */
        fun isEnabled(context: Context): Boolean {
            if (isConnected) return true
            val app = context.applicationContext
            val enabled = runCatching {
                Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            }.getOrNull()
            if (enabled.isNullOrEmpty()) return false
            val pkg = app.packageName
            val cls = TapAccessibilityService::class.java.name
            for (raw in enabled.split(':')) {
                val entry = raw.trim()
                if (entry.isEmpty()) continue
                val component = ComponentName.unflattenFromString(entry)
                if (component != null) {
                    if (component.packageName == pkg && component.className == cls) return true
                } else if (entry.contains(pkg) && entry.contains(SHORT_CLASS_NAME)) {
                    return true
                }
            }
            return false
        }

        /**
         * 打开系统的无障碍设置页。
         * Android 没有官方 extra 能精确定位到某一个服务，这里打开列表页；
         * 失败时退到「设置」首页，并把原因写进日志。
         */
        fun openSettings(context: Context) {
            val app = context.applicationContext
            val accessibility = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { app.startActivity(accessibility) }
                .onFailure { error ->
                    JidianLog.error(TAG, "打开无障碍设置失败：${error.message}")
                    runCatching {
                        app.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    }
                }
        }
    }
}
