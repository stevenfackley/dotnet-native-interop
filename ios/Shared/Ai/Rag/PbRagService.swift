import Foundation

/// EngineRagService over the framed-protobuf transport: sends one RagRequest Envelope and streams the
/// RagChunk frames the engine emits until `final`, yielding each chunk's text to APPEND — the pb-socket
/// analog of HTTPRagService's SSE stream. Plaintext base transport (dni_pb_start(0)); the PQ-encrypted
/// mode attaches transparently to the same framing.
final class PbRagService: EngineRagService, @unchecked Sendable {
    func answer(to query: String) -> AsyncThrowingStream<String, Error> {
        AsyncThrowingStream { continuation in
            let task = Task.detached(priority: .userInitiated) {
                var connection: PbConnection?
                do {
                    // Opens in the transport's current mode (plaintext or PQ-secure — Trust owns the switch).
                    let conn = try PbTransport.shared.open()
                    connection = conn

                    var envelope = Dni_Frame_V1_Envelope()
                    envelope.requestID = PbEnvelopes.newRequestId()
                    var rag = Dni_Frame_V1_RagRequest()
                    rag.query = query
                    envelope.rag = rag
                    try conn.writeRequest(envelope)

                    reading: while !Task.isCancelled {
                        guard let response = try conn.readEnvelope() else { break reading }  // clean EOF
                        switch response.body {
                        case .ragChunk(let chunk)?:
                            if chunk.final { break reading }
                            continuation.yield(chunk.text)
                        case .error(let error)?:
                            throw PbTransportError.errorFrame(error.code, error.message)
                        default:
                            throw PbTransportError.unexpectedBody("rag: expected ragChunk")
                        }
                    }
                    conn.close()
                    continuation.finish()
                } catch {
                    connection?.close()
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}
