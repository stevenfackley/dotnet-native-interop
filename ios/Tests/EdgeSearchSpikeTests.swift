import XCTest

/// GATE (Task 1): proves the vendored ONNX Runtime xcframework links and the Core ML EP runs one
/// inference of the bundled all-MiniLM model on device/simulator. Uses hardcoded ids ([CLS] hello world
/// [SEP]); the real tokenizer/agreement checks come later (EdgeSearchAgreementTests).
final class EdgeSearchSpikeTests: XCTestCase {
    func testOrtCoreMLLinksAndRunsOneInference() throws {
        let modelPath = try XCTUnwrap(
            Bundle.main.path(forResource: "model", ofType: "onnx", inDirectory: "assets")
            ?? Bundle.main.path(forResource: "model", ofType: "onnx"),
            "model.onnx must be bundled (Phase 3 assets)")
        let session = try EvsOrtSession(modelPath: modelPath)

        var out = [Float](repeating: 0, count: 384)
        let ids: [Int64] = [101, 7592, 2088, 102]      // [CLS] hello world [SEP]
        let mask: [Int64] = [1, 1, 1, 1]
        try session.embed(inputIds: ids, attentionMask: mask, length: ids.count, out: &out)

        let norm = sqrt(out.reduce(0) { $0 + $1 * $1 })
        XCTAssertEqual(norm, 1.0, accuracy: 1e-3, "embedding should be L2-normalized")
        XCTAssertTrue(out.contains { $0 != 0 }, "embedding must be non-zero")
    }
}
