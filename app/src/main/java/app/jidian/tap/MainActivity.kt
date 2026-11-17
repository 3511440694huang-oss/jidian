package app.jidian.tap

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.jidian.tap.ui.ConsoleScreen
import app.jidian.tap.ui.theme.JidianTheme

/**
 * 疾点唯一 Activity：纸白仪器面板，浅色系统栏，内容延伸到系统栏后面。
 * 启动模式 singleTask 在 Manifest 中（悬浮窗/磁贴/通知都回到这一处）。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        setContent {
            JidianTheme {
                ConsoleScreen()
            }
        }
    }
}
