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
}
