import Charts
import SwiftUI

/// Fires N pings over ALL three transports, then shows a CDF overlay of their distributions plus a
/// per-transport p50/p95/p99/max + throughput table — the cost gap, quantified.
struct TransportComparisonView: View {
    @ObservedObject var model: LatencyViewModel

    @State private var samples: [TransportKind: [Double]] = [:]
    @State private var running = false
    private let count = 200

    var body: some View {
        List {
            Section {
                Button {
                    Task { await run() }
                } label: {
                    if running {
                        HStack(spacing: 8) { ProgressView(); Text("Comparing…") }
                    } else {
                        Label("Compare all transports", systemImage: "chart.line.uptrend.xyaxis")
                    }
                }
                .disabled(running)
                Text("Fires \(count) pings over FFI, HTTP, and SQLCipher and compares the distributions.")
                    .font(.caption).foregroundStyle(.secondary)
            }

            if !samples.isEmpty {
                Section("Percentiles + throughput") {
                    ForEach(TransportKind.allCases) { kind in
                        let s = LatencyStats.summary(samples[kind] ?? [])
                        VStack(alignment: .leading, spacing: 4) {
                            Text(kind.displayName).font(.subheadline)
                            HStack {
                                stat("p50", s.p50); stat("p95", s.p95); stat("p99", s.p99); stat("max", s.max)
                            }
                            Text("\(Int(s.throughput)) calls/sec").font(.caption2).foregroundStyle(.secondary)
                        }
                        .padding(.vertical, 2)
                    }
                }
                Section("Distribution overlay (CDF)") {
                    Chart {
                        ForEach(TransportKind.allCases) { kind in
                            ForEach(Array(LatencyStats.cdf(samples[kind] ?? []).enumerated()), id: \.offset) { _, point in
                                LineMark(x: .value("ms", point.value), y: .value("fraction", point.fraction))
                                    .foregroundStyle(by: .value("transport", kind.displayName))
                            }
                        }
                    }
                    .chartXAxisLabel("round-trip (ms)")
                    .chartYAxisLabel("cumulative fraction")
                    .chartLegend(position: .bottom)
                    .frame(height: 240)
                }
            }
        }
        .navigationTitle("Transport comparison")
    }

    private func stat(_ label: String, _ value: Double) -> some View {
        VStack(spacing: 1) {
            Text(label).font(.caption2).foregroundStyle(.secondary)
            Text(String(format: "%.2f", value)).font(.caption.monospacedDigit())
        }
        .frame(maxWidth: .infinity)
    }

    @MainActor
    private func run() async {
        running = true
        defer { running = false }
        var collected: [TransportKind: [Double]] = [:]
        for kind in TransportKind.allCases {
            collected[kind] = await model.pingSeries(count: count, on: kind)
        }
        samples = collected
    }
}
