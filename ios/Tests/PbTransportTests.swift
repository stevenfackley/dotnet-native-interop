import XCTest
@testable import DotnetNativeInteropUnified

/// End-to-end tests for the framed-protobuf transport (the 4th transport): they drive `PbFeatureService`
/// against the REAL native `dni_pb_start(0)` loopback server on the simulator — exercising the socket,
/// the `[u32 LE length][Envelope]` framing, and the SwiftProtobuf codec together, and asserting the pb
/// transport produces byte-identical results to FFI (the transports differ only in how bytes cross).
final class PbTransportTests: XCTestCase {

    func testDescriptorsRoundTripAgainstServer() async throws {
        let descriptors = try await PbFeatureService().descriptors()
        XCTAssertFalse(descriptors.isEmpty, "framed-protobuf transport should return the feature catalog")
    }

    func testRunMatchesFfiForFirstFeature() async throws {
        let ffi = FFIFeatureService()
        let ffiDescriptors = try await ffi.descriptors()
        let firstId = try XCTUnwrap(ffiDescriptors.first).id
        let pbResult = try await PbFeatureService().run(firstId)
        let ffiResult = try await ffi.run(firstId)
        XCTAssertEqual(pbResult.id, ffiResult.id)
        XCTAssertEqual(pbResult.result, ffiResult.result,
                       "framed-protobuf and FFI must produce identical results for '\(firstId)'")
        XCTAssertEqual(pbResult.ok, ffiResult.ok)
    }

    func testPingRunsOverPb() async throws {
        // "ping" is the latency target — it rides the run path on every transport.
        let result = try await PbFeatureService().run("ping")
        XCTAssertEqual(result.id, "ping")
        XCTAssertTrue(result.ok, "ping should succeed over the framed-protobuf transport")
    }
}
