package io.dotnetnativeinterop.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Pattern 3 — FFI + Callback.
 *
 * The NativeAOT C ABI is called directly in-process.  A function pointer
 * ([FfiTokenListener]) is passed to [NativeBridge.nativeSessionStart]; the C
 * shim attaches the .NET background thread to the JVM and delivers tokens via
 * [FfiTokenListener.onToken].
 *
 * Threading:
 *   - [FfiTokenListener.onToken] fires on a .NET background thread.
 *   - We push into a [Channel] from that thread and emit on [Dispatchers.Default].
 *   - The UI collects on [Dispatchers.Main] (enforced by the ViewModel).
 */
public class FfiClient : InferenceClient {

    override fun stream(request: InferRequest): Flow<Token> = flow {
        // Rendezvous channel — provides backpressure: if the Kotlin side is
        // slow to collect, the channel send blocks the .NET callback thread
        // which is exactly what we want (back-pressure into .NET).
        val channel = Channel<Token>(capacity = 64)

        val listener = object : FfiTokenListener {
            override fun onToken(index: Int, text: String, isFinal: Boolean) {
                val token = Token(index, text, isFinal)
                // trySend is non-blocking from the .NET thread; if the buffer
                // is full this drops — tolerable for a 64-slot buffer.
                // For strict backpressure, use a blocking send from a thread
                // that can afford to park.
                val result = channel.trySend(token)
                if (result.isFailure && !isFinal) {
                    // Buffer overflow — close with error so the flow terminates
                    channel.close(IllegalStateException("FFI token buffer overflow"))
                }
                if (isFinal) {
                    channel.close()
                }
            }
        }

        val sessionId = withContext(Dispatchers.Default) {
            NativeBridge.nativeSessionStart(
                request.prompt,
                request.maxTokens,
                request.temperature,
                listener,
            )
        }

        if (sessionId <= 0) {
            throw IllegalStateException("nativeSessionStart failed: status $sessionId")
        }

        try {
            for (token in channel) {
                emit(token)
            }
        } finally {
            // Cancellation path: cancel the session and release the registry slot.
            // These are no-ops if the session already completed normally.
            withContext(Dispatchers.Default) {
                NativeBridge.nativeSessionCancel(sessionId)
                NativeBridge.nativeSessionFree(sessionId)
            }
        }
    }.flowOn(Dispatchers.Default)
}
