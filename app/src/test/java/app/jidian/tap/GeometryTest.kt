package app.jidian.tap

import app.jidian.tap.core.ClickConfig
import app.jidian.tap.core.Geometry
import app.jidian.tap.core.RegionShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.hypot

/**
 * Geometry 纯函数单测：区域采样范围、随机偏移、抖动区间、屏幕越界收敛。
 * 随机数使用固定种子，结果可复现。
 */
class GeometryTest {

    private fun makeConfig(
        shape: RegionShape = RegionShape.CIRCLE,
        cx: Int = -1,
        cy: Int = -1,
        w: Int = 240,
        h: Int = 240,
        offset: Int = 0,
        jitter: Int = 0,
    ) = ClickConfig(
        regionShape = shape,
        regionCx = cx,
        regionCy = cy,
        regionW = w,
        regionH = h,
        randomOffsetPx = offset,
        intervalJitterPct = jitter,
    )

    @Test
    fun unconfiguredRegionFallsBackToScreenCenter() {
        val config = makeConfig()
        val center = Geometry.resolveRegion(
            config.regionCx,
            config.regionCy,
            config.regionW,
            config.regionH,
            1080,
            2400,
        )
        assertEquals(540f, center.x, 0.001f)
        assertEquals(1200f, center.y, 0.001f)

        // 随机偏移为 0 时，未框选的落点就是屏幕中心
        val point = Geometry.samplePoint(config, 1080, 2400, Random(7))
        assertEquals(540f, point.x, 0.001f)
        assertEquals(1200f, point.y, 0.001f)
    }

    @Test
    fun configuredRegionKeepsItsCenter() {
        val point = Geometry.resolveRegion(300, 500, 240, 120, 1080, 2400)
        assertEquals(300f, point.x, 0.001f)
        assertEquals(500f, point.y, 0.001f)
    }

    @Test
    fun offscreenRegionCenterIsClampedIntoScreen() {
        val point = Geometry.resolveRegion(5000, 9000, 240, 240, 1080, 2400)
        assertEquals(1079f, point.x, 0.001f)
        assertEquals(2399f, point.y, 0.001f)
    }

    @Test
    fun circleSamplesStayInsideRegion() {
        val rng = Random(1234)
        val config = makeConfig(RegionShape.CIRCLE, 540, 1200, 400, 400)
        repeat(2000) {
            val point = Geometry.samplePoint(config, 1080, 2400, rng)
            val dx = (point.x - 540f) / 200f
            val dy = (point.y - 1200f) / 200f
            assertTrue(
                "圆形区域采样越界: (${point.x}, ${point.y})",
                dx * dx + dy * dy <= 1.0001f,
            )
        }
    }

    @Test
    fun ellipseSamplesStayInsideConfiguredRegion() {
        val rng = Random(99)
        // 悬浮窗框选画的是椭圆：regionW != regionH 时按椭圆半轴采样
        val config = makeConfig(RegionShape.CIRCLE, 540, 1200, 600, 200)
        repeat(2000) {
            val point = Geometry.samplePoint(config, 1080, 2400, rng)
            val dx = (point.x - 540f) / 300f
            val dy = (point.y - 1200f) / 100f
            assertTrue(
                "椭圆区域采样越界: (${point.x}, ${point.y})",
                dx * dx + dy * dy <= 1.0001f,
            )
        }
    }

    @Test
    fun rectSamplesStayInsideRegion() {
        val rng = Random(2024)
        val config = makeConfig(RegionShape.RECT, 540, 1200, 400, 200)
        repeat(2000) {
            val point = Geometry.samplePoint(config, 1080, 2400, rng)
            assertTrue(point.x >= 340f - 0.001f && point.x <= 740f + 0.001f)
            assertTrue(point.y >= 1100f - 0.001f && point.y <= 1300f + 0.001f)
        }
    }

    @Test
    fun randomOffsetStaysWithinRadiusWhenInsideScreen() {
        val rng = Random(31)
        val config = makeConfig(offset = 40)
        repeat(1000) {
            val point = Geometry.samplePoint(config, 1080, 2400, rng)
            val distance = hypot((point.x - 540f).toDouble(), (point.y - 1200f).toDouble())
            assertTrue("随机偏移超出半径: (${point.x}, ${point.y})", distance <= 40.0 + 0.01)
        }
    }

    @Test
    fun pointsNearScreenEdgeAreClampedIntoScreen() {
        val rng = Random(5)
        // 区域中心贴在左上角、区域半径 200、随机偏移 120：大量采样必然越过屏幕边界
        val config = makeConfig(RegionShape.CIRCLE, 10, 10, 400, 400, offset = 120)
        repeat(2000) {
            val point = Geometry.samplePoint(config, 1080, 2400, rng)
            assertTrue("落点越过左边界: ${point.x}", point.x >= 0f)
            assertTrue("落点越过上边界: ${point.y}", point.y >= 0f)
            assertTrue("落点越过右边界: ${point.x}", point.x <= 1079f)
            assertTrue("落点越过下边界: ${point.y}", point.y <= 2399f)
        }
    }

    @Test
    fun clampToScreenHandlesBothEdges() {
        val low = Geometry.clampToScreen(Geometry.Point(-80f, -60f), 1080, 2400)
        assertEquals(0f, low.x, 0.001f)
        assertEquals(0f, low.y, 0.001f)
        val high = Geometry.clampToScreen(Geometry.Point(5000f, 9000f), 1080, 2400)
        assertEquals(1079f, high.x, 0.001f)
        assertEquals(2399f, high.y, 0.001f)
    }

    @Test
    fun zeroJitterReturnsBaseInterval() {
        val rng = Random(1)
        repeat(200) {
            assertEquals(120L, Geometry.nextIntervalMs(120L, 0, rng))
        }
    }

    @Test
    fun jitterStaysWithinBounds() {
        val rng = Random(77)
        var min = Long.MAX_VALUE
        var max = Long.MIN_VALUE
        repeat(1000) {
            val value = Geometry.nextIntervalMs(1_000L, 20, rng)
            assertTrue("抖动越过下界: $value", value >= 800L)
            assertTrue("抖动越过上界: $value", value <= 1_200L)
            if (value < min) min = value
            if (value > max) max = value
        }
        // 均匀抖动应该覆盖区间两侧，而不是永远偏向一边
        assertTrue("抖动样本没有覆盖下侧: $min", min < 950L)
        assertTrue("抖动样本没有覆盖上侧: $max", max > 1_050L)
    }

    @Test
    fun jitterNeverReturnsNonPositiveInterval() {
        val rng = Random(3)
        repeat(500) {
            val value = Geometry.nextIntervalMs(1L, 80, rng)
            assertTrue("间隔必须为正: $value", value > 0L)
        }
    }

    @Test
    fun swipeEndMatchesOffsetAndClampsToScreen() {
        val start = Geometry.Point(500f, 800f)
        val end = Geometry.swipeEnd(start, 220, 0, 1080, 2400)
        assertEquals(720f, end.x, 0.001f)
        assertEquals(800f, end.y, 0.001f)

        val clamped = Geometry.swipeEnd(Geometry.Point(1000f, 2300f), 500, 500, 1080, 2400)
        assertEquals(1079f, clamped.x, 0.001f)
        assertEquals(2399f, clamped.y, 0.001f)

        // 位移为 0 时终点收敛回起点
        val same = Geometry.swipeEnd(start, 0, 0, 1080, 2400)
        assertEquals(start.x, same.x, 0.001f)
        assertEquals(start.y, same.y, 0.001f)
    }

    @Test
    fun sameSeedProducesSameSample() {
        val config = makeConfig(RegionShape.RECT, 540, 1200, 400, 200, offset = 8)
        val first = Geometry.samplePoint(config, 1080, 2400, Random(4242))
        val second = Geometry.samplePoint(config, 1080, 2400, Random(4242))
        assertEquals(first.x, second.x, 0.0001f)
        assertEquals(first.y, second.y, 0.0001f)
    }

    // —— 合并连点批次规划 ——

    @Test
    fun batchAtOneMillisecondPacksTenStrokes() {
        val starts = Geometry.planBatchStarts(1L, 0, 10, Random(1))
        assertEquals(10, starts.size)
        assertEquals(0L, starts.first())
        assertEquals(9L, starts.last())
    }

    @Test
    fun batchStopsWhenSpanWouldExceedLimit() {
        // 10ms 档一批 5 次（0/10/20/30/40），再下一次会到 50ms 超过 48ms 上限
        val ten = Geometry.planBatchStarts(10L, 0, 10, Random(2))
        assertEquals(listOf(0L, 10L, 20L, 30L, 40L), ten)

        // 16ms 档一批 4 次，最后一个是 48ms（正好在上限上）
        val sixteen = Geometry.planBatchStarts(16L, 0, 10, Random(3))
        assertEquals(listOf(0L, 16L, 32L, 48L), sixteen)

        // 30ms 档一批 2 次
        assertEquals(listOf(0L, 30L), Geometry.planBatchStarts(30L, 0, 10, Random(4)))

        // 慢速不合并：一个批次就是一次点击
        assertEquals(listOf(0L), Geometry.planBatchStarts(50L, 0, 10, Random(5)))
        assertEquals(listOf(0L), Geometry.planBatchStarts(1L, 0, 1, Random(6)))
    }

    @Test
    fun batchWithJitterStaysOrderedAndWithinSpan() {
        val rng = Random(20261119)
        repeat(500) {
            val starts = Geometry.planBatchStarts(7L, 60, 10, rng)
            assertTrue(starts.isNotEmpty())
            assertEquals(0L, starts.first())
            assertTrue(starts.size <= 10)
            assertTrue("批次总跨度必须受限：$starts", starts.last() <= Geometry.BATCH_MAX_SPAN_MS)
            starts.zipWithNext().forEach { (a, b) ->
                assertTrue("批次内起点必须递增且间隔 ≥1ms：$starts", b > a)
            }
        }
    }
}
