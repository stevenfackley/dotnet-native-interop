import XCTest
@testable import DotnetNativeInteropUnified

/// End-to-end tests for the framed-protobuf POST-QUANTUM secure channel: they open a secure connection to
/// the REAL native `dni_pb_start(1)` server on the simulator, which forces a per-connection ML-KEM-768 /
/// ML-DSA-65 handshake, after which every frame is AES-256-GCM encrypted. The mere fact that an encrypted
/// features/run round-trip DECODES proves CryptoKit (client) and the engine's BouncyCastle provider
/// (server) derived byte-identical directional keys from the same shared secret — i.e. genuine
/// cross-implementation PQ interop, not two copies of one library agreeing with themselves.
final class PbSecureChannelTests: XCTestCase {

    // The pb server is a process-wide singleton whose PQ mode is fixed at first start (dni_pb_start is
    // idempotent; _requirePq is set once). Stop it around every secure test so we always get a FRESH
    // secure server regardless of whether a plaintext pb test ran first — and leave it stopped so a later
    // plaintext test's dni_pb_start(0) rebinds a fresh plaintext server. Makes both suites order-independent.
    override func setUp() { PbServer.stop() }
    override func tearDown() { PbServer.stop() }

    /// Opens the secure channel and asserts the negotiated params are the FIPS-final sets the wire
    /// advertises — and that the handshake actually happened (non-empty key/ciphertext sizes).
    func testHandshakeNegotiatesPqParams() throws {
        let port = PbServer.start(requirePq: true)                       // flags 1 = require the PQ handshake
        XCTAssertGreaterThan(port, 0, "PbServer.start(requirePq: true) should bind the secure pb server")
        let conn = try PbConnection.open(port: port, secure: true)
        defer { conn.close() }

        let params = try XCTUnwrap(conn.pqParams, "a secure connection must expose live PQ params")
        XCTAssertEqual(params.kem, "ML-KEM-768")
        XCTAssertEqual(params.sig, "ML-DSA-65")
        XCTAssertEqual(params.cipher, "AES-256-GCM")
        XCTAssertGreaterThan(params.kemPublicKeyBytes, 0)
        XCTAssertGreaterThan(params.ciphertextBytes, 0)
        XCTAssertEqual(params.sharedSecretBytes, 32, "ML-KEM-768 shared secret is 32 bytes")
    }

    /// The proof of interop: an ENCRYPTED features round-trip over the secure channel returns the catalog.
    /// If CryptoKit's HKDF/AES-GCM keys didn't match the engine's, the first frame would fail the GCM tag.
    func testEncryptedFeaturesRoundTrip() throws {
        let port = PbServer.start(requirePq: true)
        XCTAssertGreaterThan(port, 0)
        let conn = try PbConnection.open(port: port, secure: true)
        defer { conn.close() }

        let response = try conn.request(PbEnvelopes.features(PbEnvelopes.newRequestId()))
        guard case .featuresResponse(let fr)? = response.body else {
            return XCTFail("expected an encrypted FEATURES_RESPONSE, got \(String(describing: response.body))")
        }
        XCTAssertFalse(fr.features.isEmpty, "the encrypted channel should return the feature catalog")
    }

    /// A second request on the SAME connection exercises the monotonic per-direction frame counters (nonce
    /// = counter): if the client and server counters drifted, the 2nd frame's nonce would mismatch and the
    /// GCM open would fail. Two successful requests prove the counters stay in lock-step.
    func testMultipleFramesKeepCountersInLockstep() throws {
        let port = PbServer.start(requirePq: true)
        XCTAssertGreaterThan(port, 0)
        let conn = try PbConnection.open(port: port, secure: true)
        defer { conn.close() }

        let ping = try conn.request(PbEnvelopes.run(PbEnvelopes.newRequestId(), "ping"))
        guard case .runResponse(let r1)? = ping.body else { return XCTFail("frame 1: expected runResponse") }
        XCTAssertTrue(r1.run.ok)

        let ping2 = try conn.request(PbEnvelopes.run(PbEnvelopes.newRequestId(), "ping"))
        guard case .runResponse(let r2)? = ping2.body else { return XCTFail("frame 2: expected runResponse") }
        XCTAssertTrue(r2.run.ok, "the 2nd encrypted frame must decrypt with the advanced counter/nonce")
    }
}
