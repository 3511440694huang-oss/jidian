package app.jidian.tap

import app.jidian.tap.core.ClickConfig
import app.jidian.tap.core.Geometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * 节奏配置单测：极限速度区间、护栏收敛、阶梯/预设的单调性、抖动不会越界。
 */
class ClickConfigTest {

    @Test
    fun intervalIsClampedToExtremeRange() {
        val tooFast = ClickConfig.Default.copy(intervalMs = 0L).safe()
        assertEquals(ClickConfig.MIN_INTERVAL_MS, tooFast.intervalMs)

        val negative = ClickConfig.Default.copy(intervalMs = -5L).safe()
        assertEquals(ClickConfig.MIN_INTERVAL_MS, negative.intervalMs)

        val tooSlow = ClickConfig.Default.copy(intervalMs = 99_999L).safe()
        assertEquals(ClickConfig.MAX_INTERVAL_MS, tooSlow.intervalMs)
    }

    @Test
    fun oneMillisecondIsAcceptedAndMeansThousandCps() {
        val config = ClickConfig.Default.copy(intervalMs = 1L).safe()
        assertEquals(1L, config.intervalMs)
        assertEquals(1_000f, config.targetCps, 0.001f)
        assertTrue(config.ultraFast)
    }

    @Test
    fun ultraFastOnlyAppliesToShortIntervals() {
        assertTrue(ClickConfig.Default.copy(intervalMs = 1L).ultraFast)
        assertTrue(ClickConfig.Default.copy(intervalMs = ClickConfig.ULTRA_FAST_THRESHOLD_MS).ultraFast)
        assertFalse(ClickConfig.Default.copy(intervalMs = ClickConfig.ULTRA_FAST_THRESHOLD_MS + 1L).ultraFast)
        assertFalse(ClickConfig.Default.copy(intervalMs = 120L).ultraFast)
    }

    @Test
    fun presetsAndLadderAreMonotonicAndStartAtMinimum() {
        assertEquals(ClickConfig.MIN_INTERVAL_MS, ClickConfig.INTERVAL_PRESETS.first())
        assertEquals(ClickConfig.MIN_INTERVAL_MS, ClickConfig.INTERVAL_LADDER.first())
        assertEquals(ClickConfig.MAX_INTERVAL_MS, ClickConfig.INTERVAL_LADDER.last())
        ClickConfig.INTERVAL_PRESETS.zipWithNext().forEach { (a, b) ->
            assertTrue("预设必须严格递增：$a -> $b", b > a)
        }
        ClickConfig.INTERVAL_LADDER.zipWithNext().forEach { (a, b) ->
            assertTrue("阶梯必须严格递增：$a -> $b", b > a)
        }
    }

    @Test
    fun jitterNeverDropsBelowOneMillisecond() {
        val rng = Random(20261119)
        repeat(20_000) {
            val next = Geometry.nextIntervalMs(1L, 80, rng)
            assertTrue("抖动后不得小于下限：$next", next >= ClickConfig.MIN_INTERVAL_MS)
        }
    }

    @Test
    fun batchAndHandleSwitchesSurviveSanitize() {
        val defaults = ClickConfig.Default.safe()
        assertTrue(defaults.batchTaps)
        assertTrue(defaults.showRegionHandle)

        val off = ClickConfig.Default.copy(batchTaps = false, showRegionHandle = false).safe()
        assertFalse(off.batchTaps)
        assertFalse(off.showRegionHandle)
    }
}
