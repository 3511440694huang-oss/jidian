package app.jidian.tap

import android.app.Application
import android.content.Context
import app.jidian.tap.core.ConfigStore
import app.jidian.tap.core.JidianLog
import app.jidian.tap.core.TapEngine

/**
 * 进程级依赖容器：配置中心 + 连点引擎各一份，Activity / 悬浮窗 / 无障碍服务共用。
 */
class JidianApp : Application() {

    lateinit var config: ConfigStore
        private set

    lateinit var engine: TapEngine
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        config = ConfigStore(this)
        engine = TapEngine(this, config)
        JidianLog.info("App", "疾点启动 v${BuildConfig.VERSION_NAME}（API ${android.os.Build.VERSION.SDK_INT}）")
    }

    companion object {
        @Volatile
        private var instance: JidianApp? = null

        fun from(context: Context): JidianApp {
            instance?.let { return it }
            return context.applicationContext as JidianApp
        }
    }
}
