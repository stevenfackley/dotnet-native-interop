package io.dotnetnativeinterop.transport

import kotlinx.coroutines.flow.Flow

/** A single token emitted by the inference engine. */
public data class Token(
    val index: Int,
    val text: String,
    val isFinal: Boolean,
)

/** Request parameters sent to every transport. */
public data class InferRequest(
    val prompt: String,
    val maxTokens: Int = 256,
    val temperature: Float = 0.8f,
)

/**
 * Common streaming interface implemented by each transport client.
 *
 * [stream] returns a cold [Flow] that starts inference when collected and
 * cancels it when the collector is cancelled.  The final [Token] always has
 * [Token.isFinal] == true and an empty [Token.text].
 *
 * Implementations must:
 *  - emit tokens on [kotlinx.coroutines.Dispatchers.Default] or an IO dispatcher;
 *  - complete the flow normally after the final token;
 *  - cancel any in-flight native resource when the coroutine scope is cancelled.
 */
public interface InferenceClient {
    public fun stream(request: InferRequest): Flow<Token>
}
