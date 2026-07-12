import SwiftUI

/// Log level → signal color: Error/Critical = red, Warning = amber, everything else = neutral. Uses the
/// SAME shared tokens the Trace legend does; Android mirrors this exact mapping in `LogScreen.kt`'s
/// `levelColor`.
func logLevelColor(_ level: String) -> Color {
    switch level {
    case "Critical", "Error": return Instrument.fail
    case "Warning": return Instrument.warn
    default: return Instrument.textSecondary
    }
}

/// Analysis · Log: drains the engine log ring (`dni_log_drain`) and renders the captured records — the
/// logging leg of the observability trio. This is where the errors the FFI boundary would otherwise
/// swallow silently (e.g. a token drain that ends abnormally) become visible. Ring overflow is disclosed —
/// the dropped-record count is shown, never silently swallowed (mirrors the Trace waterfall).
struct LogView: View {
    @ObservedObject var model: LogViewModel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Instrument.Space.m) {
                PanelHeader("Log · engine diagnostics")

                HStack(alignment: .center) {
                    Button {
                        model.drain()
                    } label: {
                        Label("Drain ring", systemImage: "arrow.down.circle")
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(Instrument.accent)

                    Spacer()
                    StatCell(label: "Records", value: "\(model.lastDrainCount)")
                    StatCell(label: "Capacity", value: "\(model.capacity)")
                }

                if model.droppedThisDrain > 0 || model.droppedTotal > 0 {
                    Text("⚠ \(model.droppedThisDrain) record(s) dropped to ring overflow this drain "
                        + "(\(model.droppedTotal) total) — the \(model.capacity)-record ring is drop-oldest, disclosed here.")
                        .font(.caption2)
                        .foregroundStyle(Instrument.warn)
                }

                if let error = model.error {
                    ErrorBanner(message: error, retry: { model.drain() })
                }

                Picker("Severity", selection: $model.filter) {
                    ForEach(LogFilter.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)

                let records = model.visibleRecords
                if records.isEmpty {
                    if model.error == nil {
                        Text(model.hiddenByFilter > 0
                            ? "No records at this severity (\(model.hiddenByFilter) hidden by the filter)."
                            : "No log records. Run a session/agent turn (or cancel one mid-stream), then Drain the ring.")
                            .font(.footnote)
                            .foregroundStyle(Instrument.textSecondary)
                            .padding(.top, Instrument.Space.s)
                    }
                } else {
                    VStack(alignment: .leading, spacing: Instrument.Space.s) {
                        PanelHeader("records · engine-side (Information and above)")
                        ForEach(records) { LogRowView(record: $0) }
                    }
                    .instrumentCard()
                }
            }
            .padding(Instrument.Space.l)
        }
        .instrumentScreen()
    }
}

/// One log record: a colored level pill + category + timestamp, then the message, then the exception
/// detail (in the level color) the FFI boundary used to swallow.
private struct LogRowView: View {
    let record: LogRecord

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: Instrument.Space.s) {
                Text(record.level.uppercased())
                    .font(.system(size: 10, weight: .semibold, design: .monospaced))
                    .foregroundStyle(Instrument.bg0)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(logLevelColor(record.level), in: Capsule())
                Text(record.category)
                    .font(.system(.caption, design: .monospaced))
                    .foregroundStyle(Instrument.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text("\(Int(record.timestampUs)) µs")
                    .font(.system(.caption, design: .monospaced))
                    .foregroundStyle(Instrument.textTertiary)
            }
            Text(record.message)
                .font(.caption)
                .foregroundStyle(Instrument.textPrimary)
            if let exception = record.exception {
                Text(exception)
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(logLevelColor(record.level))
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
