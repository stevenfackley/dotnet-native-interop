package io.dotnetnativeinterop.model

import kotlinx.serialization.Serializable

/** Catalog entry (mirrors iOS FeatureDescriptor). Engine JSON is camelCase. */
@Serializable
public data class FeatureDescriptor(
    val id: String,
    val title: String,
    val version: String,
    val code: String,
    val expected: String,
)

/** One feature's execution result (mirrors iOS FeatureResult). */
@Serializable
public data class FeatureResult(
    val id: String,
    val result: String,
    val elapsedMs: Double,
    val ok: Boolean,
)

/** Live runtime telemetry (mirrors iOS EngineStats / dni_engine_stats). */
@Serializable
public data class EngineStats(
    val gcGen0: Int,
    val gcGen1: Int,
    val gcGen2: Int,
    val heapBytes: Long,
    val committedBytes: Long,
    val allocatedBytes: Long,
    val gcPauseMs: Double,
    val threadCount: Int,
    val processorCount: Int,
    val uptimeMs: Double,
)

/** Per-feature UI state. */
public enum class RunStatus { Idle, Running, Ok, Failed }

/** The three interop transports the catalog can be reached through. Mechanism one-liners mirror
 *  the iOS TransportInfo strings. */
public enum class TransportKind(public val displayName: String, public val mechanism: String) {
    Ffi("FFI", "In-process C ABI — structured JSON over UnmanagedCallersOnly exports."),
    Http("HTTP", "Raw System.Net.Sockets server on 127.0.0.1 — REST + JSON over the loopback."),
    Sqlite("SQLCipher", "Encrypted on-disk SQLite (SQLCipher, PRAGMA key) — data round-trips through ciphertext."),
    ;

    /** Contract id — must equal the pattern ids in patterns.json ("ffi" / "http" / "sqlite"). */
    public val patternId: String get() = name.lowercase()
}

/** dni_ffi_echo result — native-measured; camelCase mirrors the C# record + iOS BoundaryEcho. */
@Serializable
public data class BoundaryEcho(
    val bytesHex: String,
    val len: Int,
    val decoded: String,
    val managedThreadId: Long,
    val executeUs: Double,
    val ptrIn: String,
)

/** dni_ffi_throw result — a managed exception contained at the boundary. */
@Serializable
public data class BoundaryThrow(
    val caught: Boolean,
    val type: String,
    val message: String,
    val status: Int,
)

/** One token from dni_ffi_stream_start's extended callback (NOT JSON — comes via FfiTraceListener). */
public data class BoundaryStreamToken(
    val index: Int,
    val text: String,
    val isFinal: Boolean,
    val managedThreadId: Long,
    val elapsedUs: Long,
)

/** Per-phase µs split. marshal/cross/free are frontend-measured; execute is native (executeUs). */
public data class PhaseTiming(
    val marshalUs: Double = 0.0,
    val crossUs: Double = 0.0,
    val executeUs: Double = 0.0,
    val callbackUs: Double = 0.0,
    val freeUs: Double = 0.0,
) {
    val totalUs: Double get() = marshalUs + crossUs + executeUs + callbackUs + freeUs
}

/** One row of the memory-ownership ledger. */
public data class OwnershipEntry(
    val buffer: String,
    val allocatedBy: String,
    val freedBy: String,
    val bytes: Int,
    val freed: Boolean,
)

public enum class BoundaryPreset(public val title: String) {
    Echo("echo"), Feature("feature"), Pixels("pixels"), Stream("stream"), Exception("throw"),
}

public enum class BoundaryPhase { Marshal, Cross, Execute, Callback, Free }

public enum class BoundaryInspector(public val label: String) {
    Bytes("bytes"), Timing("µs"), Memory("memory"), Threads("threads"), Abi("ABI"), Error("err"),
}
