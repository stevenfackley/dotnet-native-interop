package io.dotnetnativeinterop.transport.pb

import io.dotnetnativeinterop.transport.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Process-wide manager for the framed-protobuf loopback server (dni_pb_start / dni_pb_stop) and its
 * client connections. The native server is a singleton whose PQ mode is fixed at start time (Start is
 * idempotent and ignores later flags), so switching plaintext <-> PQ genuinely requires stop + restart —
 * [setSecure] does exactly that, honestly, rather than pretending the mode can change on a live server.
 *
 * Catalog request/response traffic (Features/Compare/Latency) goes through one shared, mutex-serialized
 * connection via [withConnection]. Streaming (rag) opens its own dedicated connection via [openConnection]
 * so token frames can be emitted incrementally without holding the shared lock.
 */
internal object PbTransport {
    private val mutex = Mutex()

    @Volatile
    private var secureMode = false
    private var port = 0
    private var shared: PbConnection? = null

    /** Whether the binary transport is currently in PQ mode (drives the Trust inspector's posture). */
    val isSecure: Boolean get() = secureMode

    /** The last successfully negotiated PQ params, or null when plaintext / not yet handshaked. */
    @Volatile
    var lastParams: PqChannelParams? = null
        private set

    /**
     * Switches the transport between plaintext and PQ. If the mode actually changes this tears down the
     * shared connection and the native server (so the next request restarts it with the new flag). No-op
     * if already in the requested mode.
     */
    suspend fun setSecure(value: Boolean) {
        mutex.withLock {
            if (value == secureMode) return@withLock
            withContext(Dispatchers.IO) {
                closeSharedLocked()
                runCatching { NativeBridge.nativePbStop() }
            }
            port = 0
            secureMode = value
            lastParams = null
        }
    }

    /** Runs [block] against the shared connection (serialized), reconnecting once on an IO error. */
    suspend fun <T> withConnection(block: (PbConnection) -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                block(ensureSharedLocked())
            } catch (e: IOException) {
                closeSharedLocked()
                block(ensureSharedLocked())
            }
        }
    }

    /**
     * Opens a fresh dedicated connection (server started on demand). The caller owns it and MUST close
     * it. Used by the streaming client so a long rag stream does not hold the shared request lock.
     */
    suspend fun openConnection(): PbConnection = mutex.withLock {
        withContext(Dispatchers.IO) {
            val p = ensurePortLocked()
            PbConnection.open(p, secureMode).also { if (secureMode) lastParams = it.pqParams }
        }
    }

    private fun ensureSharedLocked(): PbConnection {
        shared?.let { return it }
        val connection = PbConnection.open(ensurePortLocked(), secureMode)
        if (secureMode) lastParams = connection.pqParams
        shared = connection
        return connection
    }

    private fun ensurePortLocked(): Int {
        if (port > 0) return port
        val bound = NativeBridge.nativePbStart(if (secureMode) 1 else 0)
        require(bound > 0) { "nativePbStart(${if (secureMode) 1 else 0}) failed: status $bound" }
        port = bound
        return bound
    }

    private fun closeSharedLocked() {
        shared?.close()
        shared = null
    }
}
