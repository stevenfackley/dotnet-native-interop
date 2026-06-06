import Foundation

/// FFI engine RAG: calls `dni_rag_session_start` and bridges the per-token C callback into an async
/// stream. The continuation is carried across the C ABI inside an Unmanaged box passed as user_data.
/// The session is cancelled+freed on stream termination (never inside the callback, which runs on the
/// .NET drain thread — `dni_session_free` blocks on that pump and would deadlock).
final class FFIRagService: EngineRagService, @unchecked Sendable {
    private final class Box {
        let continuation: AsyncThrowingStream<String, Error>.Continuation
        init(_ c: AsyncThrowingStream<String, Error>.Continuation) { self.continuation = c }
    }

    func answer(to query: String) -> AsyncThrowingStream<String, Error> {
        AsyncThrowingStream { continuation in
            let box = Box(continuation)
            let userData = Unmanaged.passRetained(box).toOpaque()

            let callback: @convention(c) (UnsafeMutableRawPointer?, Int32, UnsafePointer<CChar>?, Int32) -> Void = { ud, _, text, isFinal in
                guard let ud else { return }
                let box = Unmanaged<Box>.fromOpaque(ud).takeUnretainedValue()
                if isFinal != 0 {
                    box.continuation.finish()
                } else if let text {
                    box.continuation.yield(String(cString: text))
                }
            }

            let sessionId = query.withCString { q in
                dni_rag_session_start(q, 256, 0.8, callback, userData)
            }

            guard sessionId > 0 else {
                continuation.finish(throwing: RagError.startFailed(Int(sessionId)))
                Unmanaged<Box>.fromOpaque(userData).release()
                return
            }

            continuation.onTermination = { _ in
                _ = dni_session_cancel(sessionId)
                _ = dni_session_free(sessionId)
                Unmanaged<Box>.fromOpaque(userData).release()
            }
        }
    }
}
