import XCTest
@testable import DotnetNativeInteropUnified

/// Validates Swift⇄.NET parity using the publisher's `edge-fixtures.json` and the bundled `edge-index.db`.
final class EdgeSearchAgreementTests: XCTestCase {
    private struct Fixtures: Decodable {
        let query: String
        let ids: [Int64]
        let queryVector: [Float]
        let expectedTopChunkId: String
    }

    private func loadFixtures() throws -> Fixtures {
        let path = try XCTUnwrap(Bundle.main.path(forResource: "edge-fixtures", ofType: "json"))
        return try JSONDecoder().decode(Fixtures.self, from: Data(contentsOf: URL(fileURLWithPath: path)))
    }

    private func loadVocab() throws -> [String] {
        let vocabPath = try XCTUnwrap(
            Bundle.main.path(forResource: "vocab", ofType: "txt", inDirectory: "assets")
            ?? Bundle.main.path(forResource: "vocab", ofType: "txt"))
        return try String(contentsOfFile: vocabPath, encoding: .utf8)
            .split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
    }

    func testTokenizerMatchesEngineIds() throws {
        let fx = try loadFixtures()
        let (ids, _) = WordPieceTokenizer(vocabLines: try loadVocab()).encode(fx.query)
        XCTAssertEqual(ids, fx.ids, "Swift tokenizer ids must equal the C# tokenizer ids")
    }

    func testQueryRanksExpectedChunkFirstAndVectorAgrees() throws {
        let fx = try loadFixtures()
        let engine = try EdgeSearchEngine()
        let hits = try engine.search(fx.query, minScore: 0.0, topK: 5)
        XCTAssertEqual(hits.first?.chunk.id, fx.expectedTopChunkId, "ranking must match the publisher")

        // Vector agreement: re-embed the query and compare to the publisher's vector (max abs delta).
        let modelPath = try XCTUnwrap(
            Bundle.main.path(forResource: "model", ofType: "onnx", inDirectory: "assets")
            ?? Bundle.main.path(forResource: "model", ofType: "onnx"))
        let (ids, mask) = WordPieceTokenizer(vocabLines: try loadVocab()).encode(fx.query)
        var v = [Float](repeating: 0, count: 384)
        try EvsOrtSession(modelPath: modelPath).embed(inputIds: ids, attentionMask: mask, length: ids.count, out: &v)
        let maxDelta = zip(v, fx.queryVector).map { abs($0 - $1) }.max() ?? 1
        print("EVS vector max abs delta (Swift/CoreML vs .NET/CPU) = \(maxDelta)")
        XCTAssertLessThan(maxDelta, 0.05, "Core ML embedding should closely match the publisher's")
    }
}
