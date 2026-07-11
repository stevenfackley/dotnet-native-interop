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

    var errorDescription: String? {
        switch self {
        case .serverUnavailable(let s): return "framed-protobuf: dni_pb_start returned \(s)"
        case .connectFailed(let e):     return "framed-protobuf: connect failed (errno \(e))"
        case .closedEarly:              return "framed-protobuf: peer closed before responding"
        case .frameTooLarge(let n):     return "framed-protobuf: frame length \(n) exceeds the 16 MiB cap"
        case .errorFrame(let c, let m): return "framed-protobuf error \(c): \(m)"
        case .unexpectedBody(let b):    return "framed-protobuf: unexpected response body (\(b))"
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

    private init(fd: Int32) { self.fd = fd }

    /// Connects to `port` on the loopback interface. Every failure path closes the fd so it can't leak.
    static func open(port: Int32) throws -> PbConnection {
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
        return PbConnection(fd: fd)
    }

    func close() { Darwin.close(fd) }

    /// Writes one request Envelope as a frame. For a streaming call (rag), write once then call
    /// `readEnvelope()` repeatedly until the terminal chunk.
    func writeRequest(_ envelope: Dni_Frame_V1_Envelope) throws {
        try writeFrame(try envelope.serializedData())
    }

    /// Reads the next response Envelope, or nil on a clean EOF at a frame boundary (peer closed politely).
    func readEnvelope() throws -> Dni_Frame_V1_Envelope? {
        guard let payload = try readFrame() else { return nil }
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
