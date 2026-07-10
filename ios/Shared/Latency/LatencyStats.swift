import Foundation

/// Pure helpers for latency analysis: percentiles, throughput, and CDF points for distribution overlays.
enum LatencyStats {
    static func percentile(_ sorted: [Double], _ p: Double) -> Double {
        guard !sorted.isEmpty else { return 0 }
        return sorted[min(sorted.count - 1, Int(p * Double(sorted.count)))]
    }

    struct Summary {
        let p50: Double
        let p95: Double
        let p99: Double
        let max: Double
        let throughput: Double   // calls/sec
    }

    static func summary(_ samples: [Double]) -> Summary {
        let sorted = samples.sorted()
        let totalMs = samples.reduce(0, +)
        let throughput = totalMs > 0 ? Double(samples.count) / (totalMs / 1000) : 0
        return Summary(
            p50: percentile(sorted, 0.50),
            p95: percentile(sorted, 0.95),
            p99: percentile(sorted, 0.99),
            max: sorted.last ?? 0,
            throughput: throughput)
    }

    /// Cumulative-distribution points (value, fraction in [0,1]) for overlaying distributions.
    static func cdf(_ samples: [Double]) -> [(value: Double, fraction: Double)] {
        let sorted = samples.sorted()
        let n = Double(sorted.count)
        guard n > 0 else { return [] }
        return sorted.enumerated().map { (value: $0.element, fraction: Double($0.offset + 1) / n) }
    }

    /// "Commit to one unit" (facelift spec §5): sub-millisecond values read as µs — never a bare
    /// `0.11 ms` — so FFI's magnitude stays legible next to HTTP/SQLite. At or above 1 ms, plain ms with
    /// 2 decimals. One value, one unit; never both in the same string. `String(format:)` uses the C
    /// locale (period decimal) regardless of the device locale — the en-US pin Android needs is implicit
    /// here, so a comma-decimal device can't print "1,00 ms" (parity break). Mirrors Android `formatLatencyMs`.
    static func formatLatencyMs(_ ms: Double) -> String {
        ms < 1.0
            ? String(format: "%.1f µs", ms * 1_000.0)
            : String(format: "%.2f ms", ms)
    }

    /// Indices of `samples` (in original order) whose value exceeds the `p`th percentile — the tail worth
    /// annotating on a jitter/sequence chart (facelift spec §5). Empty below 5 samples. NOTE: `percentile`
    /// is nearest-rank (`sorted[Int(p*n)]`), so at the default p=0.99 the threshold IS the max for any
    /// n ≤ 100 (index n-1) — zero outliers for small n. A non-empty result needs n well above 100 (the
    /// real caller, JitterView, uses 400). Mirrors Android `outlierIndices`.
    static func outlierIndices(_ samples: [Double], p: Double = 0.99) -> [Int] {
        guard samples.count >= 5 else { return [] }
        let threshold = percentile(samples.sorted(), p)
        return samples.indices.filter { samples[$0] > threshold }
    }

    /// X-positions where a (x, y) series drops by more than `dropFraction` of its own peak from the
    /// previous point — a GC collection give-back on a heap-over-time series (facelift spec §5). Pure, so
    /// the detector is unit-tested; the UI supplies (index-or-seconds, MB) pairs. Mirrors Android
    /// `collectionEventXs`.
    static func collectionEventXs(_ points: [(x: Double, y: Double)], dropFraction: Double = 0.10) -> [Double] {
        guard points.count >= 2 else { return [] }
        let peak = max(points.map(\.y).max() ?? 0, 1e-9)
        let threshold = peak * dropFraction
        return (1..<points.count)
            .filter { points[$0 - 1].y - points[$0].y > threshold }
            .map { points[$0].x }
    }
}
