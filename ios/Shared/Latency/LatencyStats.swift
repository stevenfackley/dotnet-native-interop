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
}
