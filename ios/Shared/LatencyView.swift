import Charts
import SwiftUI

/// Live latency distribution: fires the no-op `ping` feature many times over the active transport and
/// charts the client-side round-trip histogram with Swift Charts. The engine work is trivial, so the
/// spread is essentially pure transport cost — reinforcing the project's thesis visually.
struct LatencyView: View {
    @ObservedObject var viewModel: FeaturesViewModel

    @State private var samples: [Double] = []
    @State private var running = false
    private let sampleCount = 300

    var body: some View {
        NavigationStack {
            List {
                Section {
                    TransportPicker(viewModel: viewModel)
                    Button {
                        Task { await measure() }
                    } label: {
                        if running {
                            HStack(spacing: 8) {
                                ProgressView()
                                Text("Measuring \(sampleCount) calls…")
                            }
                        } else {
                            Label("Measure \(sampleCount) pings", systemImage: "stopwatch")
                        }
                    }
                    .disabled(running)
                    Text("Each call round-trips a no-op `ping` over \(viewModel.transport.displayName). "
                         + "The histogram is the client-side latency distribution — pure transport cost.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                if samples.isEmpty {
                    Section {
                        ContentUnavailableView(
                            "No samples yet",
                            systemImage: "chart.bar",
                            description: Text("Tap Measure to fire \(sampleCount) pings over the active transport."))
                    }
                } else {
                    Section("Distribution — \(samples.count) calls") {
                        Chart(bins) { bin in
                            BarMark(
                                x: .value("round-trip (ms)", bin.midpoint),
                                y: .value("calls", bin.count))
                            .foregroundStyle(ComparisonView.color(viewModel.selected))
                        }
                        .chartXAxisLabel("round-trip (ms)")
                        .chartYAxisLabel("calls")
                        .frame(height: 220)
                    }

                    Section("Stats (ms)") {
                        LabeledContent("min") { monospaced(stats.min) }
                        LabeledContent("median") { monospaced(stats.median) }
                        LabeledContent("p95") { monospaced(stats.p95) }
                        LabeledContent("max") { monospaced(stats.max) }
                    }
                }
            }
            .navigationTitle("Latency")
        }
    }

    private func monospaced(_ value: Double) -> some View {
        Text(String(format: "%.3f", value)).monospacedDigit()
    }

    @MainActor
    private func measure() async {
        running = true
        defer { running = false }
        samples = await viewModel.pingLatencies(count: sampleCount)
    }

    private var bins: [LatencyBin] { LatencyBin.make(from: samples, bucketCount: 24) }

    private var stats: (min: Double, median: Double, p95: Double, max: Double) {
        let sorted = samples.sorted()
        guard let first = sorted.first, let last = sorted.last else { return (0, 0, 0, 0) }
        func percentile(_ p: Double) -> Double {
            sorted[min(sorted.count - 1, Int(p * Double(sorted.count)))]
        }
        return (first, percentile(0.5), percentile(0.95), last)
    }
}

/// One histogram bucket: its midpoint (ms) and how many samples fell in it.
struct LatencyBin: Identifiable {
    let id = UUID()
    let midpoint: Double
    let count: Int

    /// Buckets `samples` into `bucketCount` equal-width bins across the observed [min, max] range.
    static func make(from samples: [Double], bucketCount: Int) -> [LatencyBin] {
        guard let low = samples.min(), let high = samples.max() else { return [] }
        guard high > low else { return [LatencyBin(midpoint: low, count: samples.count)] }

        let width = (high - low) / Double(bucketCount)
        var counts = [Int](repeating: 0, count: bucketCount)
        for sample in samples {
            let index = min(bucketCount - 1, Int((sample - low) / width))
            counts[index] += 1
        }
        return counts.enumerated().map { index, count in
            LatencyBin(midpoint: low + ((Double(index) + 0.5) * width), count: count)
        }
    }
}
