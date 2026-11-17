package app.jidian.tap.service

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.jidian.tap.JidianApp
import app.jidian.tap.MainActivity
import app.jidian.tap.R
import app.jidian.tap.core.JidianLog

/**
 * 快捷设置磁贴：
 * - [onStartListening] 同步磁贴状态（引擎运行中 ⇒ ACTIVE）与 label/icon；
 * - [onClick]：无障碍未连接 → 打开 MainActivity；已连接 → 开关连点，
 *   无论哪种情况都顺带确保悬浮控制台在跑（[FloatService.show]）。
 */
class TapTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (isEngineReady()) {
            runCatching { JidianApp.from(this).engine.toggle("tile") }
                .onFailure { JidianLog.error(TAG, "磁贴切换连点失败：${it.message}") }
        } else {
            // 无障碍没开：先带用户去主界面把服务打开
            openMain()
        }
        // 同时确保悬浮控制台在跑（缺权限时会由 FloatService 自己引导授权）
        runCatching { FloatService.show(this) }
            .onFailure { JidianLog.warn(TAG, "磁贴启动悬浮窗失败：${it.message}") }
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val running = runCatching { JidianApp.from(this).engine.status.value.running }
            .getOrDefault(false)
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile)
        tile.updateTile()
    }

    /** 无障碍是否就绪：静态连接标记 + 引擎状态双保险（冷启动时二者可能先后置位）。 */
    private fun isEngineReady(): Boolean {
        val connected = runCatching { TapAccessibilityService.isConnected }.getOrDefault(false)
        if (connected) return true
        return runCatching {
            JidianApp.from(this).engine.status.value.accessibilityReady
        }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    private fun openMain() {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    REQ_OPEN_MAIN,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                startActivityAndCollapse(intent)
            }
        } catch (t: Throwable) {
            JidianLog.warn(TAG, "磁贴打开主界面失败，改用 startActivity：${t.message}")
            try {
                startActivity(intent)
            } catch (t2: Throwable) {
                JidianLog.error(TAG, "磁贴打开主界面失败：${t2.message}")
            }
        }
    }

    companion object {
        private const val TAG = "TapTileService"
        private const val REQ_OPEN_MAIN = 20
    }
}
