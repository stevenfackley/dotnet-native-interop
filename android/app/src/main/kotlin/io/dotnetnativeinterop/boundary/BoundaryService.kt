package io.dotnetnativeinterop.boundary

import io.dotnetnativeinterop.model.BoundaryEcho
import io.dotnetnativeinterop.model.BoundaryStreamToken
import io.dotnetnativeinterop.model.BoundaryThrow
import io.dotnetnativeinterop.model.OwnershipEntry
import io.dotnetnativeinterop.model.PhaseTiming
import io.dotnetnativeinterop.transport.FfiTraceListener
import io.dotnetnativeinterop.transport.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

internal val boundaryJson: Json = Json { ignoreUnknownKeys = true }

/** Result of one echo trace: native echo + frontend timing + thread context. */
public data class BoundaryEchoTrace(
    val echo: BoundaryEcho,
    val timing: PhaseTiming,
    val callerThreadId: Long,
)

public interface BoundaryService {
    public suspend fun echo(input: String): BoundaryEchoTrace
    public suspend fun throwDemo(): BoundaryThrow
    public fun stream(prompt: String, maxTokens: Int): Flow<BoundaryStreamToken>
}

/** BoundaryService over the in-process C ABI (Pattern 3 — boundary instrumentation). */
public class FfiBoundaryService : BoundaryService {

    override suspend fun echo(input: String): BoundaryEchoTrace = withContext(Dispatchers.IO) {
        val callerTid = Thread.currentThread().id
        val cStart = System.nanoTime()
        val raw = NativeBridge.nativeFfiEcho(input) ?: error("nativeFfiEcho returned null")
        val callWallUs = (System.nanoTime() - cStart) / 1000.0
        val echo = boundaryJson.decodeFromString<BoundaryEcho>(raw)
        // marshal/free happen inside the JNI shim (not separately observable from Kotlin) — fold into cross;
        // executeUs is native; cross = round-trip - native execute. (Honest split is labelled in the UI.)
        val timing = PhaseTiming(
            crossUs = (callWallUs - echo.executeUs).coerceAtLeast(0.0),
            executeUs = echo.executeUs,
        )
        BoundaryEchoTrace(echo, timing, callerTid)
    }

    override suspend fun throwDemo(): BoundaryThrow = withContext(Dispatchers.IO) {
        val raw = NativeBridge.nativeFfiThrow() ?: error("nativeFfiThrow returned null")
        boundaryJson.decodeFromString(raw)
    }

    override fun stream(prompt: String, maxTokens: Int): Flow<BoundaryStreamToken> = flow {
        val channel = Channel<BoundaryStreamToken>(capacity = 64)
        val listener = object : FfiTraceListener {
            override fun onTrace(index: Int, text: String, managedThreadId: Long, elapsedUs: Long, isFinal: Boolean) {
                channel.trySend(BoundaryStreamToken(index, text, isFinal, managedThreadId, elapsedUs))
                if (isFinal) channel.close()
            }
        }
        val sessionId = withContext(Dispatchers.Default) {
            NativeBridge.nativeFfiStreamStart(prompt, maxTokens, listener)
        }
        if (sessionId <= 0) throw IllegalStateException("nativeFfiStreamStart failed: status $sessionId")
        try {
            for (token in channel) emit(token)
        } finally {
            // Stop via the shared lifecycle exports (no-ops if the stream already finished).
            withContext(Dispatchers.Default) {
                NativeBridge.nativeSessionCancel(sessionId)
                NativeBridge.nativeSessionFree(sessionId)
            }
        }
    }.flowOn(Dispatchers.Default)
}

/** Ownership ledger rows. On Android the JNI shim frees the result string eagerly (take_native_string ->
 *  dni_string_free), so the "leak" is illustrative, not a real leak (unlike iOS where the caller owns it). */
public fun echoLedger(inputBytes: Int, resultBytes: Int): List<OwnershipEntry> = listOf(
    OwnershipEntry("input utf8", "host", "JNI (ReleaseStringUTFChars)", inputBytes, freed = true),
    OwnershipEntry("result json", ".NET", "JNI (dni_string_free)", resultBytes, freed = true),
)

/** Deterministic fake for unit tests + previews. */
public class FakeBoundaryService : BoundaryService {
    override suspend fun echo(input: String): BoundaryEchoTrace {
        val hex = input.encodeToByteArray().joinToString("") { "%02X".format(it) }
        return BoundaryEchoTrace(
            BoundaryEcho(hex, input.encodeToByteArray().size, input, 7L, 4.2, "0x1000"),
            PhaseTiming(crossUs = 2.0, executeUs = 4.2), 1L,
        )
    }
    override suspend fun throwDemo(): BoundaryThrow =
        BoundaryThrow(true, "System.InvalidOperationException", "Boundary demo: contained.", -5)
    override fun stream(prompt: String, maxTokens: Int): Flow<BoundaryStreamToken> = flow {
        repeat(3) { emit(BoundaryStreamToken(it, "tok$it ", false, 9L, it * 800L)) }
        emit(BoundaryStreamToken(3, "", true, 9L, 2400L))
    }
}
