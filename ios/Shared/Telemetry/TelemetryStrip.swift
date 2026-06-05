import SwiftUI

/// Presentational live readout of engine runtime stats. No polling — pass in the latest `EngineStats`.
struct TelemetryStrip: View {
    let stats: EngineStats?

    var body: some View {
        if let s = stats {
            Grid(alignment: .leading, horizontalSpacing: 16, verticalSpacing: 6) {
                GridRow { cell("heap", mb(s.heapBytes)); cell("alloc", mb(s.allocatedBytes)) }
                GridRow { cell("GC 0/1/2", "\(s.gcGen0)/\(s.gcGen1)/\(s.gcGen2)"); cell("GC pause", String(format: "%.0f ms", s.gcPauseMs)) }
                GridRow { cell("threads", "\(s.threadCount)"); cell("cores", "\(s.processorCount)") }
                GridRow { cell("committed", mb(s.committedBytes)); cell("uptime", String(format: "%.0f s", s.uptimeMs / 1000)) }
            }
            .font(.caption.monospacedDigit())
        } else {
            Text("telemetry unavailable").font(.caption).foregroundStyle(.secondary)
        }
    }

    private func mb(_ bytes: Int) -> String { String(format: "%.1f MB", Double(bytes) / 1_048_576) }

    private func cell(_ label: String, _ value: String) -> some View {
        HStack(spacing: 6) {
            Text(label).foregroundStyle(.secondary)
            Text(value)
        }
    }
}
