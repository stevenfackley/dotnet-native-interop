import Foundation

/// One engine-side span from `dni_trace_drain` — an `Activity` flattened for the waterfall. The µs
/// offsets are from engine boot; the client aligns them to its own clock with one offset per drain (see
/// `TraceDrain.nowUs`), accurate to ±the drain round-trip. Client-side spans (marshal/socket/decode) are
/// NOT in this payload; the waterfall labels these engine-side and states the tolerance.
///
/// `toolArgs`/`toolResult` are populated ONLY on Foreman's `agent.tool.<name>` spans (see
/// `EngineTrace.Record` / `ForemanAgent.cs` on the engine side) — every other span leaves them nil. Both
/// are already bounded + truncated engine-side (args <= 256 chars, result <= 512 chars, a visible
/// `"…(truncated)"` suffix when clamped) so a large `search_manuals` result can't blow the drain
/// payload; a failed/unknown tool call still tags `toolResult` with its JSON error, never blank — the
/// Foreman tool-call strip (`formatToolCall` in Shared/Foreman/AgentModels.swift) is the sole consumer
/// of these two fields.
struct TraceSpan: Codable, Identifiable, Sendable {
    let name: String
    let startUs: Double
    let durUs: Double
    let requestId: String?
    let status: String?
    let toolArgs: String?
    let toolResult: String?

    /// SwiftUI list identity only — the wire carries no span id, so one is minted per decoded value and
    /// excluded from Codable via CodingKeys (re-encoding a drained span round-trips the payload unchanged).
    var id = UUID()

    enum CodingKeys: String, CodingKey {
        case name, startUs, durUs, requestId, status, toolArgs, toolResult
    }

    init(
        name: String,
        startUs: Double,
        durUs: Double,
        requestId: String? = nil,
        status: String? = nil,
        toolArgs: String? = nil,
        toolResult: String? = nil
    ) {
        self.name = name
        self.startUs = startUs
        self.durUs = durUs
        self.requestId = requestId
        self.status = status
        self.toolArgs = toolArgs
        self.toolResult = toolResult
    }
}

/// The `dni_trace_drain` payload: every span recorded since the previous drain, the engine's
/// µs-since-boot at drain time, the ring capacity, and the count of spans DROPPED to ring overflow since
/// the last drain. Overflow is disclosed here and surfaced in the UI — never silently swallowed.
struct TraceDrain: Codable, Sendable {
    let nowUs: Double
    let dropped: Int
    let capacity: Int
    let spans: [TraceSpan]

    enum CodingKeys: String, CodingKey {
        case nowUs, dropped, capacity, spans
    }

    init(nowUs: Double = 0, dropped: Int = 0, capacity: Int = 512, spans: [TraceSpan] = []) {
        self.nowUs = nowUs
        self.dropped = dropped
        self.capacity = capacity
        self.spans = spans
    }

    /// Lenient by design: the engine may evolve this payload, and a missing key must degrade to its
    /// default (0 / 0 / 512 / empty) rather than fail the whole drain. Unknown keys are already
    /// tolerated — a keyed container only reads the keys it is asked for.
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        nowUs = try container.decodeIfPresent(Double.self, forKey: .nowUs) ?? 0
        dropped = try container.decodeIfPresent(Int.self, forKey: .dropped) ?? 0
        capacity = try container.decodeIfPresent(Int.self, forKey: .capacity) ?? 512
        spans = try container.decodeIfPresent([TraceSpan].self, forKey: .spans) ?? []
    }
}

/// Reads the engine's span ring for the Foreman tool-call strip — the SAME `dni_trace_drain` ring the
/// Analysis · Trace waterfall reads (see `turnSpans(from:expectedToolSteps:)`'s correlation caveat).
/// Abstracted for testability: ForemanViewModel tests inject a fake that never touches the C ABI.
protocol AgentTraceReader: Sendable {
    func drain() -> TraceDrain?
}

/// Real reader: `dni_trace_drain` returns heap UTF-8 JSON — copy it, release it with `dni_string_free`,
/// then decode. Returns nil on any failure (NULL from the export, or undecodable JSON); the caller
/// renders that honestly as "no spans captured", never a crash.
final class NativeAgentTraceReader: AgentTraceReader {
    func drain() -> TraceDrain? {
        guard let ptr = dni_trace_drain() else { return nil }
        defer { dni_string_free(ptr) }
        let json = String(cString: ptr)
        return try? JSONDecoder().decode(TraceDrain.self, from: Data(json.utf8))
    }
}
