package io.dotnetnativeinterop.transport.pb

import dni.frame.v1.Envelope
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * One live connection to the framed-protobuf loopback server (127.0.0.1:port). Owns the socket + its
 * [FrameChannel]; on a secure connection it runs the PQ handshake in the constructor and attaches the
 * per-frame cipher before any application frame flows (matching the engine's per-connection handshake).
 *
 * Blocking by design (call from an IO dispatcher). Not thread-safe: a single request/response — or a
 * single rag stream — at a time. Independent connections are fully independent (each does its own
 * handshake with fresh AES-GCM counters), so the streaming client can safely hold its own.
 */
internal class PbConnection private constructor(
    private val socket: Socket,
    private val channel: FrameChannel,
    val pqParams: PqChannelParams?,
) : Closeable {

    /** Writes one request Envelope as a frame (encrypted if the channel negotiated a cipher). */
    fun writeRequest(envelope: Envelope) = channel.writeFrame(envelope.toByteArray())

    /** Reads the next Envelope, or null on a clean EOF at a frame boundary. */
    fun readEnvelope(): Envelope? = channel.readFrame()?.let { Envelope.parseFrom(it) }

    /** One request -> one response. Throws if the peer closes before responding. */
    fun request(envelope: Envelope): Envelope {
        writeRequest(envelope)
        return readEnvelope() ?: throw IOException("framed-protobuf peer closed before responding")
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5_000

        /** Connects to [port] on loopback and, when [secure], completes the PQ handshake before returning. */
        fun open(port: Int, secure: Boolean): PbConnection {
            val socket = Socket()
            socket.tcpNoDelay = true // frames are small; disable Nagle so request latency isn't batched
            socket.connect(InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS)
            val channel = FrameChannel(BufferedInputStream(socket.getInputStream()), socket.getOutputStream())
            var params: PqChannelParams? = null
            if (secure) {
                val result = try {
                    PqHandshakeClient.handshake(channel)
                } catch (e: Exception) {
                    runCatching { socket.close() }
                    throw e
                }
                channel.useCipher(result.cipher)
                params = result.params
            }
            return PbConnection(socket, channel, params)
        }
    }
}
