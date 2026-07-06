package io.dotnetnativeinterop.transport.pb

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Length-prefixed frame I/O — the client mirror of the engine's `FrameConnection`. Wire framing is
 * `[u32 little-endian length][payload]`, where the payload is a serialized `Envelope` on the plain
 * channel; once a PQ handshake completes the caller attaches (via [useCipher]) an [AeadFrameCipher] and
 * the payload becomes AES-256-GCM `ciphertext || tag`. The length framing is unchanged either way.
 *
 * Operates on raw streams (a socket's, or piped streams under test) so the framing is unit-testable
 * without a real socket. Not thread-safe: one request/response exchange at a time (the transport
 * serializes access with a mutex).
 */
internal class FrameChannel(
    private val input: InputStream,
    private val output: OutputStream,
) {
    private var cipher: AeadFrameCipher? = null

    /** True once [useCipher] has been called (subsequent frames are encrypted). */
    val encrypted: Boolean get() = cipher != null

    /** Attaches the AEAD cipher established by the PQ handshake; all later frames are encrypted. */
    fun useCipher(c: AeadFrameCipher) { cipher = c }

    /** Writes [plaintext] as one frame, encrypting first when a cipher is attached. */
    fun writeFrame(plaintext: ByteArray) {
        val payload = cipher?.encryptOutbound(plaintext) ?: plaintext
        val prefix = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.size).array()
        output.write(prefix)
        output.write(payload)
        output.flush()
    }

    /**
     * Reads one frame and returns its plaintext payload, or null on a clean EOF at a frame boundary. On
     * an encrypted channel a tampered/forged frame surfaces as an `AEADBadTagException` (never a silently
     * accepted or dropped frame).
     */
    fun readFrame(): ByteArray? {
        val prefix = readExactly(4) ?: return null
        val length = ByteBuffer.wrap(prefix).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
        if (length == 0L || length > MAX_FRAME_BYTES) {
            throw IOException("Framed-protobuf length out of range: $length")
        }
        val payload = readExactly(length.toInt())
            ?: throw EOFException("truncated frame: expected $length payload bytes")
        return cipher?.decryptInbound(payload) ?: payload
    }

    // Reads exactly [n] bytes, or null if EOF is hit (mirrors the engine's ReadExactAsync returning
    // false -> null). readFrame turns a null length prefix into a clean EOF and a null payload into a
    // truncated-frame error, so a dropped connection is never mistaken for a valid empty frame.
    private fun readExactly(n: Int): ByteArray? {
        val buffer = ByteArray(n)
        var read = 0
        while (read < n) {
            val count = input.read(buffer, read, n - read)
            if (count < 0) return null
            read += count
        }
        return buffer
    }

    companion object {
        // Cap a single frame so a malformed/hostile length prefix can't drive an unbounded allocation
        // (matches the engine's FrameConnection.MaxFrameBytes).
        const val MAX_FRAME_BYTES = 16 * 1024 * 1024
    }
}
