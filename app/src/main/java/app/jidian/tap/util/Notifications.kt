package app.jidian.tap.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import app.jidian.tap.MainActivity
import app.jidian.tap.R
import app.jidian.tap.core.JidianLog
import app.jidian.tap.service.FloatService

/**
 * 前台服务通知：渠道 `jidian_float`（低优先级、不响铃），
 * 常驻通知带「停止连点」（路由回 [FloatService.ACTION_STOP_ENGINE]）与「打开疾点」两个 action。
 *
 * 通知只是状态提示：POST_NOTIFICATIONS 未授权时系统不显示，服务照常运行。
 */
object Notifications {

    const val CHANNEL_ID = "jidian_float"
    const val NOTIFICATION_ID = 1001

    private const val TAG = "Notifications"
    private const val TITLE_IDLE = "疾点待命"

    private const val REQ_CONTENT = 10
    private const val REQ_OPEN = 11
    private const val REQ_STOP = 12

    private val PI_FLAGS =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    /** 建渠道（API 26+ 恒有渠道；重复创建同 id 安全）。 */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_desc)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    /** 构建常驻通知：运行中/待命两种文案，含停止与打开两个 action。 */
    fun build(context: Context, running: Boolean, intervalMs: Long): Notification {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val contentIntent = PendingIntent.getActivity(context, REQ_CONTENT, openIntent, PI_FLAGS)
        val openActionIntent = PendingIntent.getActivity(context, REQ_OPEN, openIntent, PI_FLAGS)
        val stopActionIntent = PendingIntent.getService(
            context,
            REQ_STOP,
            Intent(context, FloatService::class.java).setAction(FloatService.ACTION_STOP_ENGINE),
            PI_FLAGS,
        )
        val icon = Icon.createWithResource(context, R.drawable.ic_notification)

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (running) context.getString(R.string.notification_title) else TITLE_IDLE)
            .setContentText(
                if (running) {
                    "间隔 $intervalMs ms · 轻点圆盘停止"
                } else {
                    context.getString(R.string.notification_text)
                },
            )
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
        builder.addAction(
            Notification.Action.Builder(
                icon,
                context.getString(R.string.notification_action_stop),
                stopActionIntent,
            ).build(),
        )
        builder.addAction(
            Notification.Action.Builder(
                icon,
                context.getString(R.string.notification_action_open),
                openActionIntent,
            ).build(),
        )
        return builder.build()
    }

    /** 刷新常驻通知（未授权通知权限时系统静默忽略，不抛错）。 */
    fun update(context: Context, running: Boolean, intervalMs: Long) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        try {
            manager.notify(NOTIFICATION_ID, build(context, running, intervalMs))
        } catch (t: Throwable) {
            JidianLog.warn(TAG, "更新通知失败：${t.message}")
        }
    }
}
