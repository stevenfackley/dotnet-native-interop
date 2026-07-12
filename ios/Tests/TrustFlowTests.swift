import XCTest
@testable import DotnetNativeInteropUnified

/// End-to-end test of the Trust inspector's HONEST PQ flow against the real engine + pb server on the
/// simulator: the binary transport must report PLAINTEXT until a genuine ML-KEM/ML-DSA handshake completes,
/// then ENCRYPTED with the live negotiated params, then PLAINTEXT again when disabled. No fake lock — the
/// posture is read from the engine's `trust~posture`, which only flips after the server publishes params.
@MainActor
final class TrustFlowTests: XCTestCase {
    // Reset the transport mode + singleton server around each test so posture starts from a known plaintext.
    override func setUp() { PbTransport.shared.setSecure(false); PbServer.stop() }
    override func tearDown() { PbTransport.shared.setSecure(false); PbServer.stop() }

    private func binaryPosture(_ vm: TrustViewModel) throws -> TransportPosture {
        try XCTUnwrap(vm.report?.transports.first { $0.transport == "binary" })
    }

    func testPqToggleFlipsPostureHonestly() async throws {
        let vm = TrustViewModel()

        // 1) Plaintext to start — the binary transport must NOT claim encryption before any handshake.
        await vm.refresh()
        XCTAssertNil(vm.errorMessage, "trust~posture should decode")
        XCTAssertFalse(try binaryPosture(vm).encrypted, "binary is PLAINTEXT before a handshake")
        XCTAssertNil(vm.report?.binaryPqChannel)

        // 2) Enable PQ → a real handshake runs → posture flips to encrypted with live params.
        await vm.setPqEnabled(true)
        XCTAssertTrue(vm.pqRequested)
        XCTAssertNil(vm.errorMessage, "the PQ handshake should succeed on iOS 26")
        XCTAssertTrue(try binaryPosture(vm).encrypted, "binary is ENCRYPTED after a real PQ handshake")
        let pq = try XCTUnwrap(vm.report?.binaryPqChannel, "live PQ params must be published")
        XCTAssertEqual(pq.kem, "ML-KEM-768")
        XCTAssertEqual(pq.sig, "ML-DSA-65")
        XCTAssertEqual(pq.cipher, "AES-256-GCM")
        XCTAssertEqual(pq.sharedSecretBytes, 32)

        // 3) Disable → back to honest plaintext (the switch never lies).
        await vm.setPqEnabled(false)
        XCTAssertFalse(vm.pqRequested)
        XCTAssertFalse(try binaryPosture(vm).encrypted, "binary is PLAINTEXT again after disabling PQ")
        XCTAssertNil(vm.report?.binaryPqChannel)
    }

    /// A catalog that returns fixed JSON, or throws when `fail` is set — drives the failure path hermetically.
    private struct FakeFeatureService: FeatureService {
        var json: String = "{}"
        var fail = false
        func descriptors() async throws -> [FeatureDescriptor] { [] }
        func run(_ id: String) async throws -> FeatureResult {
            if fail { throw FeatureServiceError.nullResult }
            return FeatureResult(id: id, result: json, elapsedMs: 0, ok: true)
        }
    }

    /// Regression pin: a FAILED PQ negotiation must revert the switch AND keep its banner. The trailing
    /// posture refresh used to call `refresh()`, whose up-front `errorMessage = nil` silently wiped the
    /// "PQ negotiation failed" banner — the switch flicked back OFF with no explanation. Hermetic (fakes +
    /// a no-op actuator), so it needs no simulator pb server. Parity with Android's TrustViewModelTest.
    func testFailedPqNegotiationStaysDisclosed() async throws {
        let plaintext = """
        {"transports":[{"transport":"binary","inProcess":false,"encrypted":false,"wire":"loopback","detail":"framed protobuf"}],"binaryPqChannel":null}
        """
        // trust~posture (catalog) succeeds; the PQ handshake ping (binaryCatalog) FAILS.
        let vm = TrustViewModel(
            catalog: FakeFeatureService(json: plaintext),
            binaryCatalog: FakeFeatureService(fail: true),
            pqToggle: { _ in }  // no real transport side effect
        )
        await vm.refresh()
        await vm.setPqEnabled(true)

        XCTAssertFalse(vm.pqRequested, "the switch must NOT read ON when the handshake failed")
        let msg = try XCTUnwrap(vm.errorMessage, "the failure must stay disclosed after the trailing refresh")
        XCTAssertTrue(msg.contains("PQ negotiation failed"), "banner names the failure; got: \(msg)")
        XCTAssertFalse(vm.negotiating)
    }
}
