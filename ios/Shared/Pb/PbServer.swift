import Foundation

/// Thin wrapper over the framed-protobuf server's C-ABI exports. Lives in the app target (which links
/// dni.xcframework), so callers that don't themselves link the framework — notably the test target via
/// `@testable import` — reach `dni_pb_start` / `dni_pb_stop` through here instead of the raw symbols.
enum PbServer {
    /// Starts the loopback pb server (idempotent) and returns its bound 127.0.0.1 port (> 0) or a negative
    /// status. `requirePq` fixes the server into PQ mode (an ML-KEM-768 / ML-DSA-65 handshake per
    /// connection); it is honored only on the FIRST start, so `stop()` first to switch plaintext ⇄ PQ.
    @discardableResult
    static func start(requirePq: Bool = false) -> Int32 { dni_pb_start(requirePq ? 1 : 0) }

    /// Stops the server so the next `start` can rebind in a different mode. Idempotent.
    static func stop() { dni_pb_stop() }
}
