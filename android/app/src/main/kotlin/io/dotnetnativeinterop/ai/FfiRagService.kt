package io.dotnetnativeinterop.ai

import io.dotnetnativeinterop.transport.FfiTokenListener
import io.dotnetnativeinterop.transport.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * RAG answer stream over the in-process FFI (dni_rag_session_start + the FfiTokenListener callback).
 * Same channel-bridge + backpressure + cleanup pattern as transport/FfiClient, on the RAG entrypoint.
 */
public class FfiRagService : RagService {
    override fun answer(query: String): Flow<String> = flow {
        val channel = Channel<String>(capacity = 64)

        val listener = object : FfiTokenListener {
            override fun onToken(index: Int, text: String, isFinal: Boolean) {
                if (text.isNotEmpty()) {
                    val result = channel.trySend(text)
                    if (result.isFailure && !isFinal) {
                        channel.close(IllegalStateException("RAG token buffer overflow"))
                    }
                }
                if (isFinal) {
                    channel.close()
                }
            }
        }

        val sessionId = withContext(Dispatchers.Default) {
            NativeBridge.nativeRagSessionStart(query, 256, 0.8f, listener)
        }
        if (sessionId <= 0) {
            throw IllegalStateException("nativeRagSessionStart failed: status $sessionId")
        }

        try {
            for (fragment in channel) {
                emit(fragment)
            }
        } finally {
            withContext(Dispatchers.Default) {
                NativeBridge.nativeSessionCancel(sessionId)
                NativeBridge.nativeSessionFree(sessionId)
            }
        }
    }.flowOn(Dispatchers.Default)
}
