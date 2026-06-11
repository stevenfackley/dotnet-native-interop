import SwiftUI

/// Presentational live readout of engine runtime stats. No polling — pass in the latest `EngineStats`.
struct TelemetryStrip: View {
    let stats: EngineStats?

    var body: some View {
        if let s = stats {
            Grid(alignment: .leading, horizontalSpacing: Instrument.Space.l,
                 verticalSpacing: Instrument.Space.m) {
                GridRow {
                    StatCell(label: "heap", value: mb(s.heapBytes), tint: Instrument.accent)
                    StatCell(label: "alloc", value: mb(s.allocatedBytes))
                }
                GridRow {
                    StatCell(label: "GC 0/1/2", value: "\(s.gcGen0)/\(s.gcGen1)/\(s.gcGen2)")
                    StatCell(label: "GC pause", value: String(format: "%.0f ms", s.gcPauseMs))
                }
                GridRow {
                    StatCell(label: "threads", value: "\(s.threadCount)")
                    StatCell(label: "cores", value: "\(s.processorCount)")
                }
                GridRow {
                    StatCell(label: "committed", value: mb(s.committedBytes))
                    StatCell(label: "uptime", value: String(format: "%.0f s", s.uptimeMs / 1000))
                }
            }
        } else {
            Text("telemetry unavailable")
                .font(.caption)
                .foregroundStyle(Instrument.textTertiary)
        }
    }

    private func mb(_ bytes: Int) -> String { String(format: "%.1f MB", Double(bytes) / 1_048_576) }
}
