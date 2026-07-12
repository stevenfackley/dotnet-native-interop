import Foundation

/// Drives the Analysis · Log segment. Drains the engine log ring (`dni_log_drain`) on demand and holds the
/// most recent drain, plus a cumulative dropped-record count. Ring overflow is disclosed (never silently
/// swallowed) — the payload's `dropped` is accumulated and shown. Mirrors the Android `LogViewModel` and
/// the Trace segment's shape.
@MainActor
final class LogViewModel: ObservableObject {
    @Published private(set) var records: [LogRecord] = []
    @Published private(set) var nowUs: Double = 0
    @Published private(set) var capacity: Int = 256
    @Published private(set) var droppedThisDrain: Int = 0
    @Published private(set) var droppedTotal: Int = 0
    @Published private(set) var lastDrainCount: Int = 0
    @Published private(set) var error: String?
    @Published var filter: LogFilter = .all

    private let reader: LogReader

    init(reader: LogReader = NativeLogReader()) {
        self.reader = reader
    }

    /// Records passing the current severity filter (drain order is oldest-first).
    var visibleRecords: [LogRecord] {
        records.filter { $0.severityRank >= filter.minRank }
    }

    /// How many records the current severity filter hides (shown as a hint when > 0).
    var hiddenByFilter: Int { records.count - visibleRecords.count }

    /// Drains the ring and replaces the current view with the drained records. A NULL/undecodable drain
    /// surfaces honestly as an error, never a crash.
    func drain() {
        guard let drain = reader.drain() else {
            error = "dni_log_drain returned null"
            return
        }
        error = nil
        records = drain.records
        nowUs = drain.nowUs
        capacity = drain.capacity
        droppedThisDrain = drain.dropped
        droppedTotal += drain.dropped
        lastDrainCount = drain.records.count
    }

    func select(_ filter: LogFilter) {
        self.filter = filter
    }
}
