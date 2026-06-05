import Charts
import SwiftUI

/// Watch the NativeAOT runtime move: a live telemetry strip + managed-heap chart that update on a timer
/// while an optional "stress" loop hammers the Phase 1 benchmarks (which allocate), driving GC.
struct TelemetryView: View {
    @ObservedObject var model: LatencyViewModel
    @StateObject private var poller: TelemetryPoller
    @State private var stressing = false

    init(service: TelemetryService, model: LatencyViewModel) {
        _model = ObservedObject(wrappedValue: model)
        _poller = StateObject(wrappedValue: TelemetryPoller(service: service))
    }

    var body: some View {
        List {
            Section {
                Toggle("Run stress (loop benchmarks)", isOn: $stressing)
                Text("Stress loops `bench-parallel` over FFI to allocate and churn the GC. Watch heap, "
                     + "GC counts, and pause time move live.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Section("Live runtime") {
                TelemetryStrip(stats: poller.stats)
                if let error = poller.errorMessage {
                    Text(error).font(.caption).foregroundStyle(.red)
                }
            }
            Section("Managed heap (MB)") {
                Chart {
                    ForEach(Array(poller.heapHistory.enumerated()), id: \.offset) { index, mb in
                        LineMark(x: .value("t", index), y: .value("MB", mb))
                            .foregroundStyle(.green)
                    }
                }
                .chartYAxisLabel("heap MB")
                .frame(height: 200)
            }
        }
        .navigationTitle("Engine telemetry")
        .task { await poller.loop() }
        .task(id: stressing) { await stressLoop() }
    }

    private func stressLoop() async {
        while stressing && !Task.isCancelled {
            _ = await model.roundTripMs("bench-parallel~size_320", on: .ffi)
        }
    }
}
