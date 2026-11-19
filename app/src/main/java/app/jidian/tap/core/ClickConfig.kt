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
 *
 * 节奏下限放到 1ms：给人「极限速度」的上限入口，实际能跑多快由系统的
 * accessibility 手势注入能力决定，界面上的「实际频率」显示的是实测值。
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
    /**
     * 合并连点：把极限档的多次点击压进**一个** GestureDescription 的多个 Stroke，
     * 用一次派发开销换多次点击（单次 dispatchGesture 往返约 5–7ms，是 1ms 档跑不快的根因）。
     * 关掉后回到「一次手势一次点击」，兼容对多点触控敏感的目标。
     */
    val batchTaps: Boolean = true,
    /** 是否在屏幕上显示半透明落点手柄（可直接拖动定位）。 */
    val showRegionHandle: Boolean = true,
) {
    val regionConfigured: Boolean
        get() = regionCx >= 0 && regionCy >= 0

    /** 目标点击频率（次/秒）。 */
    val targetCps: Float
        get() = if (intervalMs <= 0L) 0f else 1000f / intervalMs.toFloat()

    /** 是否为「极限节奏」：间隔已经短到由系统注入速度决定。 */
    val ultraFast: Boolean
        get() = intervalMs <= ULTRA_FAST_THRESHOLD_MS

    /** 引擎热路径读取的安全版本：所有数值已收敛到合法区间。 */
    fun safe(): ClickConfig = sanitized(this)

    companion object {
        /** 节奏下限 1ms（上限 1000 次/秒的输入目标，实测由系统决定）。 */
        const val MIN_INTERVAL_MS = 1L
        const val MAX_INTERVAL_MS = 10_000L

        /** ≤ 该值视为极限节奏：触点时长压到 1ms，不再塞随机触点时长。 */
        const val ULTRA_FAST_THRESHOLD_MS = 16L

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

        /** 主界面快捷档位（毫秒）：1ms 起，覆盖「极限」到「慢速」。 */
        val INTERVAL_PRESETS = listOf(1L, 10L, 50L, 100L, 500L, 1_000L)

        /**
         * 面板 [−][+] 的步进阶梯（毫秒）。
         *
         * 低端按 1/2/5/10… 递增，保证从 1ms 也能一档一档往上走；
         * 超过 1000ms 后按 500ms 粗调，最长 10000ms。
         */
        val INTERVAL_LADDER = listOf(
            1L, 2L, 5L, 10L, 15L, 20L, 30L, 40L, 50L, 75L,
            100L, 150L, 200L, 300L, 500L, 750L, 1_000L,
            1_500L, 2_000L, 3_000L, 5_000L, 10_000L,
        )

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
