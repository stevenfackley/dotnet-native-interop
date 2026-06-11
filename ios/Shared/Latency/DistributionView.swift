import Charts
import SwiftUI

/// Single-transport latency histogram: fires N `ping` round-trips over the selected transport and charts
/// the client-side distribution. (Relocated from the original Latency tab; the engine work is trivial, so
/// the spread is essentially pure transport cost.)
struct DistributionView: View {
    @ObservedObject var model: LatencyViewModel

    @State private var series = LatencyViewModel.Series()
    @State private var running = false
    private let sampleCount = 300

    private var samples: [Double] { series.samples }

    var body: some View {
        List {
            Section {
                LabTransportPicker(transport: $model.transport)
                Button {
                    Task { await measure() }
                } label: {
                    if running {
                        HStack(spacing: 8) { ProgressView(); Text("Measuring \(sampleCount) calls…") }
                    } else {
                        Label("Measure \(sampleCount) pings", systemImage: "stopwatch")
                    }
                }
                .disabled(running)
                Text("Each call round-trips a no-op `ping` over \(model.transport.displayName) — the "
                     + "histogram is pure transport cost.")
                    .font(.caption).foregroundStyle(Instrument.textSecondary)
            }
            .listRowBackground(Instrument.bg1)
            .listRowSeparatorTint(Instrument.hairline)

            if samples.isEmpty {
                Section {
                    if series.failures > 0 {
                        ErrorBanner(message: "All \(series.failures) pings failed over "
                                    + "\(model.transport.displayName) — no samples to chart.") {
                            Task { await measure() }
                        }
                        .listRowInsets(EdgeInsets())
                    } else {
                        ContentUnavailableView("No samples yet", systemImage: "chart.bar",
                                               description: Text("Tap Measure to fire \(sampleCount) pings."))
                    }
                }
                .listRowBackground(Color.clear)
            } else {
                Section(distributionTitle) {
                    Chart(bins) { bin in
                        BarMark(x: .value("round-trip (ms)", bin.midpoint), y: .value("calls", bin.count))
                            .foregroundStyle(Instrument.transport(model.transport))
                    }
                    .chartXAxisLabel("round-trip (ms)")
                    .chartYAxisLabel("calls")
                    .frame(height: 220)
                }
                .listRowBackground(Instrument.bg1)
                .listRowSeparatorTint(Instrument.hairline)
                Section("Stats (ms)") {
                    LabeledContent("min") { mono(samples.min() ?? 0) }
                    LabeledContent("median") { mono(LatencyStats.percentile(samples.sorted(), 0.5)) }
                    LabeledContent("p95") { mono(LatencyStats.percentile(samples.sorted(), 0.95)) }
                    LabeledContent("max") { mono(samples.max() ?? 0) }
                }
                .listRowBackground(Instrument.bg1)
                .listRowSeparatorTint(Instrument.hairline)
            }
        }
        .instrumentScreen()
        .navigationTitle("Distribution")
    }

    private var distributionTitle: String {
        series.failures > 0
            ? "Distribution — \(samples.count)/\(sampleCount) calls · \(series.failures) failed"
            : "Distribution — \(samples.count) calls"
    }

    private func mono(_ value: Double) -> some View {
        Text(String(format: "%.3f", value)).monospacedDigit()
    }

    @MainActor
    private func measure() async {
        running = true
        defer { running = false }
        series = await model.pingSeries(count: sampleCount, on: model.transport)
    }

    private var bins: [LatencyBin] { LatencyBin.make(from: samples, bucketCount: 24) }
}

/// One histogram bucket: its midpoint (ms) and how many samples fell in it.
struct LatencyBin: Identifiable {
    let id = UUID()
    let midpoint: Double
    let count: Int

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
