package io.dotnetnativeinterop.transport

import dni.frame.v1.Envelope
import io.dotnetnativeinterop.transport.pb.PbEnvelopes
import io.dotnetnativeinterop.transport.pb.PbErrorFrameException
import io.dotnetnativeinterop.transport.pb.PbTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Streaming [InferenceClient] over the framed-protobuf transport. The binary transport's streaming
 * endpoint is grounded RAG (the engine's StreamRagAsync), so this maps a prompt to a RagRequest and
 * relays the RagChunk frames as [Token]s — the last chunk (final=true) becomes the terminal empty token
 * the interface contract requires.
 *
 * Uses a dedicated connection (not the shared request connection) so the stream can emit incrementally
 * without holding the catalog lock; the connection is closed when the flow completes or is cancelled.
 */
public class PbInferenceClient : InferenceClient {

    override fun stream(request: InferRequest): Flow<Token> = flow {
        val connection = PbTransport.openConnection()
        try {
            connection.writeRequest(PbEnvelopes.rag(PbEnvelopes.newRequestId(), request.prompt))
            while (true) {
                val envelope = connection.readEnvelope() ?: break
                when (envelope.bodyCase) {
                    Envelope.BodyCase.RAG_CHUNK -> {
                        val chunk = envelope.ragChunk
                        emit(Token(index = chunk.index, text = chunk.text, isFinal = chunk.final))
                        if (chunk.final) break
                    }
                    Envelope.BodyCase.ERROR ->
                        throw PbErrorFrameException(envelope.error.code, envelope.error.message)
                    else -> error("framed-protobuf: unexpected ${envelope.bodyCase} during rag stream")
                }
            }
        } finally {
            connection.close()
        }
    }.flowOn(Dispatchers.IO)
}
