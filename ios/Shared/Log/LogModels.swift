import Foundation

/// One engine log record from `dni_log_drain` — the logging leg of the observability trio (alongside the
/// Trace waterfall's `dni_trace_drain`). `timestampUs` is µs from engine boot, on the SAME clock as trace
/// spans, so a record aligns to the same timeline. `exception` is "TypeName: message" when the record
/// carried one (the detail the FFI boundary used to swallow silently) — nil otherwise. Level is
/// Information and above (the engine drops Trace/Debug at the logger).
struct LogRecord: Codable, Identifiable, Sendable {
    let level: String
    let category: String
    let message: String
    let timestampUs: Double
    let requestId: String?
    let exception: String?

    /// SwiftUI list identity only — the wire carries no record id, so one is minted per decoded value and
    /// excluded from Codable via CodingKeys (re-encoding a drained record round-trips the payload unchanged).
    var id = UUID()

    enum CodingKeys: String, CodingKey {
        case level, category, message, timestampUs, requestId, exception
    }

    init(
        level: String,
        category: String,
        message: String,
        timestampUs: Double,
        requestId: String? = nil,
        exception: String? = nil
    ) {
        self.level = level
        self.category = category
        self.message = message
        self.timestampUs = timestampUs
        self.requestId = requestId
        self.exception = exception
    }

    /// Severity rank for filtering/coloring — Information(0) < Warning(1) < Error(2) < Critical(3). An
    /// unknown level ranks 0 (shown under "all", never hidden by a severity filter). Mirrors Android's
    /// `levelRank`.
    var severityRank: Int {
        switch level {
        case "Critical": return 3
        case "Error": return 2
        case "Warning": return 1
        default: return 0
        }
    }
}

/// The `dni_log_drain` payload: every record captured since the previous drain, the engine's µs-since-boot
/// at drain time, the ring capacity, and the count of records DROPPED to ring overflow since the last
/// drain. Overflow is disclosed here and surfaced in the UI — never silently swallowed (mirrors `TraceDrain`).
struct LogDrain: Codable, Sendable {
    let nowUs: Double
    let dropped: Int
    let capacity: Int
    let records: [LogRecord]

    enum CodingKeys: String, CodingKey {
        case nowUs, dropped, capacity, records
    }

    init(nowUs: Double = 0, dropped: Int = 0, capacity: Int = 256, records: [LogRecord] = []) {
        self.nowUs = nowUs
        self.dropped = dropped
        self.capacity = capacity
        self.records = records
    }

    /// Lenient by design: the engine may evolve this payload, and a missing key must degrade to its
    /// default (0 / 0 / 256 / empty) rather than fail the whole drain.
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        nowUs = try container.decodeIfPresent(Double.self, forKey: .nowUs) ?? 0
        dropped = try container.decodeIfPresent(Int.self, forKey: .dropped) ?? 0
        capacity = try container.decodeIfPresent(Int.self, forKey: .capacity) ?? 256
        records = try container.decodeIfPresent([LogRecord].self, forKey: .records) ?? []
    }
}

/// The Log segment's severity filter (parity with the Trace waterfall's request filter). A record is
/// visible iff its `severityRank` >= the filter's `minRank`.
enum LogFilter: String, CaseIterable, Identifiable {
    case all = "all"
    case warnPlus = "warn+"
    case errorsOnly = "errors"

    var id: Self { self }

    var minRank: Int {
        switch self {
        case .all: return 0
        case .warnPlus: return 1
        case .errorsOnly: return 2
        }
    }
}

/// Reads the engine's log ring — abstracted for testability so `LogViewModel` tests inject a fake that
/// never touches the C ABI (mirrors `AgentTraceReader`).
protocol LogReader: Sendable {
    func drain() -> LogDrain?
}

/// Real reader: `dni_log_drain` returns heap UTF-8 JSON — copy it, release it with `dni_string_free`, then
/// decode. Returns nil on any failure (NULL from the export, or undecodable JSON); the caller renders that
/// honestly as "no records captured", never a crash.
final class NativeLogReader: LogReader {
    func drain() -> LogDrain? {
        guard let ptr = dni_log_drain() else { return nil }
        defer { dni_string_free(ptr) }
        let json = String(cString: ptr)
        return try? JSONDecoder().decode(LogDrain.self, from: Data(json.utf8))
    }
}
