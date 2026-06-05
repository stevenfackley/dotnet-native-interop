import Foundation
import SQLite3
import Accelerate

/// Fully on-device edge search: embeds the query with ONNX Runtime + Core ML (via EvsOrtSession) and
/// cosine-ranks it against the prebuilt SQLite vector index. No engine/FFI, no network.
///
/// `@unchecked Sendable`: the ORT session isn't thread-safe, but the view model serializes calls (one
/// in-flight search, guarded by its `searching` flag), so it's safe to hop actors via Task.detached.
final class EdgeSearchEngine: @unchecked Sendable {
    private let ort: EvsOrtSession
    private let tokenizer: WordPieceTokenizer
    private let indexed: [(chunk: EdgeChunk, embedding: [Float])]

    var allErrorCodes: [String] { Array(Set(indexed.flatMap { $0.chunk.errorCodes })) }
    var allTools: [String] { Array(Set(indexed.flatMap { $0.chunk.toolsRequired })) }

    /// Loads the bundled model (Core ML EP), vocab, and the compiled index. Throws on any missing asset.
    init() throws {
        guard let modelPath = Bundle.main.path(forResource: "model", ofType: "onnx", inDirectory: "assets")
            ?? Bundle.main.path(forResource: "model", ofType: "onnx") else {
            throw EdgeSearchError.assetMissing("model.onnx")
        }
        guard let vocabPath = Bundle.main.path(forResource: "vocab", ofType: "txt", inDirectory: "assets")
            ?? Bundle.main.path(forResource: "vocab", ofType: "txt") else {
            throw EdgeSearchError.assetMissing("vocab.txt")
        }
        guard let dbPath = Bundle.main.path(forResource: "edge-index", ofType: "db") else {
            throw EdgeSearchError.assetMissing("edge-index.db")
        }
        let vocabLines = try String(contentsOfFile: vocabPath, encoding: .utf8)
            .split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
        tokenizer = WordPieceTokenizer(vocabLines: vocabLines)
        ort = try EvsOrtSession(modelPath: modelPath)
        indexed = try EdgeSearchEngine.loadIndex(dbPath: dbPath)
    }

    /// Embeds `query`, returns chunks with cosine (== dot, vectors are normalized) ≥ `minScore`,
    /// sorted desc, capped at `topK`.
    func search(_ query: String, minScore: Float = 0.70, topK: Int = 20) throws -> [EdgeSearchHit] {
        let (ids, mask) = tokenizer.encode(query)
        var q = [Float](repeating: 0, count: 384)
        try ort.embed(inputIds: ids, attentionMask: mask, length: ids.count, out: &q)

        var hits: [EdgeSearchHit] = []
        for item in indexed {
            var score: Float = 0
            vDSP_dotpr(q, 1, item.embedding, 1, &score, 384)
            if score >= minScore { hits.append(EdgeSearchHit(chunk: item.chunk, score: score)) }
        }
        hits.sort { $0.score > $1.score }
        return Array(hits.prefix(topK))
    }

    // Reads chunks + embeddings via the SQLite C API.
    private static func loadIndex(dbPath: String) throws -> [(chunk: EdgeChunk, embedding: [Float])] {
        var db: OpaquePointer?
        guard sqlite3_open_v2(dbPath, &db, SQLITE_OPEN_READONLY, nil) == SQLITE_OK, let db else {
            throw EdgeSearchError.dbOpenFailed
        }
        defer { sqlite3_close(db) }

        var stmt: OpaquePointer?
        let sql = "SELECT ChunkId, DocumentId, SectionTitle, ContentText, Embedding, Metadata FROM Chunks"
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { throw EdgeSearchError.dbOpenFailed }
        defer { sqlite3_finalize(stmt) }

        var result: [(chunk: EdgeChunk, embedding: [Float])] = []
        while sqlite3_step(stmt) == SQLITE_ROW {
            func text(_ col: Int32) -> String { sqlite3_column_text(stmt, col).map { String(cString: $0) } ?? "" }
            let byteLen = Int(sqlite3_column_bytes(stmt, 4))
            var embedding = [Float](repeating: 0, count: 384)
            if let blob = sqlite3_column_blob(stmt, 4), byteLen == 384 * MemoryLayout<Float>.size {
                _ = embedding.withUnsafeMutableBytes { memcpy($0.baseAddress, blob, byteLen) }
            }
            let meta = EdgeChunk.Metadata(json: text(5))
            result.append((
                chunk: EdgeChunk(id: text(0), documentId: text(1), sectionTitle: text(2),
                                 contentText: text(3), errorCodes: meta.errorCodes,
                                 toolsRequired: meta.toolsRequired),
                embedding: embedding))
        }
        return result
    }
}
