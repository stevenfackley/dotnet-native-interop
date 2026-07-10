import Charts
import SwiftUI

/// Fires N pings over ALL three transports, then shows a CDF overlay of their distributions plus a
/// per-transport p50/p95/p99/max + throughput table — the cost gap, quantified.
struct TransportComparisonView: View {
    @ObservedObject var model: LatencyViewModel

    @State private var series: [TransportKind: LatencyViewModel.Series] = [:]
    @State private var running = false
    private let count = 200

    private func samples(_ kind: TransportKind) -> [Double] { series[kind]?.samples ?? [] }
    private var totalFailures: Int { series.values.reduce(0) { $0 + $1.failures } }

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
                    .font(.caption).foregroundStyle(Instrument.textSecondary)
            }
            .instrumentRow()

            if totalFailures > 0 {
                Section {
                    ErrorBanner(message: "\(totalFailures) pings failed and are excluded — "
                        + failureBreakdown)
                        .listRowInsets(EdgeInsets())
                }
                .listRowBackground(Color.clear)
            }

            if !series.isEmpty {
                Section("Percentiles + throughput") {
                    ForEach(TransportKind.allCases) { kind in
                        let s = LatencyStats.summary(samples(kind))
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text(kind.displayName).font(.subheadline)
                                if let f = series[kind]?.failures, f > 0 {
                                    Text("\(f) failed").font(.caption2).foregroundStyle(Instrument.warn)
                                }
                            }
                            HStack {
                                stat("p50", s.p50); stat("p95", s.p95); stat("p99", s.p99); stat("max", s.max)
                            }
                            Text("\(Int(s.throughput)) calls/sec").font(.caption2).foregroundStyle(Instrument.textSecondary)
                        }
                        .padding(.vertical, 2)
                    }
                }
                .instrumentRow()
                Section("Distribution overlay (CDF)") {
                    Chart {
                        ForEach(TransportKind.allCases) { kind in
                            ForEach(Array(LatencyStats.cdf(samples(kind)).enumerated()), id: \.offset) { _, point in
                                LineMark(x: .value("ms", point.value), y: .value("fraction", point.fraction))
                                    .foregroundStyle(by: .value("transport", kind.displayName))
                            }
                        }
                    }
                    // Canonical transport colors, never an arbitrary palette (facelift spec §2/§5).
                    .chartForegroundStyleScale(
                        domain: TransportKind.allCases.map(\.displayName),
                        range: TransportKind.allCases.map { Instrument.transport($0) }
                    )
                    .chartXAxisLabel("round-trip (ms)")
                    .chartYAxisLabel("cumulative fraction")
                    .chartLegend(position: .bottom)
                    .frame(height: 240)
                }
                .instrumentRow()
            }
        }
        .instrumentScreen()
        .navigationTitle("Transport comparison")
    }

    private func stat(_ label: String, _ value: Double) -> some View {
        VStack(spacing: 1) {
            Text(label).font(.caption2).foregroundStyle(Instrument.textSecondary)
            Text(LatencyStats.formatLatencyMs(value)).font(.caption.monospacedDigit())
                .contentTransition(.numericText())
        }
        .frame(maxWidth: .infinity)
    }

    private var failureBreakdown: String {
        TransportKind.allCases
            .compactMap { kind -> String? in
                guard let f = series[kind]?.failures, f > 0 else { return nil }
                return "\(kind.displayName): \(f)"
            }
            .joined(separator: ", ")
    }

    @MainActor
    private func run() async {
        running = true
        defer { running = false }
        var collected: [TransportKind: LatencyViewModel.Series] = [:]
        for kind in TransportKind.allCases {
            collected[kind] = await model.pingSeries(count: count, on: kind)
        }
        series = collected
    }
}
