import Foundation

/// Mirrors `DotnetNativeInterop.Engine.Ai.Agent.ForemanStopReason` (source-gen'd as its name string via
/// `UseStringEnumConverter` — see `ForemanModels.cs`) — why a Foreman turn ended. A client MUST
/// distinguish these honestly: `StepCapReached` / `Error` must never be presented to the user as a
/// clean `Answered`. Cases keep their C# capitalization because rawValue == the wire string.
enum AgentStopReason: String, Codable, Sendable {
    case Answered, StepCapReached, Error
}

/// Wire shape of the status fragment's JSON payload (mirrors `AgentSessionStatus` in `ForemanModels.cs`).
private struct RawAgentSessionStatus: Decodable {
    let stopReason: AgentStopReason
    let toolSteps: Int
    let backend: String
}

/// Parsed, honest turn status. `backend` is the ACTUAL brain that ran the turn (e.g. "scripted routing —
/// no on-device LLM present"), read from the wire — never hardcoded/assumed by the client.
struct AgentTurnStatus: Sendable, Equatable {
    let stopReason: AgentStopReason
    let toolSteps: Int
    let backend: String
}

/// One item streamed by a Foreman turn: either real answer text, or the terminal status.
enum AgentFragment: Sendable {
    case answer(String)
    case status(AgentTurnStatus)
}

/// Thrown when a fragment is a status marker (leading 0x01) but its readable tag is missing — a malformed
/// terminal marker. The service layer maps this onto an honest Error status, never a swallowed spinner.
enum AgentFragmentError: Error { case malformedStatusMarker }

// The status marker's readable tag, kept only for log greppability — mirrors
// ForemanLanguageModel.StatusMarker / dni_agent_session_start's documented contract in abi/dni.h.
// DETECTION below happens on the leading 0x01 control byte alone, NEVER on this tag text, because an
// on-device LLM answer could legitimately stream the literal characters "dni.agent.status" while
// answering a question about the marker itself.
// internal (not private): AgentModelsTests builds a synthetic status fragment from these directly, so
// the test always agrees with production on what the marker actually is instead of hand-rolling a
// duplicate.
let agentStatusTag = "dni.agent.status"
let agentStatusControlByte: Character = "\u{01}"

/// The `agent.turn` / `agent.tool.<name>` span names `ForemanAgent.RunTurnAsync` records (see dni.h).
private let agentTurnSpanName = "agent.turn"
private let agentToolSpanPrefix = "agent.tool."

/// Classifies one raw `dni_token_cb` fragment per `dni_agent_session_start`'s completion contract
/// (abi/dni.h): the ONE fragment whose first character is the 0x01 control byte is the status fragment;
/// every other (non-empty) fragment is real streamed answer text. Detect on the control byte, not the
/// tag. Throws only while JSON-decoding a malformed status payload — the service layer (FFIAgentService)
/// maps that throw onto an honest Error status rather than swallowing it into a stuck spinner.
func parseAgentFragment(_ text: String) throws -> AgentFragment {
    guard text.first == agentStatusControlByte else { return .answer(text) }
    // Detection stays on the leading 0x01 (never the tag text — an on-device LLM could stream the literal
    // "dni.agent.status" while answering ABOUT the marker). But the JSON offset is found STRUCTURALLY by
    // the tag rather than a fixed length: the live engine writes the marker with a REPEATED 0x01 prefix
    // (observed 0x01 0x01 dni.agent.status…), so a fixed `1 + tag.count` skip lands one byte short and
    // yields invalid JSON. Taking everything after the tag is robust to any number of leading control
    // bytes. (The engine's StatusMarker is declared with a single 0x01 — the doubled prefix on the wire
    // is worth an engine-side look; flagged, fixed defensively client-side here.) No tag ⇒ malformed.
    guard let tagRange = text.range(of: agentStatusTag) else {
        throw AgentFragmentError.malformedStatusMarker
    }
    let payload = String(text[tagRange.upperBound...])
    let raw = try JSONDecoder().decode(RawAgentSessionStatus.self, from: Data(payload.utf8))
    return .status(AgentTurnStatus(stopReason: raw.stopReason, toolSteps: raw.toolSteps, backend: raw.backend))
}

/// Best-effort per-turn span slice for the tool-call strip: the latest `agent.turn` span (max startUs,
/// if present) plus the last `expectedToolSteps` `agent.tool.*` spans in the drain, oldest first —
/// reusing the SAME `dni_trace_drain` ring the Analysis · Trace waterfall reads (no new export).
///
/// NOT correlated by request id: `ForemanAgent.RunTurnAsync` starts its spans via
/// `EngineTrace.Source.StartActivity` directly (see ForemanAgent.cs), not `EngineTrace.StartSpan(name,
/// requestId)`, so agent spans carry no `dni.request_id` tag today. This is reliable only when a single
/// Foreman turn is in flight at a time, which ForemanViewModel enforces (one active turn, prior turn
/// cancelled). A future engine change to tag agent spans with a turn id would make this exact rather
/// than best-effort — flagged, not done here (out of this task's authorized scope).
func turnSpans(from drain: TraceDrain?, expectedToolSteps: Int) -> [TraceSpan] {
    guard let drain else { return [] }
    let toolSpans = drain.spans
        .filter { $0.name.hasPrefix(agentToolSpanPrefix) }
        .sorted { $0.startUs < $1.startUs }
        .suffix(max(expectedToolSteps, 0))
    let turnSpan = drain.spans
        .filter { $0.name == agentTurnSpanName }
        .max { $0.startUs < $1.startUs }
    let combined = (turnSpan.map { [$0] } ?? []) + Array(toolSpans)
    return combined.sorted { $0.startUs < $1.startUs }
}

/// Formats one `agent.tool.<name>` span as `name(args) -> result` for the Foreman tool-call strip — the
/// REAL args/result the engine tagged on this span (`dni.agent.tool_args`/`dni.agent.tool_result`, see
/// `EngineTrace`/`ForemanAgent.cs`), already bounded + truncated engine-side (args <= 256 chars, result
/// <= 512 chars, a visible `"…(truncated)"` suffix when clamped) — not a per-tool hand-tuned summary, so
/// it stays honest and generic across every current and future tool. A failed/unknown tool call still
/// shows its JSON error here (the engine tags the error as the result, never leaves it blank).
///
/// Falls back to an explicit "not captured" placeholder (never a blank string) for a span drained from
/// an engine build that predates these tags, so an old/new build mismatch reads as missing data, not a
/// bug.
func formatToolCall(_ span: TraceSpan) -> String {
    let name = span.name.hasPrefix(agentToolSpanPrefix)
        ? String(span.name.dropFirst(agentToolSpanPrefix.count))
        : span.name
    let args = span.toolArgs ?? "(args not captured)"
    let result = span.toolResult ?? "(result not captured)"
    return "\(name)(\(args)) -> \(result)"
}
