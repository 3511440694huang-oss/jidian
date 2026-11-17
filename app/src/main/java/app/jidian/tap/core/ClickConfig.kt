package app.jidian.tap.core

/** 点击动作类型。 */
enum class TapMode { SINGLE, DOUBLE, SWIPE, LONG_PRESS }

/** 落点区域形状。 */
enum class RegionShape { CIRCLE, RECT }

/**
 * 疾点的全部可持久化配置。
 *
 * 坐标系说明：region/overlay 坐标均为**屏幕绝对像素**（含状态栏的整块显示区域），
 * 与 AccessibilityService.dispatchGesture 使用的坐标系一致。
 * regionCx/regionCy 为 -1 表示尚未框选，引擎按当前屏幕中心运行。
 */
data class ClickConfig(
    val intervalMs: Long = 120L,
    val mode: TapMode = TapMode.SINGLE,
    val doubleGapMs: Long = 70L,
    val swipeDurationMs: Long = 240L,
    val swipeDx: Int = 220,
    val swipeDy: Int = 0,
    val longPressMs: Long = 400L,
    val regionShape: RegionShape = RegionShape.CIRCLE,
    val regionCx: Int = -1,
    val regionCy: Int = -1,
    val regionW: Int = 240,
    val regionH: Int = 240,
    val randomOffsetPx: Int = 6,
    val intervalJitterPct: Int = 0,
    val limitCount: Int = 0,
    val pauseOnScreenOff: Boolean = true,
    val haptic: Boolean = false,
) {
    val regionConfigured: Boolean
        get() = regionCx >= 0 && regionCy >= 0

    /** 目标点击频率（次/秒）。 */
    val targetCps: Float
        get() = if (intervalMs <= 0L) 0f else 1000f / intervalMs.toFloat()

    /** 引擎热路径读取的安全版本：所有数值已收敛到合法区间。 */
    fun safe(): ClickConfig = sanitized(this)

    companion object {
        const val MIN_INTERVAL_MS = 10L
        const val MAX_INTERVAL_MS = 10_000L
        const val MIN_DOUBLE_GAP_MS = 20L
        const val MAX_DOUBLE_GAP_MS = 400L
        const val MIN_SWIPE_MS = 60L
        const val MAX_SWIPE_MS = 3_000L
        const val MIN_LONG_PRESS_MS = 120L
        const val MAX_LONG_PRESS_MS = 2_000L
        const val MAX_SWIPE_DISTANCE = 2_000
        const val MIN_OFFSET_PX = 0
        const val MAX_OFFSET_PX = 300
        const val MAX_JITTER_PCT = 80
        const val MAX_REGION_SIDE = 4_000
        const val MAX_LIMIT_COUNT = 1_000_000

        /** 节奏预设（毫秒） */
        val INTERVAL_PRESETS = listOf(20L, 50L, 100L, 200L, 500L, 1_000L)

        val Default = ClickConfig()

        fun sanitized(input: ClickConfig): ClickConfig = input.copy(
            intervalMs = input.intervalMs.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS),
            doubleGapMs = input.doubleGapMs.coerceIn(MIN_DOUBLE_GAP_MS, MAX_DOUBLE_GAP_MS),
            swipeDurationMs = input.swipeDurationMs.coerceIn(MIN_SWIPE_MS, MAX_SWIPE_MS),
            swipeDx = input.swipeDx.coerceIn(-MAX_SWIPE_DISTANCE, MAX_SWIPE_DISTANCE),
            swipeDy = input.swipeDy.coerceIn(-MAX_SWIPE_DISTANCE, MAX_SWIPE_DISTANCE),
            longPressMs = input.longPressMs.coerceIn(MIN_LONG_PRESS_MS, MAX_LONG_PRESS_MS),
            regionW = input.regionW.coerceIn(1, MAX_REGION_SIDE),
            regionH = input.regionH.coerceIn(1, MAX_REGION_SIDE),
            randomOffsetPx = input.randomOffsetPx.coerceIn(MIN_OFFSET_PX, MAX_OFFSET_PX),
            intervalJitterPct = input.intervalJitterPct.coerceIn(0, MAX_JITTER_PCT),
            limitCount = input.limitCount.coerceIn(0, MAX_LIMIT_COUNT),
        )
    }
}
