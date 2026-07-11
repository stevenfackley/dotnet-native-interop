import Foundation
import SwiftProtobuf

/// Errors from the framed-protobuf transport. Typed ErrorFrame responses are surfaced (never swallowed
/// into a silent empty state), matching the repo's honesty rules and the Android PbErrorFrameException.
enum PbTransportError: LocalizedError {
    case serverUnavailable(Int32)     // dni_pb_start returned a non-positive status
    case connectFailed(Int32)         // socket/connect errno
    case closedEarly                  // peer closed mid-frame / before responding
    case frameTooLarge(UInt32)        // length prefix exceeds the 16 MiB cap
    case errorFrame(Int32, String)    // a typed ErrorFrame from the server
    case unexpectedBody(String)       // a well-formed response of the wrong oneof case
    case pqUnavailable                // secure channel requested but CryptoKit PQ needs iOS 26+

    var errorDescription: String? {
        switch self {
        case .serverUnavailable(let s): return "framed-protobuf: dni_pb_start returned \(s)"
        case .connectFailed(let e):     return "framed-protobuf: connect failed (errno \(e))"
        case .closedEarly:              return "framed-protobuf: peer closed before responding"
        case .frameTooLarge(let n):     return "framed-protobuf: frame length \(n) exceeds the 16 MiB cap"
        case .errorFrame(let c, let m): return "framed-protobuf error \(c): \(m)"
        case .unexpectedBody(let b):    return "framed-protobuf: unexpected response body (\(b))"
        case .pqUnavailable:            return "framed-protobuf PQ: the secure channel requires iOS 26+ (ML-KEM/ML-DSA)"
        }
    }
}

/// One blocking connection to the framed-protobuf loopback server (127.0.0.1:port). Wire framing is
/// `[u32 little-endian length][Envelope bytes]`, 16 MiB cap — byte-identical to the .NET server and the
/// Android `PbConnection`/`FrameChannel`. One request/response at a time; not thread-safe (call from a
/// detached task). This is the plaintext base transport (`dni_pb_start(0)`); the PQ-encrypted mode
/// (`dni_pb_start(1)`, per-frame AES-256-GCM after an ML-KEM/ML-DSA handshake) is a follow-on that
/// attaches a cipher to the same framing.
final class PbConnection {
    private let fd: Int32
    private static let maxFrame: UInt32 = 16 * 1024 * 1024

    /// Set together after a successful PQ handshake; nil = plaintext channel. Once set, every application
    /// frame's payload is AES-256-GCM encrypted on write and decrypted on read. They stay nil DURING the
    /// handshake so the offer/reply frames themselves are plaintext, exactly as the server expects. These
    /// are plain closures (not the iOS-26-gated `AeadFrameCipher`) so `PbConnection` needs no availability
    /// annotation — the gated cipher is captured inside the `if #available` block in `open`.
    private var encryptFrame: ((Data) throws -> Data)?
    private var decryptFrame: ((Data) throws -> Data)?

    /// The live negotiated params after `open(port:secure:)` with secure=true — feeds the Trust inspector.
    private(set) var pqParams: PqChannelParams?

    private init(fd: Int32) { self.fd = fd }

    /// Connects to `port` on the loopback interface. When `secure`, runs the PQ handshake and attaches the
    /// per-frame cipher before returning, so all subsequent frames are encrypted. Every failure path —
    /// connect, handshake, or key derivation — closes the fd so it can't leak.
    static func open(port: Int32, secure: Bool = false) throws -> PbConnection {
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else { throw PbTransportError.connectFailed(errno) }

        var yes: Int32 = 1
        // Frames are small; disable Nagle so a request isn't batched into a latency-adding delay.
        setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &yes, socklen_t(MemoryLayout<Int32>.size))

        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = in_port_t(UInt16(truncatingIfNeeded: port)).bigEndian   // network byte order
        addr.sin_addr.s_addr = inet_addr("127.0.0.1")

        let rc = withUnsafePointer(to: &addr) { raw in
            raw.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                connect(fd, sa, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard rc == 0 else {
            let e = errno
            Darwin.close(fd)
            throw PbTransportError.connectFailed(e)
        }

        let connection = PbConnection(fd: fd)
        if secure {
            // The PQ handshake needs CryptoKit's ML-KEM/ML-DSA (iOS 26+). On older OS the secure channel
            // is simply unavailable — surfaced honestly rather than silently downgraded to plaintext.
            guard #available(iOS 26.0, *) else {
                connection.close()
                throw PbTransportError.pqUnavailable
            }
            do {
                let result = try PqHandshakeClient.handshake(connection) {
                    DispatchTime.now().uptimeNanoseconds
                }
                let cipher = result.cipher                 // attach AFTER the plaintext handshake
                connection.encryptFrame = { try cipher.encryptOutbound($0) }
                connection.decryptFrame = { try cipher.decryptInbound($0) }
                connection.pqParams = result.params
            } catch {
                connection.close()
                throw error
            }
        }
        return connection
    }

    func close() { Darwin.close(fd) }

    /// Writes one request Envelope as a frame. For a streaming call (rag), write once then call
    /// `readEnvelope()` repeatedly until the terminal chunk. Encrypted when a cipher is attached.
    func writeRequest(_ envelope: Dni_Frame_V1_Envelope) throws {
        var payload = try envelope.serializedData()
        if let encryptFrame { payload = try encryptFrame(payload) }
        try writeFrame(payload)
    }

    /// Reads the next response Envelope, or nil on a clean EOF at a frame boundary (peer closed politely).
    /// Decrypted when a cipher is attached.
    func readEnvelope() throws -> Dni_Frame_V1_Envelope? {
        guard var payload = try readFrame() else { return nil }
        if let decryptFrame { payload = try decryptFrame(payload) }
        return try Dni_Frame_V1_Envelope(serializedBytes: payload)
    }

    /// One request Envelope → one response Envelope.
    func request(_ envelope: Dni_Frame_V1_Envelope) throws -> Dni_Frame_V1_Envelope {
        try writeRequest(envelope)
        guard let response = try readEnvelope() else { throw PbTransportError.closedEarly }
        return response
    }

    // MARK: framing

    private func writeFrame(_ payload: Data) throws {
        var frame = withUnsafeBytes(of: UInt32(payload.count).littleEndian) { Data($0) }
        frame.append(payload)
        try writeAll(frame)
    }

    /// Reads one frame, or nil on a clean EOF exactly at a frame boundary (peer closed politely).
    private func readFrame() throws -> Data? {
        guard let header = try readExactly(4) else { return nil }
        let len = header.withUnsafeBytes { UInt32(littleEndian: $0.load(as: UInt32.self)) }
        guard len <= Self.maxFrame else { throw PbTransportError.frameTooLarge(len) }
        if len == 0 { return Data() }
        guard let body = try readExactly(Int(len)) else { throw PbTransportError.closedEarly }
        return body
    }

    private func writeAll(_ data: Data) throws {
        try data.withUnsafeBytes { raw in
            guard var p = raw.baseAddress else { return }
            var remaining = raw.count
            while remaining > 0 {
                let n = Darwin.write(fd, p, remaining)
                if n <= 0 { throw PbTransportError.closedEarly }
                p = p.advanced(by: n)
                remaining -= n
            }
        }
    }

    /// Reads exactly `n` bytes. Returns nil only if EOF arrives before ANY byte (clean boundary); a
    /// partial read followed by EOF is a truncated frame → `closedEarly`.
    private func readExactly(_ n: Int) throws -> Data? {
        var buf = [UInt8](repeating: 0, count: n)
        var got = 0
        var cleanEOF = false
        try buf.withUnsafeMutableBytes { raw in
            let base = raw.baseAddress!
            while got < n {
                let r = Darwin.read(fd, base.advanced(by: got), n - got)
                if r == 0 { if got == 0 { cleanEOF = true }; if got == 0 { return } else { throw PbTransportError.closedEarly } }
                if r < 0 { throw PbTransportError.closedEarly }
                got += r
            }
        }
        return cleanEOF ? nil : Data(buf)
    }
}
