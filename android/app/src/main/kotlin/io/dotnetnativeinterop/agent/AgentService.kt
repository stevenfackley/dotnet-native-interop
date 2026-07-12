package io.dotnetnativeinterop.agent

import io.dotnetnativeinterop.trace.TraceDrain
import io.dotnetnativeinterop.transport.FfiTokenListener
import io.dotnetnativeinterop.transport.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Streams one Foreman turn: zero or more answer-text fragments, then exactly one terminal status fragment. */
public interface AgentService {
    public fun run(query: String): Flow<AgentFragment>
}

/**
 * Foreman turn over the in-process FFI (`dni_agent_session_start`). Same channel-bridge + backpressure +
 * cleanup pattern as [io.dotnetnativeinterop.ai.FfiRagService] / `transport.FfiClient`, on the agent
 * entrypoint — it shares the SAME `FfiTokenListener` callback ABI and the SAME
 * `nativeSessionCancel`/`nativeSessionFree` lifecycle as every other session (no new lifecycle exports).
 */
public class FfiAgentService : AgentService {
    override fun run(query: String): Flow<AgentFragment> = flow {
        val channel = Channel<AgentFragment>(capacity = 64)

        val listener = object : FfiTokenListener {
            override fun onToken(index: Int, text: String, isFinal: Boolean) {
                if (text.isNotEmpty()) {
                    // parseAgentFragment throws only while JSON-decoding a status fragment (a malformed
                    // terminal marker). Contain it: a throw inside this JNI callback is swallowed by the
                    // shim's ExceptionCheck, so the status would never be applied and the turn would stick
                    // at Streaming forever (a silent-broken spinner, against the repo's DNA). Instead
                    // surface an honest Error status so a bad terminal fragment shows as an errored turn.
                    val fragment = runCatching { parseAgentFragment(text) }.getOrElse {
                        AgentFragment.Status(
                            AgentTurnStatus(AgentStopReason.Error, 0, "unknown (status fragment unparseable)"),
                        )
                    }
                    val result = channel.trySend(fragment)
                    if (result.isFailure && !isFinal) {
                        channel.close(IllegalStateException("Agent fragment buffer overflow"))
                    }
                }
                if (isFinal) {
                    channel.close()
                }
            }
        }

        val sessionId = withContext(Dispatchers.Default) {
            NativeBridge.nativeAgentSessionStart(query, listener)
        }
        if (sessionId <= 0) {
            throw IllegalStateException("nativeAgentSessionStart failed: status $sessionId")
        }

        try {
            for (fragment in channel) {
                emit(fragment)
            }
        } finally {
            // Cancellation path: cancel + free the session (no-ops if it already completed normally).
            // NonCancellable is REQUIRED: this finally runs while the coroutine is already cancelled on the
            // cancel path, and a plain withContext throws at ensureActive() before running the block — so the
            // cleanup would be skipped exactly when needed, leaking the .NET session. See FfiClient's note.
            withContext(NonCancellable + Dispatchers.Default) {
                NativeBridge.nativeSessionCancel(sessionId)
                NativeBridge.nativeSessionFree(sessionId)
            }
        }
    }.flowOn(Dispatchers.Default)
}

/**
 * Reads the engine's span ring for the tool-call strip — the SAME `dni_trace_drain` the Analysis · Trace
 * waterfall reads (see [turnSpansFrom]'s correlation caveat). Abstracted for testability: [AgentViewModel]
 * tests inject a fake that never touches the JNI shim.
 */
public fun interface AgentTraceReader {
    public fun drain(): TraceDrain?
}

/** Real reader: `dni_trace_drain` via [NativeBridge], decoded with the same lenient [Json] as [io.dotnetnativeinterop.trace.TraceViewModel]. */
public class NativeAgentTraceReader : AgentTraceReader {
    private val json = Json { ignoreUnknownKeys = true }

    override fun drain(): TraceDrain? {
        val raw = NativeBridge.nativeTraceDrain() ?: return null
        return runCatching { json.decodeFromString<TraceDrain>(raw) }.getOrNull()
    }
}
