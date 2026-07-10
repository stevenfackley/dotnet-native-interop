import Foundation

/// FeatureService over the framed-protobuf loopback transport (the 4th transport) — parallel to
/// FFI/HTTP/SQLite, so the shared, transport-agnostic UI can drive it through the same seam. Structured
/// binary RPC: length-prefixed Google.Protobuf frames over a 127.0.0.1 socket, no ASP.NET/gRPC runtime.
/// Starts the server idempotently (`dni_pb_start(0)`) for its port and opens a fresh connection per
/// request (stateless, like HTTPFeatureService). Plaintext base transport; the PQ-encrypted mode is next.
struct PbFeatureService: FeatureService {

    func descriptors() async throws -> [FeatureDescriptor] {
        try await roundTrip { conn in
            let resp = try conn.request(PbEnvelopes.features(PbEnvelopes.newRequestId()))
            switch resp.body {
            case .featuresResponse(let fr)?: return fr.features.map { $0.toModel() }
            case .error(let ef)?:            throw PbTransportError.errorFrame(ef.code, ef.message)
            default:                         throw PbTransportError.unexpectedBody(bodyLabel(resp.body))
            }
        }
    }

    func run(_ id: String) async throws -> FeatureResult {
        try await roundTrip { conn in
            let resp = try conn.request(PbEnvelopes.run(PbEnvelopes.newRequestId(), id))
            switch resp.body {
            case .runResponse(let rr)?: return rr.run.toModel()
            case .error(let ef)?:       throw PbTransportError.errorFrame(ef.code, ef.message)
            default:                    throw PbTransportError.unexpectedBody(bodyLabel(resp.body))
            }
        }
    }

    /// Starts the pb server (idempotent), opens a connection on its port, runs `work`, and always closes.
    private func roundTrip<T: Sendable>(
        _ work: @escaping @Sendable (PbConnection) throws -> T
    ) async throws -> T {
        try await Task.detached(priority: .userInitiated) {
            let port = dni_pb_start(0)                       // flags 0 = plaintext (no PQ handshake)
            guard port > 0 else { throw PbTransportError.serverUnavailable(port) }
            let conn = try PbConnection.open(port: port)
            defer { conn.close() }
            return try work(conn)
        }.value
    }

    private func bodyLabel(_ body: Dni_Frame_V1_Envelope.OneOf_Body?) -> String {
        guard let body else { return "empty" }
        switch body {
        case .features:         return "features"
        case .run:              return "run"
        case .rag:              return "rag"
        case .ping:             return "ping"
        case .bench:            return "bench"
        case .featuresResponse: return "featuresResponse"
        case .runResponse:      return "runResponse"
        case .ragChunk:         return "ragChunk"
        case .pingResponse:     return "pingResponse"
        case .error:            return "error"
        case .handshakeOffer:   return "handshakeOffer"
        case .handshakeReply:   return "handshakeReply"
        }
    }
}

extension TransportInfo {
    static let binary = TransportInfo(
        id: "binary",
        displayName: "Framed protobuf",
        mechanism: "Length-prefixed Google.Protobuf frames over a 127.0.0.1 socket — structured binary RPC.",
        summary: "A hand-rolled framed-protobuf server (no ASP.NET, no gRPC runtime) runs inside the "
            + "library on a loopback port. Requests/responses are Protobuf Envelopes with a u32 length "
            + "prefix — compact binary on the wire, and an opt-in ML-KEM/ML-DSA post-quantum channel.",
        features: [
            "Compact binary frames — no HTTP/JSON text overhead",
            "One shared .proto contract across .NET / Kotlin / Swift",
            "Opt-in post-quantum AES-256-GCM secure channel",
        ],
        limitations: [
            "Length-prefixed framing must be parsed by hand",
            "Dynamic port handshake on each start",
            "iOS suspends the listener when backgrounded",
        ])
}
