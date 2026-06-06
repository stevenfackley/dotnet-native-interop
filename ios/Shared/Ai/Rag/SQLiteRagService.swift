import Foundation

/// SQLCipher engine RAG: calls `dni_sqlite_rag`, which round-trips the grounded answer through a
/// key-encrypted db and returns JSON {answer}. Non-streaming — yields the whole answer once.
final class SQLiteRagService: EngineRagService, @unchecked Sendable {
    func answer(to query: String) -> AsyncThrowingStream<String, Error> {
        AsyncThrowingStream { continuation in
            let task = Task.detached(priority: .userInitiated) {
                let json: String? = query.withCString { q -> String? in
                    guard let ptr = dni_sqlite_rag(q) else { return nil }
                    defer { dni_string_free(ptr) }
                    return String(cString: ptr)
                }
                guard let json,
                      let result = try? JSONDecoder().decode(RagAnswer.self, from: Data(json.utf8))
                else {
                    continuation.finish(throwing: RagError.nullResult)
                    return
                }
                continuation.yield(result.answer)
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}
