package app.jidian.tap.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 配置中心：SharedPreferences 持久化 + StateFlow 热更新。
 *
 * 写入是同步的（[save] 返回时磁盘已提交、监听者已收到新值），
 * 悬浮窗框选确认后立刻生效，UI 与引擎读取的是同一份数据。
 */
class ConfigStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<ClickConfig> = _state.asStateFlow()
    private val listeners = CopyOnWriteArrayList<(ClickConfig) -> Unit>()

    fun snapshot(): ClickConfig = _state.value

    @Synchronized
    fun save(config: ClickConfig) {
        val clean = ClickConfig.sanitized(config)
        persist(clean)
        _state.value = clean
        for (listener in listeners) {
            runCatching { listener(clean) }
        }
    }

    fun update(transform: (ClickConfig) -> ClickConfig) {
        save(transform(_state.value))
    }

    fun addListener(listener: (ClickConfig) -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: (ClickConfig) -> Unit) {
        listeners.remove(listener)
    }

    fun reset() {
        prefs.edit().clear().apply()
        save(ClickConfig.Default)
    }

    private fun load(): ClickConfig {
        val d = ClickConfig.Default
        val mode = runCatching {
            TapMode.valueOf(prefs.getString(KEY_MODE, d.mode.name) ?: d.mode.name)
        }.getOrDefault(d.mode)
        val shape = runCatching {
            RegionShape.valueOf(prefs.getString(KEY_SHAPE, d.regionShape.name) ?: d.regionShape.name)
        }.getOrDefault(d.regionShape)
        val loaded = ClickConfig(
            intervalMs = prefs.getLong(KEY_INTERVAL, d.intervalMs),
            mode = mode,
            doubleGapMs = prefs.getLong(KEY_DOUBLE_GAP, d.doubleGapMs),
            swipeDurationMs = prefs.getLong(KEY_SWIPE_MS, d.swipeDurationMs),
            swipeDx = prefs.getInt(KEY_SWIPE_DX, d.swipeDx),
            swipeDy = prefs.getInt(KEY_SWIPE_DY, d.swipeDy),
            longPressMs = prefs.getLong(KEY_LONG_PRESS, d.longPressMs),
            regionShape = shape,
            regionCx = prefs.getInt(KEY_RX, d.regionCx),
            regionCy = prefs.getInt(KEY_RY, d.regionCy),
            regionW = prefs.getInt(KEY_RW, d.regionW),
            regionH = prefs.getInt(KEY_RH, d.regionH),
            randomOffsetPx = prefs.getInt(KEY_OFFSET, d.randomOffsetPx),
            intervalJitterPct = prefs.getInt(KEY_JITTER, d.intervalJitterPct),
            limitCount = prefs.getInt(KEY_LIMIT, d.limitCount),
            pauseOnScreenOff = prefs.getBoolean(KEY_SCREEN_OFF, d.pauseOnScreenOff),
            haptic = prefs.getBoolean(KEY_HAPTIC, d.haptic),
            batchTaps = prefs.getBoolean(KEY_BATCH, d.batchTaps),
            showRegionHandle = prefs.getBoolean(KEY_HANDLE, d.showRegionHandle),
        )
        return ClickConfig.sanitized(loaded)
    }

    private fun persist(c: ClickConfig) {
        prefs.edit()
            .putLong(KEY_INTERVAL, c.intervalMs)
            .putString(KEY_MODE, c.mode.name)
            .putLong(KEY_DOUBLE_GAP, c.doubleGapMs)
            .putLong(KEY_SWIPE_MS, c.swipeDurationMs)
            .putInt(KEY_SWIPE_DX, c.swipeDx)
            .putInt(KEY_SWIPE_DY, c.swipeDy)
            .putLong(KEY_LONG_PRESS, c.longPressMs)
            .putString(KEY_SHAPE, c.regionShape.name)
            .putInt(KEY_RX, c.regionCx)
            .putInt(KEY_RY, c.regionCy)
            .putInt(KEY_RW, c.regionW)
            .putInt(KEY_RH, c.regionH)
            .putInt(KEY_OFFSET, c.randomOffsetPx)
            .putInt(KEY_JITTER, c.intervalJitterPct)
            .putInt(KEY_LIMIT, c.limitCount)
            .putBoolean(KEY_SCREEN_OFF, c.pauseOnScreenOff)
            .putBoolean(KEY_HAPTIC, c.haptic)
            .putBoolean(KEY_BATCH, c.batchTaps)
            .putBoolean(KEY_HANDLE, c.showRegionHandle)
            .apply()
    }

    companion object {
        private const val PREFS = "jidian_config"
        private const val KEY_INTERVAL = "interval_ms"
        private const val KEY_MODE = "mode"
        private const val KEY_DOUBLE_GAP = "double_gap_ms"
        private const val KEY_SWIPE_MS = "swipe_ms"
        private const val KEY_SWIPE_DX = "swipe_dx"
        private const val KEY_SWIPE_DY = "swipe_dy"
        private const val KEY_LONG_PRESS = "long_press_ms"
        private const val KEY_SHAPE = "region_shape"
        private const val KEY_RX = "region_cx"
        private const val KEY_RY = "region_cy"
        private const val KEY_RW = "region_w"
        private const val KEY_RH = "region_h"
        private const val KEY_OFFSET = "offset_px"
        private const val KEY_JITTER = "jitter_pct"
        private const val KEY_LIMIT = "limit_count"
        private const val KEY_SCREEN_OFF = "pause_screen_off"
        private const val KEY_HAPTIC = "haptic"
        private const val KEY_BATCH = "batch_taps"
        private const val KEY_HANDLE = "show_region_handle"
    }
}
