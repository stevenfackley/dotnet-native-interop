package io.dotnetnativeinterop.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic tests for the latency helpers; pins parity with the iOS LatencyStats indexing. */
public class LatencyStatsTest {

    private val oneToHundred: List<Double> = (1..100).map { it.toDouble() }

    @Test
    public fun percentileMatchesIosIndexing() {
        // iOS: sorted[min(count-1, (p*count).toInt())]
        assertEquals(51.0, LatencyStats.percentile(oneToHundred, 0.50), 1e-9)
        assertEquals(96.0, LatencyStats.percentile(oneToHundred, 0.95), 1e-9)
        assertEquals(100.0, LatencyStats.percentile(oneToHundred, 0.99), 1e-9)
        assertEquals(100.0, LatencyStats.percentile(oneToHundred, 1.0), 1e-9) // clamped to count-1
    }

    @Test
    public fun percentileEmptyIsZero() {
        assertEquals(0.0, LatencyStats.percentile(emptyList(), 0.5), 0.0)
    }

    @Test
    public fun summaryThroughputAndMax() {
        // 4 calls totalling 8 ms -> 4 / 0.008 s = 500 calls/sec.
        val s = LatencyStats.summary(listOf(2.0, 2.0, 2.0, 2.0))
        assertEquals(2.0, s.max, 1e-9)
        assertEquals(500.0, s.throughput, 1e-6)
    }

    @Test
    public fun summaryEmptyHasZeroThroughput() {
        val s = LatencyStats.summary(emptyList())
        assertEquals(0.0, s.throughput, 0.0)
        assertEquals(0.0, s.max, 0.0)
    }

    @Test
    public fun cdfSortsAscendingAndEndsAtOne() {
        val pts = LatencyStats.cdf(listOf(3.0, 1.0, 2.0))
        assertEquals(3, pts.size)
        assertEquals(1.0, pts[0].value, 1e-9)
        assertEquals(1.0 / 3.0, pts[0].fraction, 1e-9)
        assertEquals(1.0, pts.last().fraction, 1e-9)
    }

    @Test
    public fun binsCountEverySample() {
        val samples = listOf(0.0, 1.0, 2.0, 3.0, 4.0)
        val bins = LatencyStats.bins(samples, 4)
        assertEquals(4, bins.size)
        assertEquals(samples.size, bins.sumOf { it.count })
    }

    @Test
    public fun binsDegenerateWhenAllEqual() {
        val bins = LatencyStats.bins(listOf(5.0, 5.0, 5.0), 24)
        assertEquals(1, bins.size)
        assertEquals(3, bins[0].count)
        assertEquals(5.0, bins[0].midpoint, 1e-9)
    }

    @Test
    public fun binsEmptyForNoSamples() {
        assertTrue(LatencyStats.bins(emptyList(), 24).isEmpty())
    }

    @Test
    public fun formatLatencyUsesMicrosecondsBelowOneMillisecond() {
        assertEquals("500.0 µs", LatencyStats.formatLatencyMs(0.5))
        assertEquals("999.0 µs", LatencyStats.formatLatencyMs(0.999))
    }

    @Test
    public fun formatLatencyUsesMillisecondsAtOrAboveOneMillisecond() {
        assertEquals("1.00 ms", LatencyStats.formatLatencyMs(1.0))
        assertEquals("12.34 ms", LatencyStats.formatLatencyMs(12.34))
    }

    @Test
    public fun outlierIndicesEmptyBelowFiveSamples() {
        assertTrue(LatencyStats.outlierIndices(listOf(1.0, 2.0)).isEmpty())
    }

    @Test
    public fun outlierIndicesFindsOnlyTheValueAboveThreshold() {
        // Nine 1.0s, one 2.0, one 3.0 (11 samples) -> p90 lands on the 2.0; only the 3.0 exceeds it.
        val samples = listOf(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 2.0, 3.0)
        assertEquals(listOf(10), LatencyStats.outlierIndices(samples, 0.90))
    }

    @Test
    public fun collectionEventXsFindsBigDropAfterPeak() {
        // Ramps 10 -> 100 -> 10: a GC-like give-back at x=2.0.
        val series = listOf(0.0 to 10.0, 1.0 to 100.0, 2.0 to 10.0)
        assertEquals(listOf(2.0), LatencyStats.collectionEventXs(series))
    }

    @Test
    public fun collectionEventXsEmptyForSmoothlyGrowingSeries() {
        val series = listOf(0.0 to 10.0, 1.0 to 11.0, 2.0 to 12.0)
        assertTrue(LatencyStats.collectionEventXs(series).isEmpty())
    }
}
