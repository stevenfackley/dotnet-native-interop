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
                Section("Latency over call index (ms)") {
                    Chart {
                        ForEach(Array(samples.enumerated()), id: \.offset) { index, ms in
                            LineMark(x: .value("call #", index), y: .value("ms", ms))
                                .foregroundStyle(Instrument.transport(model.transport))
                        }
                    }
                    .chartXAxisLabel("call #")
                    .chartYAxisLabel("round-trip (ms)")
                    .frame(height: 240)
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
