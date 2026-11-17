package app.jidian.tap.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import app.jidian.tap.core.JidianLog
import app.jidian.tap.service.FloatService
import app.jidian.tap.service.TapAccessibilityService

/** 控制台要展示的四项就绪状态。 */
data class JdPermissions(
    val accessibility: Boolean = false,
    val overlay: Boolean = false,
    val foreground: Boolean = false,
    val notifications: Boolean = false,
)

/**
 * 权限读数与系统设置跳转。所有 Intent 都做兜底：
 * 厂商 ROM 可能砍掉某个 Action，失败时退回应用详情页，并把原因写进 [JidianLog]。
 */
object JdPermission {

    fun read(context: Context): JdPermissions = JdPermissions(
        accessibility = runCatching { TapAccessibilityService.isEnabled(context) }
            .getOrElse {
                JidianLog.warn("UI", "读取无障碍状态失败：${it.message}")
                false
            },
        overlay = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false),
        foreground = runCatching { FloatService.isRunning }.getOrDefault(false),
        notifications = notificationsGranted(context),
    )

    fun notificationsGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            true
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        }

    /** 是否已豁免电池优化。读不到（个别 ROM）时按「未豁免」展示，引导用户去看一眼。 */
    fun batteryUnrestricted(context: Context): Boolean = runCatching {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        power?.isIgnoringBatteryOptimizations(context.packageName) == true
    }.getOrDefault(false)

    /** 系统「关闭动画」时表盘必须静止（DESIGN.md §8）。 */
    fun reduceMotion(context: Context): Boolean = runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }.getOrDefault(false)

    fun openAccessibility(context: Context) {
        runCatching { TapAccessibilityService.openSettings(context) }
            .onFailure { JidianLog.warn("UI", "打开无障碍设置失败：${it.message}") }
    }

    fun openOverlay(context: Context) {
        val direct = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            packageUri(context),
        )
        if (!tryStart(context, direct)) {
            tryStart(context, appDetails(context))
        }
    }

    /**
     * 电池优化：优先申请豁免；个别 ROM 不认 ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS，
     * 退回电池优化列表页，再不行退回应用详情页。
     */
    fun openBattery(context: Context) {
        if (batteryUnrestricted(context)) {
            if (!tryStart(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) {
                tryStart(context, appDetails(context))
            }
            return
        }
        val request = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            packageUri(context),
        )
        if (tryStart(context, request)) return
        if (tryStart(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) return
        tryStart(context, appDetails(context))
    }

    fun openNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        if (!tryStart(context, intent)) {
            tryStart(context, appDetails(context))
        }
    }

    private fun packageUri(context: Context): Uri = Uri.parse("package:${context.packageName}")

    private fun appDetails(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))

    private fun tryStart(context: Context, intent: Intent): Boolean = runCatching {
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure {
        JidianLog.warn("UI", "打开系统设置失败：${it.message}")
    }.isSuccess
}
