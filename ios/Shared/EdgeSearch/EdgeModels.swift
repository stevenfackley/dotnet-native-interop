import Foundation

/// One indexed maintenance-doc section from the edge index.
struct EdgeChunk: Identifiable, Sendable {
    let id: String            // ChunkId
    let documentId: String
    let sectionTitle: String
    let contentText: String
    let errorCodes: [String]
    let toolsRequired: [String]

    /// Decodes the `Metadata` JSON column: { "error_codes":[…], "tools_required":[…] }.
    struct Metadata {
        let errorCodes: [String]
        let toolsRequired: [String]
        init(json: String) {
            struct Raw: Decodable { let error_codes: [String]?; let tools_required: [String]? }
            let raw = try? JSONDecoder().decode(Raw.self, from: Data(json.utf8))
            errorCodes = raw?.error_codes ?? []
            toolsRequired = raw?.tools_required ?? []
        }
    }
}

/// A ranked hit: the chunk and its cosine score (0…1).
struct EdgeSearchHit: Identifiable, Sendable {
    let chunk: EdgeChunk
    let score: Float
    var id: String { chunk.id }
}

/// Errors surfaced by the edge engine; rendered as graceful "unavailable"/inline cards.
enum EdgeSearchError: LocalizedError {
    case assetMissing(String)
    case dbOpenFailed
    case inferenceFailed

    var errorDescription: String? {
        switch self {
        case .assetMissing(let f): return "Missing bundled asset: \(f)."
        case .dbOpenFailed: return "Couldn't open the compiled search index."
        case .inferenceFailed: return "On-device embedding failed (Core ML)."
        }
    }
}
