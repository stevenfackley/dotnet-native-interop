import Foundation

/// raw-HTTP engine RAG: GETs `/rag?q=…` on the loopback server (`dni_http_start`) and parses the
/// `data: {index,text,final}` SSE frames, yielding each token's text until the final marker.
final class HTTPRagService: EngineRagService, @unchecked Sendable {
    func answer(to query: String) -> AsyncThrowingStream<String, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    let port = Int(dni_http_start())
                    guard port > 0 else { throw RagError.startFailed(port) }

                    let encoded = query.addingPercentEncoding(
                        withAllowedCharacters: .urlQueryAllowed) ?? query
                    guard let url = URL(string: "http://127.0.0.1:\(port)/rag?q=\(encoded)") else {
                        throw RagError.badURL
                    }

                    let (bytes, response) = try await URLSession.shared.bytes(from: url)
                    if let code = (response as? HTTPURLResponse)?.statusCode, code != 200 {
                        throw RagError.http(code)
                    }

                    for try await line in bytes.lines {
                        guard line.hasPrefix("data: ") else { continue }
                        let json = Data(line.dropFirst(6).utf8)
                        guard let frame = try? JSONDecoder().decode(RagSSEFrame.self, from: json) else {
                            continue
                        }
                        if frame.final { break }
                        continuation.yield(frame.text)
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}
