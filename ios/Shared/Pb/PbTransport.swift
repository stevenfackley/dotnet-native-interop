import Foundation

/// Global mode + connection factory for the framed-protobuf transport. The binary transport is either
/// PLAINTEXT (`dni_pb_start(0)`) or PQ-SECURE (`dni_pb_start(1)` + a per-connection ML-KEM-768 / ML-DSA-65
/// handshake, then per-frame AES-256-GCM). The Trust inspector flips the mode for the WHOLE transport —
/// Compare/Latency binary runs go encrypted too, honestly, not just a Trust-screen demo. The pb server's
/// mode is fixed at first start, so switching restarts the singleton server. Thread-safe via a lock;
/// `PbFeatureService`/`PbRagService` open every connection through here so they always honor the mode.
/// `@unchecked Sendable`: the mutable `secure` flag is guarded by `lock`, so the shared singleton is safe
/// to touch from the detached tasks the services run on (the compiler can't prove the manual locking).
final class PbTransport: @unchecked Sendable {
    static let shared = PbTransport()

    private let lock = NSLock()
    private var secure = false

    private init() {}

    var isSecure: Bool {
        lock.lock(); defer { lock.unlock() }
        return secure
    }

    /// Switches plaintext ⇄ PQ. Restarts the singleton pb server so the new mode takes effect (its PQ mode
    /// is fixed at first start — honest, not hot-swapped). A no-op when already in the requested mode.
    func setSecure(_ enabled: Bool) {
        lock.lock(); defer { lock.unlock() }
        guard secure != enabled else { return }
        PbServer.stop()
        secure = enabled
    }

    /// Opens a connection in the current mode, starting the server if needed. Runs the PQ handshake when
    /// secure (so this can throw a handshake error / `pqUnavailable` on older OS).
    func open() throws -> PbConnection {
        lock.lock(); let wantSecure = secure; lock.unlock()
        let port = PbServer.start(requirePq: wantSecure)
        guard port > 0 else { throw PbTransportError.serverUnavailable(port) }
        return try PbConnection.open(port: port, secure: wantSecure)
    }
}
