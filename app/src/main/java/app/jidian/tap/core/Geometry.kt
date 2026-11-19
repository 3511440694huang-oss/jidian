package app.jidian.tap.core

import java.util.Random
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 落点几何：全部是纯函数，不依赖 Android 类，可直接在 JVM 单测里跑。
 *
 * 坐标系与 [ClickConfig] 一致：屏幕绝对像素，原点在整块显示区域左上角，
 * 与 AccessibilityService.dispatchGesture 使用的坐标系相同。
 *
 * 随机数一律由调用方传入 [Random]（引擎热路径复用一个实例），保证结果可复现、可单测。
 */
object Geometry {

    /** 屏幕上的一个浮点坐标点。 */
    data class Point(val x: Float, val y: Float)

    private const val TWO_PI = 6.2831855f

    /**
     * 计算区域中心。
     *
     * 未框选（regionCx / regionCy < 0）时退化为当前屏幕中心；
     * 已配置时把中心收敛到屏幕内，避免换设备或转屏后被历史坐标带到屏幕外。
     *
     * [w] / [h] 是区域尺寸，目前不参与中心计算（区域的半宽半高由 [samplePoint] 使用），
     * 保留在签名里让「区域」的完整描述一次传入，后续扩展不用改调用点。
     */
    fun resolveRegion(
        regionCx: Int,
        regionCy: Int,
        w: Int,
        h: Int,
        screenW: Int,
        screenH: Int,
    ): Point {
        if (regionCx < 0 || regionCy < 0) {
            return Point(screenW / 2f, screenH / 2f)
        }
        return clampToScreen(Point(regionCx.toFloat(), regionCy.toFloat()), screenW, screenH)
    }

    /**
     * 在配置的落点区域内均匀采样一个点，再叠加随机偏移，最后收敛到屏幕内。
     *
     * - [RegionShape.CIRCLE] 按椭圆处理：悬浮窗框选画出来的就是椭圆，regionW == regionH 时即正圆；
     *   半径要用 sqrt 采样才是面积均匀，直接线性采样会让点向圆心聚集。
     * - [RegionShape.RECT] 在宽、高范围内均匀采样。
     * - 未框选时从屏幕中心起步，随机偏移照常生效。
     */
    fun samplePoint(cfg: ClickConfig, screenW: Int, screenH: Int, rng: Random): Point {
        val w = screenW.coerceAtLeast(2)
        val h = screenH.coerceAtLeast(2)
        val center = resolveRegion(cfg.regionCx, cfg.regionCy, cfg.regionW, cfg.regionH, w, h)
        var x = center.x
        var y = center.y
        if (cfg.regionConfigured) {
            val halfW = cfg.regionW.coerceAtLeast(1) * 0.5f
            val halfH = cfg.regionH.coerceAtLeast(1) * 0.5f
            when (cfg.regionShape) {
                RegionShape.CIRCLE -> {
                    // sqrt 均匀：圆/椭圆内每个面积元被选中的概率相同
                    val radius = sqrt(rng.nextFloat())
                    val angle = rng.nextFloat() * TWO_PI
                    x = center.x + halfW * radius * cos(angle)
                    y = center.y + halfH * radius * sin(angle)
                }

                RegionShape.RECT -> {
                    x = center.x + halfW * (rng.nextFloat() * 2f - 1f)
                    y = center.y + halfH * (rng.nextFloat() * 2f - 1f)
                }
            }
        }
        val offsetPx = cfg.randomOffsetPx
        if (offsetPx > 0) {
            // 随机偏移同样限制在半径 offsetPx 的圆内（均匀采样）
            val radius = offsetPx * sqrt(rng.nextFloat())
            val angle = rng.nextFloat() * TWO_PI
            x += radius * cos(angle)
            y += radius * sin(angle)
        }
        return clampToScreen(Point(x, y), w, h)
    }

    /**
     * 下一次点击的间隔：baseMs 上下均匀抖动 ±jitterPct%。
     *
     * 返回值至少 [ClickConfig.MIN_INTERVAL_MS]，永远不会是 0 或负数，避免引擎空转成死循环。
     * 用 Double 计算再取整，防止整数除法把上界截掉。
     */
    fun nextIntervalMs(baseMs: Long, jitterPct: Int, rng: Random): Long {
        val base = baseMs.coerceAtLeast(ClickConfig.MIN_INTERVAL_MS)
        val pct = jitterPct.coerceIn(0, ClickConfig.MAX_JITTER_PCT)
        if (pct == 0) return base
        val span = base.toDouble() * pct / 100.0
        val delta = (rng.nextDouble() * 2.0 - 1.0) * span
        return (base + delta).toLong().coerceAtLeast(ClickConfig.MIN_INTERVAL_MS)
    }

    /** 把任意坐标收敛到屏幕内；右下边界取 width-1 / height-1，保证是合法触摸点。 */
    fun clampToScreen(p: Point, screenW: Int, screenH: Int): Point {
        val maxX = (screenW - 1).coerceAtLeast(0).toFloat()
        val maxY = (screenH - 1).coerceAtLeast(0).toFloat()
        return Point(p.x.coerceIn(0f, maxX), p.y.coerceIn(0f, maxY))
    }

    /**
     * 合并连点的批次规划：返回一批点击相对第一个点击的起始时刻（毫秒）。
     *
     * AccessibilityService 的 `dispatchGesture` 一次往返要 5–7ms，1ms 间隔下单点命中率
     * 只有约 150 次/秒；把 N 次点击塞进同一个 GestureDescription 的 N 个 Stroke，可以把
     * 往返开销摊薄到每次点击上。`getMaxStrokeCount()` 只允许 10 个 Stroke，同时批次的
     * 总跨度必须够短——用户按停止时不能还残留一长串没放完的点击，所以限制在
     * [BATCH_MAX_SPAN_MS] 内（1ms 档为 10 次/10ms，20ms 档为 3 次/40ms）。
     *
     * 节奏抖动在批次内按 [nextIntervalMs] 逐段累计；某一段会让总跨度超出上限时停止扩批。
     * 返回值至少一个元素（0 表示不合并）。
     */
    fun planBatchStarts(
        intervalMs: Long,
        jitterPct: Int,
        maxStrokes: Int,
        rng: Random,
    ): List<Long> {
        val max = maxStrokes.coerceAtLeast(1)
        if (max == 1) return listOf(0L)
        val base = intervalMs.coerceAtLeast(ClickConfig.MIN_INTERVAL_MS)
        val starts = ArrayList<Long>(max)
        starts.add(0L)
        var at = 0L
        while (starts.size < max) {
            val step = nextIntervalMs(base, jitterPct, rng)
            if (at + step > BATCH_MAX_SPAN_MS) break
            at += step
            starts.add(at)
        }
        return starts
    }

    /** 合并批次允许的最大总跨度（毫秒）。 */
    const val BATCH_MAX_SPAN_MS = 48L

    /** 滑动终点：起点 + 位移，并收敛到屏幕内（贴边时不会滑出屏幕）。 */
    fun swipeEnd(start: Point, dx: Int, dy: Int, screenW: Int, screenH: Int): Point =
        clampToScreen(Point(start.x + dx, start.y + dy), screenW, screenH)
}
