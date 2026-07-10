import Charts
import SwiftUI

/// Latency vs call index over the selected transport — shows cold→warm steady state and GC jitter
/// (NativeAOT has no JIT warmup, so the line is flat from the first call apart from GC blips).
struct JitterView: View {
    @ObservedObject var model: LatencyViewModel

    @State private var series = LatencyViewModel.Series()
    @State private var running = false
    private let count = 400

    private var samples: [Double] { series.samples }

    var body: some View {
        List {
            Section {
                LabTransportPicker(transport: $model.transport)
                Button {
                    Task { await run() }
                } label: {
                    if running {
                        HStack(spacing: 8) { ProgressView(); Text("Sampling…") }
                    } else {
                        Label("Sample \(count) sequential pings", systemImage: "waveform.path.ecg")
                    }
                }
                .disabled(running)
                Text("Each point is one `ping` round-trip in order over \(model.transport.displayName).")
                    .font(.caption).foregroundStyle(Instrument.textSecondary)
            }
            .instrumentRow()

            if series.failures > 0 {
                Section {
                    ErrorBanner(message: samples.isEmpty
                        ? "All \(series.failures) pings failed over \(model.transport.displayName)."
                        : "\(series.failures) of \(count) pings failed — gaps are excluded from the line.")
                        .listRowInsets(EdgeInsets())
                }
                .listRowBackground(Color.clear)
            }

            if !samples.isEmpty {
                // Annotate the tail (facelift spec §5); cap the markers so the chart doesn't drown in ticks.
                let allOutliers = LatencyStats.outlierIndices(samples)
                let outliers = Array(allOutliers.prefix(6))
                Section("Latency over call index (ms)") {
                    Chart {
                        ForEach(Array(samples.enumerated()), id: \.offset) { index, ms in
                            LineMark(x: .value("call #", index), y: .value("ms", ms))
                                .foregroundStyle(Instrument.transport(model.transport))
                        }
                        ForEach(Array(outliers.enumerated()), id: \.element) { idx, i in
                            PointMark(x: .value("call #", i), y: .value("ms", samples[i]))
                                .foregroundStyle(Instrument.warn)
                                .symbolSize(36)
                                .annotation(position: .top) {
                                    if idx == 0 {
                                        Text("P99+").font(.system(size: 9)).foregroundStyle(Instrument.warn)
                                    }
                                }
                        }
                    }
                    .chartXAxisLabel("call #")
                    .chartYAxisLabel("round-trip (ms)")
                    .frame(height: 240)
                    if !allOutliers.isEmpty {
                        let note = allOutliers.count > outliers.count ? " (first \(outliers.count) marked)" : ""
                        Text("\(allOutliers.count) call(s) above p99\(note) — GC blips or cold-start spikes.")
                            .font(.caption).foregroundStyle(Instrument.textSecondary)
                    }
                }
                .instrumentRow()
            }
        }
        .instrumentScreen()
        .navigationTitle("Jitter over time")
    }

    @MainActor
    private func run() async {
        running = true
        defer { running = false }
        series = await model.pingSeries(count: count, on: model.transport)
    }
}
