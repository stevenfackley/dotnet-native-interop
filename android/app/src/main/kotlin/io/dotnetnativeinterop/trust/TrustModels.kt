package io.dotnetnativeinterop.trust

import kotlinx.serialization.Serializable

/**
 * The `trust~posture` payload — the honest per-transport security posture the engine reports (camelCase,
 * mirroring the engine's `TrustPostureReport` / `TransportPosture` / `PqChannelParams` records). Honesty
 * is the whole point: HTTP reports as plaintext loopback, and the binary transport reports plaintext
 * until (and unless) a PQ handshake actually completes — at which point [binaryPqChannel] carries the
 * LIVE negotiated params, not a hardcoded label.
 */
@Serializable
public data class TransportPosture(
    val transport: String,   // "ffi" | "http" | "sqlcipher" | "binary"
    val inProcess: Boolean,
    val encrypted: Boolean,
    val wire: String,
    val detail: String,
)

/** Live params of an active framed-protobuf PQ channel (null in the report when plaintext / no channel). */
@Serializable
public data class PqChannelParams(
    val kem: String,
    val sig: String,
    val cipher: String,
    val kemPublicKeyBytes: Int,
    val ciphertextBytes: Int,
    val sharedSecretBytes: Int,
    val handshakeUs: Double,
)

/** Full trust posture: per-transport posture + the live PQ params when the binary channel is up. */
@Serializable
public data class TrustPostureReport(
    val transports: List<TransportPosture> = emptyList(),
    val binaryPqChannel: PqChannelParams? = null,
)
