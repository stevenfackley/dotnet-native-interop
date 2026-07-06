package io.dotnetnativeinterop.trace

import kotlinx.serialization.Serializable

/**
 * One engine-side span from `dni_trace_drain` — an `Activity` flattened for the waterfall. The µs offsets
 * are from engine boot; the client aligns them to its own clock with one offset per drain (see
 * [TraceDrain.nowUs]), accurate to ±the drain round-trip. Client-side spans (marshal/socket/decode) are
 * NOT in this payload; the waterfall labels these engine-side and states the tolerance.
 */
@Serializable
public data class TraceSpan(
    val name: String,
    val startUs: Double,
    val durUs: Double,
    val requestId: String? = null,
    val status: String? = null,
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
