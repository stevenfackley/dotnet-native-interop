import Foundation

/// Streams a grounded answer from the .NET engine over one transport. Each yielded string is a
/// fragment to APPEND to the running answer.
protocol EngineRagService: Sendable {
    func answer(to query: String) -> AsyncThrowingStream<String, Error>
}
