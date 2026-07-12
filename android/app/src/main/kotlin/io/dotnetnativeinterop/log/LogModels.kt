package io.dotnetnativeinterop.log

import kotlinx.serialization.Serializable

/**
 * One engine log record from `dni_log_drain` — the logging leg of the observability trio (alongside the
 * Trace waterfall's `dni_trace_drain`). [timestampUs] is µs from engine boot, on the SAME clock as trace
 * spans, so a record aligns to the same timeline. [exception] is "TypeName: message" when the record
 * carried one (the detail the FFI boundary used to swallow silently) — null otherwise. Level is
 * Information and above (the engine drops Trace/Debug at the logger).
 */
@Serializable
public data class LogRecord(
    val level: String,
    val category: String,
    val message: String,
    val timestampUs: Double,
    val requestId: String? = null,
    val exception: String? = null,
)

/**
 * The `dni_log_drain` payload: every record captured since the previous drain, the engine's µs-since-boot
 * at drain time, the ring capacity, and the count DROPPED to ring overflow since the last drain. Overflow
 * is disclosed here and surfaced in the UI — never silently swallowed (mirrors [io.dotnetnativeinterop.trace.TraceDrain]).
 */
@Serializable
public data class LogDrain(
    val nowUs: Double = 0.0,
    val dropped: Long = 0,
    val capacity: Int = 256,
    val records: List<LogRecord> = emptyList(),
)

/** Severity rank for filtering/coloring — Information(0) < Warning(1) < Error(2) < Critical(3). An
 *  unknown level ranks as 0 (shown under "all", never hidden by a severity filter). */
public fun levelRank(level: String): Int = when (level) {
    "Critical" -> 3
    "Error" -> 2
    "Warning" -> 1
    else -> 0
}

/** The Log segment's severity filter (parity with the Trace waterfall's request filter). */
public enum class LogFilter(public val label: String, public val minRank: Int) {
    All("all", 0),
    WarnPlus("warn+", 1),
    ErrorsOnly("errors", 2),
}
