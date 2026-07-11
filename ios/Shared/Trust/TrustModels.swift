import Foundation

/// Codable mirror of the engine's `trust~posture` JSON (`TrustPosture.ReportJson`, camelCase). Honesty is
/// the whole point: HTTP reports as plaintext loopback, and the binary transport reports plaintext until —
/// and unless — a real ML-KEM/ML-DSA handshake completes, at which point `binaryPqChannel` carries the
/// LIVE negotiated params (published by the server, not a hardcoded label).

/// The security posture of one interop transport, told honestly.
struct TransportPosture: Codable, Identifiable, Sendable {
    let transport: String   // "ffi" | "http" | "sqlcipher" | "binary"
    let inProcess: Bool
    let encrypted: Bool
    let wire: String
    let detail: String

    var id: String { transport }
}

/// Live params of an active framed-protobuf PQ channel (null in the report when plaintext / no channel).
/// A Codable peer of the handshake-side `PqChannelParams` — same fields, but decoded from the server's
/// report rather than produced by the client handshake.
struct TrustPqChannelParams: Codable, Sendable {
    let kem: String
    let sig: String
    let cipher: String
    let kemPublicKeyBytes: Int
    let ciphertextBytes: Int
    let sharedSecretBytes: Int
    let handshakeUs: Double
}

/// Full trust posture: per-transport posture + the live PQ params when the binary channel is up.
struct TrustPostureReport: Codable, Sendable {
    let transports: [TransportPosture]
    let binaryPqChannel: TrustPqChannelParams?
}
