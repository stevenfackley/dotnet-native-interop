import SwiftUI

/// The Analysis section's "Latency" segment: a hub of focused latency analyses + the live
/// engine-telemetry screen (IA collapse spec, 2026-06-21 — was the standalone "Latency" tab).
struct LatencyHubView: View {
    @ObservedObject var model: LatencyViewModel
    let infos: [TransportInfo]
    let telemetry: TelemetryService

    var body: some View {
        NavigationStack {
            List {
                Section("Measure") {
                    NavigationLink { DistributionView(model: model) } label: {
                        Label("Distribution", systemImage: "chart.bar")
                    }
                    NavigationLink { TransportComparisonView(model: model) } label: {
                        Label("Transport comparison", systemImage: "chart.line.uptrend.xyaxis")
                    }
                    NavigationLink { JitterView(model: model) } label: {
                        Label("Jitter over time", systemImage: "waveform.path.ecg")
                    }
                    NavigationLink { PayloadScalingView(model: model) } label: {
                        Label("Payload scaling", systemImage: "arrow.up.right.square")
                    }
                }
                .instrumentRow()
                Section("Runtime") {
                    NavigationLink { TelemetryView(service: telemetry, model: model) } label: {
                        Label("Engine telemetry", systemImage: "gauge.with.dots.needle.67percent")
                    }
                }
                .instrumentRow()
            }
            .instrumentScreen()
            .navigationTitle("Latency")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    // IA collapse spec: About is reachable from Analysis as well as Boundary.
                    AboutToolbarButton(infos: infos, telemetry: telemetry)
                }
            }
        }
    }
}
