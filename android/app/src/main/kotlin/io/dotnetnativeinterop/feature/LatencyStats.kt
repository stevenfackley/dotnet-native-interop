package io.dotnetnativeinterop.feature

/** One latency histogram bucket: its midpoint (ms) and how many samples fell in it.
 *  Mirrors the iOS `LatencyBin` (Shared/Latency/DistributionView.swift). */
public data class LatencyBin(val midpoint: Double, val count: Int)

/**
 * Pure helpers for latency analysis: percentiles, throughput, CDF points, and histogram binning.
 * Byte-for-byte mirror of the iOS `LatencyStats` enum (Shared/Latency/LatencyStats.swift) plus
 * `LatencyBin.make`. No Android dependencies, so it is exercised directly by JVM unit tests.
 */
public object LatencyStats {

    /** Percentile of an already-sorted ascending series (0 for an empty series). */
    public fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val index = minOf(sorted.size - 1, (p * sorted.size).toInt())
        return sorted[index]
    }

    public data class Summary(
        val p50: Double,
        val p95: Double,
        val p99: Double,
        val max: Double,
        /** calls/sec, derived from the summed client round-trip time. */
        val throughput: Double,
    )

    public fun summary(samples: List<Double>): Summary {
        val sorted = samples.sorted()
        val totalMs = samples.sum()
        val throughput = if (totalMs > 0.0) samples.size / (totalMs / 1000.0) else 0.0
        return Summary(
            p50 = percentile(sorted, 0.50),
            p95 = percentile(sorted, 0.95),
            p99 = percentile(sorted, 0.99),
            max = sorted.lastOrNull() ?: 0.0,
            throughput = throughput,
        )
    }

    /** Cumulative-distribution point: a value (ms) and the fraction of samples at or below it. */
    public data class CdfPoint(val value: Double, val fraction: Double)

    /** Cumulative-distribution points for overlaying distributions. */
    public fun cdf(samples: List<Double>): List<CdfPoint> {
        val sorted = samples.sorted()
        val n = sorted.size
        if (n == 0) return emptyList()
        return sorted.mapIndexed { i, value -> CdfPoint(value, (i + 1).toDouble() / n) }
    }

    /** Buckets [samples] into [bucketCount] equal-width bins for a histogram. */
    public fun bins(samples: List<Double>, bucketCount: Int): List<LatencyBin> {
        if (samples.isEmpty() || bucketCount < 1) return emptyList()
        val low = samples.min()
        val high = samples.max()
        if (high <= low) return listOf(LatencyBin(low, samples.size))
        val width = (high - low) / bucketCount
        val counts = IntArray(bucketCount)
        for (sample in samples) {
            val index = minOf(bucketCount - 1, ((sample - low) / width).toInt())
            counts[index]++
        }
        return counts.mapIndexed { index, count ->
            LatencyBin(midpoint = low + ((index + 0.5) * width), count = count)
        }
    }
}
