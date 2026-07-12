package io.dotnetnativeinterop.agent

import io.dotnetnativeinterop.trace.TraceDrain
import io.dotnetnativeinterop.trace.TraceSpan
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Mirrors `DotnetNativeInterop.Engine.Ai.Agent.ForemanStopReason` (source-gen'd as its name string via
 * `UseStringEnumConverter` — see `ForemanModels.cs`) — why a Foreman turn ended. A client MUST distinguish
 * these honestly: [StepCapReached] / [Error] must never be presented to the user as a clean [Answered].
 */
@Serializable
public enum class AgentStopReason { Answered, StepCapReached, Error }

/** Wire shape of the status fragment's JSON payload (mirrors `AgentSessionStatus` in `ForemanModels.cs`). */
@Serializable
internal data class RawAgentSessionStatus(
    val stopReason: AgentStopReason,
    val toolSteps: Int,
    val backend: String,
)

/**
 * Parsed, honest turn status. [backend] is the ACTUAL brain that ran the turn (e.g. "scripted routing —
 * no on-device LLM present"), read from the wire — never hardcoded/assumed by the client.
 */
public data class AgentTurnStatus(
    val stopReason: AgentStopReason,
    val toolSteps: Int,
    val backend: String,
)

/** One item streamed by a Foreman turn: either real answer text, or the terminal status. */
public sealed interface AgentFragment {
    public data class Answer(val text: String) : AgentFragment
    public data class Status(val status: AgentTurnStatus) : AgentFragment
}

// The status marker's readable tag, kept only for log greppability — mirrors
// ForemanLanguageModel.StatusMarker / dni_agent_session_start's documented contract in abi/dni.h.
// DETECTION below happens on the leading 0x01 control byte alone, NEVER on this tag text, because an
// on-device LLM answer could legitimately stream the literal characters "dni.agent.status" while
// answering a question about the marker itself.
// internal (not private): AgentModelsTest builds a synthetic status fragment from these directly, so the
// test always agrees with production on what the marker actually is instead of hand-rolling a duplicate.
internal const val STATUS_TAG = "dni.agent.status"
internal const val CONTROL_BYTE = '\u0001'

private val agentJson = Json { ignoreUnknownKeys = true }

/**
 * Classifies one raw `dni_token_cb` fragment per `dni_agent_session_start`'s completion contract
 * (abi/dni.h): the ONE fragment whose first character is the 0x01 control byte is the status fragment;
 * every other (non-empty) fragment is real streamed answer text. Detect on the control byte, not the tag.
 *
 * The JSON offset is found STRUCTURALLY by the tag rather than a fixed `1 + STATUS_TAG.length` skip: an
 * earlier engine bug emitted a DOUBLED 0x01 prefix (a stray raw control byte in
 * ForemanLanguageModel.StatusMarker, since root-caused + fixed so the wire is a single 0x01 per abi/dni.h)
 * which made a fixed skip land one byte short on invalid JSON. Taking everything after the tag stays robust
 * to any number of leading control bytes regardless; detection stays on the leading 0x01 (never the tag
 * text — an LLM could stream "dni.agent.status" while answering ABOUT the marker). No tag ⇒ malformed
 * ⇒ decode throws honestly.
 */
public fun parseAgentFragment(text: String): AgentFragment {
    if (text.isNotEmpty() && text[0] == CONTROL_BYTE) {
        val tagStart = text.indexOf(STATUS_TAG)
        require(tagStart >= 0) { "status fragment (leading 0x01) is missing the '$STATUS_TAG' tag" }
        val payload = text.substring(tagStart + STATUS_TAG.length)
        val raw = agentJson.decodeFromString<RawAgentSessionStatus>(payload)
        return AgentFragment.Status(AgentTurnStatus(raw.stopReason, raw.toolSteps, raw.backend))
    }
    return AgentFragment.Answer(text)
}

/**
 * Best-effort per-turn span slice for the tool-call strip: the `agent.turn` span (if present) plus the
 * last [expectedToolSteps] `agent.tool.*` spans in the drain, oldest first — reusing the SAME
 * `dni_trace_drain` ring the Analysis · Trace waterfall reads (no new export).
 *
 * NOT correlated by request id: `ForemanAgent.RunTurnAsync` starts its spans via
 * `EngineTrace.Source.StartActivity` directly (see ForemanAgent.cs), not `EngineTrace.StartSpan(name,
 * requestId)`, so agent spans carry no `dni.request_id` tag today. This is reliable only when a single
 * Foreman turn is in flight at a time, which [io.dotnetnativeinterop.agent.AgentViewModel] enforces
 * (one active turn job). A future engine change to tag agent spans with a turn id would make this exact
 * rather than best-effort — flagged, not done here (out of this task's authorized scope).
 */
public fun turnSpansFrom(drain: TraceDrain?, expectedToolSteps: Int): List<TraceSpan> {
    if (drain == null) return emptyList()
    val toolSpans = drain.spans
        .filter { it.name.startsWith("agent.tool.") }
        .sortedBy { it.startUs }
        .takeLast(expectedToolSteps.coerceAtLeast(0))
    val turnSpan = drain.spans.filter { it.name == "agent.turn" }.maxByOrNull { it.startUs }
    return (listOfNotNull(turnSpan) + toolSpans).sortedBy { it.startUs }
}

private const val TOOL_SPAN_PREFIX = "agent.tool."

/** True for a tool-call span (as opposed to the one `agent.turn` span the strip also carries). */
public fun TraceSpan.isToolCall(): Boolean = name.startsWith(TOOL_SPAN_PREFIX)

/**
 * Formats one `agent.tool.<name>` span as `name(args) -> result` for the Foreman tool-call strip — the
 * REAL args/result the engine tagged on this span (`dni.agent.tool_args`/`dni.agent.tool_result`, see
 * `EngineTrace`/`ForemanAgent.cs`), already bounded + truncated engine-side (args <= 256 chars, result
 * <= 512 chars, a visible `"…(truncated)"` suffix when clamped) — not a per-tool hand-tuned summary, so
 * it stays honest and generic across every current and future tool. A failed/unknown tool call still
 * shows its JSON error here (the engine tags the error as the result, never leaves it blank).
 *
 * Falls back to an explicit "not captured" placeholder (never a blank string) for a span drained from an
 * engine build that predates these tags, so an old/new build mismatch reads as missing data, not a bug.
 */
public fun formatToolCall(span: TraceSpan): String {
    val name = span.name.removePrefix(TOOL_SPAN_PREFIX)
    val args = span.toolArgs ?: "(args not captured)"
    val result = span.toolResult ?: "(result not captured)"
    return "$name($args) -> $result"
}
