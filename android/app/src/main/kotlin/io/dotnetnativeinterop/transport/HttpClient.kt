package io.dotnetnativeinterop.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Pattern 1 — HTTP Loopback (raw sockets + SSE).
 *
 * Lifecycle:
 *   - [NativeBridge.nativeHttpStart] binds a raw-socket HTTP server to 127.0.0.1:0 and returns the port.
 *   - [NativeBridge.nativeHttpStop] is called in [stop].
 *   - The caller is responsible for managing [start]/[stop] around the app lifecycle
 *     (the listener dies when the Android process is backgrounded/killed).
 */
public class HttpClient : InferenceClient {

    private val json = Json { ignoreUnknownKeys = true }

    private val okhttp = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private var port: Int = 0

    /** Must be called before [stream]. Returns the bound port. */
    public suspend fun start(): Int = withContext(Dispatchers.IO) {
        val p = NativeBridge.nativeHttpStart()
        if (p <= 0) throw IllegalStateException("nativeHttpStart failed: status $p")
        port = p
        p
    }

    /** Stops the raw-socket listener. */
    public suspend fun stop(): Unit = withContext(Dispatchers.IO) {
        NativeBridge.nativeHttpStop()
    }

    override fun stream(request: InferRequest): Flow<Token> = callbackFlow {
        if (port <= 0) throw IllegalStateException("HttpClient not started — call start() first")

        val body = json.encodeToString(
            HttpInferRequest.serializer(),
            HttpInferRequest(request.prompt, request.maxTokens, request.temperature),
        ).toRequestBody("application/json".toMediaType())

        val httpRequest = Request.Builder()
            .url("http://127.0.0.1:$port/v1/infer")
            .post(body)
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            // Correlation id for the Trace waterfall (read + span-tagged by the raw-HTTP server).
            .header("X-Dni-Request-Id", UUID.randomUUID().toString())
            .build()

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String,
            ) {
                runCatching {
                    val sseToken = json.decodeFromString(SseToken.serializer(), data)
                    val token = Token(sseToken.index, sseToken.text, sseToken.final)
                    // trySendBlocking applies backpressure — it blocks THIS OkHttp SSE reader thread until
                    // the collector frees a buffer slot, so a fast loopback server can't outrun a slow UI
                    // and silently drop tokens. (A non-blocking trySend into a bounded channel would drop
                    // on overflow, and dropping the FINAL token would end the stream looking complete —
                    // a truncated transcript with no error.) Returns a closed result, not a throw, once the
                    // collector cancels; the trailing close()/onClosed then tears down cleanly.
                    trySendBlocking(token)
                    if (sseToken.final) close()
                }.onFailure { close(it) }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                close(t ?: IllegalStateException("SSE connection failed: ${response?.code}"))
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        }

        val eventSource = EventSources.createFactory(okhttp)
            .newEventSource(httpRequest, listener)

        // Cancel the SSE call when the collector completes or is cancelled (unblocks the reader thread).
        awaitClose { eventSource.cancel() }
    }.flowOn(Dispatchers.IO)

    @Serializable
    private data class HttpInferRequest(
        val prompt: String,
        @SerialName("maxTokens") val maxTokens: Int,
        val temperature: Float,
    )

    @Serializable
    private data class SseToken(
        val index: Int,
        val text: String,
        @SerialName("final") val final: Boolean,
    )
}
