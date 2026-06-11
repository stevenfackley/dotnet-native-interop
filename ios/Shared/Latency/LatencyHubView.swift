import SwiftUI

/// The Latency tab: a hub of focused latency analyses + the live engine-telemetry screen.
struct LatencyHubView: View {
    @ObservedObject var model: LatencyViewModel
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
                .listRowBackground(Instrument.bg1)
                .listRowSeparatorTint(Instrument.hairline)
                Section("Runtime") {
                    NavigationLink { TelemetryView(service: telemetry, model: model) } label: {
                        Label("Engine telemetry", systemImage: "gauge.with.dots.needle.67percent")
                    }
                }
                .listRowBackground(Instrument.bg1)
                .listRowSeparatorTint(Instrument.hairline)
            }
            .instrumentScreen()
            .navigationTitle("Latency")
        }
    }
}
