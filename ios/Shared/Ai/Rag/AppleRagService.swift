import Foundation
#if canImport(FoundationModels)
import FoundationModels
#endif

/// Apple Foundation Models RAG (the contrast): the SAME retrieved manual chunks are stuffed into a
/// grounded prompt and answered by Apple's on-device model in Swift. Yields CUMULATIVE snapshots.
struct AppleRagService: Sendable {
    /// Yields the whole answer-so-far on each step (cumulative); the view model replaces.
    func answer(to query: String, sources: [SearchResult]) -> AsyncThrowingStream<String, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                #if canImport(FoundationModels)
                if #available(iOS 26.0, *) {
                    do {
                        let context = sources.map { "- \($0.text)" }.joined(separator: "\n")
                        let prompt = """
                        Answer the question using ONLY these maintenance-manual excerpts. \
                        If they don't cover it, say so.

                        \(context)

                        Question: \(query)
                        """
                        let session = LanguageModelSession()
                        for try await partial in session.streamResponse(to: prompt) {
                            continuation.yield(partial.content)
                        }
                        continuation.finish()
                    } catch {
                        continuation.finish(throwing: error)
                    }
                    return
                }
                #endif
                continuation.finish(throwing: RagError.appleUnavailable(
                    "Apple Intelligence requires iOS 26 on an eligible device."))
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    /// Whether Apple's model is usable on this device (for the pane's empty state).
    static func availabilityMessage() -> String? {
        #if canImport(FoundationModels)
        if #available(iOS 26.0, *) {
            switch SystemLanguageModel.default.availability {
            case .available: return nil
            case .unavailable(let reason): return "Apple model unavailable: \(reason)"
            @unknown default: return "Apple model availability unknown."
            }
        } else {
            return "Requires iOS 26 or later."
        }
        #else
        return "FoundationModels isn't available in this build."
        #endif
    }
}
