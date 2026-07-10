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
                    .font(.caption).foregroundStyle(Instrument.textSecondary)
            }
            .instrumentRow()
            if let error = poller.errorMessage {
                Section {
                    ErrorBanner(message: error)
                        .listRowInsets(EdgeInsets())
                }
                .listRowBackground(Color.clear)
            }
            Section("Live runtime") {
                TelemetryStrip(stats: poller.stats)
            }
            .instrumentRow()
            Section("Managed heap / committed (MB)") {
                // Heap is the story (primary = textPrimary); committed is supporting context (muted =
                // textTertiary), NOT accent — the facelift reserves accent for interaction/in-flight,
                // not decoration. Colors are name-mapped, so heap stays primary regardless of draw order.
                let heapPoints = poller.heapHistory.enumerated().map { (x: Double($0.offset), y: $0.element) }
                let gcEvents = Array(LatencyStats.collectionEventXs(heapPoints).prefix(10))
                Chart {
                    ForEach(Array(poller.heapHistory.enumerated()), id: \.offset) { index, mb in
                        LineMark(x: .value("t", index), y: .value("MB", mb))
                            .foregroundStyle(by: .value("series", "heap"))
                    }
                    ForEach(Array(poller.committedHistory.enumerated()), id: \.offset) { index, mb in
                        LineMark(x: .value("t", index), y: .value("MB", mb))
                            .foregroundStyle(by: .value("series", "committed"))
                    }
                    // GC give-backs detected from the heap series (facelift spec §5): a point-to-point
                    // heap drop > 10% of peak → dashed warn "GC" (only the first is labelled).
                    ForEach(Array(gcEvents.enumerated()), id: \.offset) { idx, x in
                        RuleMark(x: .value("t", x))
                            .foregroundStyle(Instrument.warn)
                            .lineStyle(StrokeStyle(lineWidth: 1.5, dash: [6, 5]))
                            .annotation(position: .top, alignment: .leading) {
                                if idx == 0 {
                                    Text("GC").font(.system(size: 9)).foregroundStyle(Instrument.warn)
                                }
                            }
                    }
                }
                .chartForegroundStyleScale(
                    domain: ["heap", "committed"],
                    range: [Instrument.textPrimary, Instrument.textTertiary]
                )
                .chartYAxisLabel("MB")
                .chartLegend(position: .bottom)
                .frame(height: 240)
            }
            .instrumentRow()
        }
        .instrumentScreen()
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
