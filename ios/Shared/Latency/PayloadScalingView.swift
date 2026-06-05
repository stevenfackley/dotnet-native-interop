import Charts
import SwiftUI

/// Latency vs response size: sweeps `bench-echo~bytes_N` over increasing N for the selected transport,
/// averaging a few reps per size — exposes where serialization/copy cost dominates per transport.
struct PayloadScalingView: View {
    @ObservedObject var model: LatencyViewModel

    @State private var points: [(label: String, ms: Double)] = []
    @State private var running = false

    private let sizes: [(label: String, bytes: Int)] =
        [("64 B", 64), ("1 KB", 1_024), ("16 KB", 16_384), ("256 KB", 262_144), ("1 MB", 1_048_576)]
    private let reps = 5

    var body: some View {
        List {
            Section {
                LabTransportPicker(transport: $model.transport)
                Button {
                    Task { await run() }
                } label: {
                    if running {
                        HStack(spacing: 8) { ProgressView(); Text("Sweeping…") }
                    } else {
                        Label("Sweep payload sizes", systemImage: "arrow.up.right.square")
                    }
                }
                .disabled(running)
                Text("Round-trips an N-byte `bench-echo` over \(model.transport.displayName), averaged "
                     + "over \(reps) reps per size.")
                    .font(.caption).foregroundStyle(.secondary)
            }

            if !points.isEmpty {
                Section("Latency vs payload size (ms)") {
                    Chart {
                        ForEach(Array(points.enumerated()), id: \.offset) { _, point in
                            BarMark(x: .value("size", point.label), y: .value("ms", point.ms))
                                .foregroundStyle(ComparisonView.color(model.transport))
                        }
                    }
                    .chartYAxisLabel("round-trip (ms)")
                    .frame(height: 240)
                }
                Section("Values") {
                    ForEach(Array(points.enumerated()), id: \.offset) { _, point in
                        LabeledContent(point.label) {
                            Text(String(format: "%.2f ms", point.ms)).monospacedDigit()
                        }
                    }
                }
            }
        }
        .navigationTitle("Payload scaling")
    }

    @MainActor
    private func run() async {
        running = true
        defer { running = false }
        var collected: [(label: String, ms: Double)] = []
        for size in sizes {
            var total = 0.0
            var hits = 0
            for _ in 0..<reps {
                if let ms = await model.roundTripMs("bench-echo~bytes_\(size.bytes)", on: model.transport) {
                    total += ms
                    hits += 1
                }
            }
            collected.append((label: size.label, ms: hits > 0 ? total / Double(hits) : 0))
        }
        points = collected
    }
}
