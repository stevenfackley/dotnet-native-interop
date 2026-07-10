package io.dotnetnativeinterop.trace

import kotlinx.serialization.Serializable

/**
 * One engine-side span from `dni_trace_drain` — an `Activity` flattened for the waterfall. The µs offsets
 * are from engine boot; the client aligns them to its own clock with one offset per drain (see
 * [TraceDrain.nowUs]), accurate to ±the drain round-trip. Client-side spans (marshal/socket/decode) are
 * NOT in this payload; the waterfall labels these engine-side and states the tolerance.
 *
 * [toolArgs]/[toolResult] are populated ONLY on Foreman's `agent.tool.<name>` spans (see
 * `EngineTrace.Record` / `ForemanAgent.cs` on the engine side) — every other span leaves them null. Both
 * are already bounded + truncated engine-side (args <= 256 chars, result <= 512 chars, a visible
 * `"…(truncated)"` suffix when clamped) so a large `search_manuals` result can't blow the drain payload;
 * a failed/unknown tool call still tags [toolResult] with its JSON error, never blank — see
 * `io.dotnetnativeinterop.agent.ForemanScreen`'s tool-call strip, the sole consumer of these two fields.
 */
@Serializable
public data class TraceSpan(
    val name: String,
    val startUs: Double,
    val durUs: Double,
    val requestId: String? = null,
    val status: String? = null,
    val toolArgs: String? = null,
    val toolResult: String? = null,
)

/**
 * The `dni_trace_drain` payload: every span recorded since the previous drain, the engine's µs-since-boot
 * at drain time, the ring capacity, and the count of spans DROPPED to ring overflow since the last drain.
 * Overflow is disclosed here and surfaced in the UI — never silently swallowed.
 */
@Serializable
public data class TraceDrain(
    val nowUs: Double = 0.0,
    val dropped: Long = 0,
    val capacity: Int = 512,
    val spans: List<TraceSpan> = emptyList(),
)
