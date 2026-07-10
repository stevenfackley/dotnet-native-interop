import XCTest
import SwiftProtobuf
@testable import DotnetNativeInteropUnified

/// Wave B Plan B gate — the framed-protobuf transport's Swift codegen (Shared/Pb/dni_frame.pb.swift,
/// generated from proto/dni_frame.proto via protoc-gen-swift) must encode byte-for-byte identically to
/// the .NET (Google.Protobuf) and Kotlin (protobuf-lite) sides. These runtime tests prove the field
/// numbers + framing match the shared wire contract, not just that the types compile.
final class PbWireContractTests: XCTestCase {

    /// Byte-exact: Envelope{ request_id:"abc", ping:{} } must encode as field 1 (LEN "abc") then field 5
    /// (LEN empty) — i.e. tags 0x0A and 0x2A. A field-number drift in the proto would flip these bytes and
    /// silently break cross-platform decoding, so this pins the exact wire.
    func testEnvelopePingEncodesByteExact() throws {
        var e = Dni_Frame_V1_Envelope()
        e.requestID = "abc"
        e.ping = Dni_Frame_V1_PingRequest()
        let bytes = [UInt8](try e.serializedData())
        // 0A 03 'a''b''c'  |  2A 00
        XCTAssertEqual(bytes, [0x0A, 0x03, 0x61, 0x62, 0x63, 0x2A, 0x00])
    }

    /// A response envelope round-trips through serialize → parse with every scalar intact and the oneof
    /// resolving to the right case.
    func testEnvelopeRunResponseRoundTrips() throws {
        var run = Dni_Frame_V1_FeatureRunPb()
        run.id = "ping"; run.result = "pong"; run.elapsedMs = 1.5; run.ok = true
        var resp = Dni_Frame_V1_RunResponse(); resp.run = run
        var e = Dni_Frame_V1_Envelope(); e.requestID = "r1"; e.runResponse = resp

        let parsed = try Dni_Frame_V1_Envelope(serializedBytes: try e.serializedData())
        XCTAssertEqual(parsed.requestID, "r1")
        guard case .runResponse(let rr) = parsed.body else { return XCTFail("expected .runResponse oneof") }
        XCTAssertEqual(rr.run.id, "ping")
        XCTAssertEqual(rr.run.result, "pong")
        XCTAssertEqual(rr.run.elapsedMs, 1.5, accuracy: 1e-9)
        XCTAssertTrue(rr.run.ok)
    }

    /// The PQ handshake offer carries session_id at field 7 (folded into HKDF for replay freshness) and
    /// the algorithm names the Trust inspector surfaces; all survive a round-trip.
    func testHandshakeOfferRoundTrips() throws {
        var offer = Dni_Frame_V1_HandshakeOffer()
        offer.kemPublicKey = Data([1, 2, 3])
        offer.sigPublicKey = Data([4, 5])
        offer.signature = Data([6])
        offer.kemAlgorithm = "ML-KEM-768"
        offer.sigAlgorithm = "ML-DSA-65"
        offer.cipher = "AES-256-GCM"
        offer.sessionID = Data(repeating: 0xAB, count: 32)

        let parsed = try Dni_Frame_V1_HandshakeOffer(serializedBytes: try offer.serializedData())
        XCTAssertEqual(parsed.kemAlgorithm, "ML-KEM-768")
        XCTAssertEqual(parsed.sigAlgorithm, "ML-DSA-65")
        XCTAssertEqual(parsed.cipher, "AES-256-GCM")
        XCTAssertEqual([UInt8](parsed.kemPublicKey), [1, 2, 3])
        XCTAssertEqual(parsed.sessionID.count, 32)
    }
}
