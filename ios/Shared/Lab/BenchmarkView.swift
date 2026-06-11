import Charts
import SwiftUI

/// Multi-series line+point chart of a benchmark payload (e.g. scalar vs SIMD GFLOPS across sizes).
struct BenchmarkChart: View {
    let series: [BenchmarkSeries]

    var body: some View {
        Chart {
            ForEach(series) { line in
                ForEach(Array(line.points.enumerated()), id: \.offset) { _, point in
                    LineMark(x: .value("x", point.x), y: .value("y", point.y))
                        .foregroundStyle(by: .value("series", line.name))
                    PointMark(x: .value("x", point.x), y: .value("y", point.y))
                        .foregroundStyle(by: .value("series", line.name))
                }
            }
        }
        .chartLegend(position: .bottom)
        .frame(height: 240)
    }
}

/// Runs a benchmark command over the selected transport, then charts the result + summary stats.
struct BenchmarkDetailView: View {
    @ObservedObject var lab: LabViewModel
    let title: String
    let command: String

    @State private var payload: BenchmarkPayload?
    @State private var running = false
    @State private var errorMessage: String?

    var body: some View {
        List {
            Section {
                Button {
                    Task { await run() }
                } label: {
                    if running {
                        HStack(spacing: 8) { ProgressView(); Text("Running…") }
                    } else {
                        Label("Run benchmark", systemImage: "bolt.fill")
                    }
                }
                .disabled(running)
                LabTransportPicker(transport: $lab.transport)
                Text("The benchmark executes inside the NativeAOT library and returns its series as JSON "
                     + "over the selected transport.")
                    .font(.caption).foregroundStyle(Instrument.textSecondary)
            }
            .listRowBackground(Instrument.bg1)
            .listRowSeparatorTint(Instrument.hairline)

            if let payload {
                Section(payload.title) { BenchmarkChart(series: payload.series) }
                    .listRowBackground(Instrument.bg1)
                    .listRowSeparatorTint(Instrument.hairline)
                Section("Summary") {
                    ForEach(payload.summary) { stat in
                        LabeledContent(stat.label, value: stat.value)
                    }
                }
                .listRowBackground(Instrument.bg1)
                .listRowSeparatorTint(Instrument.hairline)
            } else if !running {
                Section {
                    ContentUnavailableView("No run yet", systemImage: "chart.xyaxis.line",
                                           description: Text("Tap Run to execute the benchmark."))
                }
                .listRowBackground(Instrument.bg1)
                .listRowSeparatorTint(Instrument.hairline)
            }

            if let errorMessage {
                Section {
                    ErrorBanner(message: errorMessage)
                }
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets())
            } else if let lastError = lab.lastError, payload == nil, !running {
                Section {
                    ErrorBanner(message: lastError)
                }
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets())
            }
        }
        .instrumentScreen()
        .navigationTitle(title)
    }

    private func run() async {
        running = true
        defer { running = false }
        guard let result = await lab.render(command) else {
            errorMessage = "The native library returned no data."
            return
        }
        do {
            payload = try JSONDecode.decode(BenchmarkPayload.self, from: result.result)
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
