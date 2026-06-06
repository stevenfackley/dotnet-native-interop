import Foundation

/// The SQLCipher round-trip payload from `dni_sqlite_rag` ({"answer": "…"}).
struct RagAnswer: Codable, Sendable {
    let answer: String
}

/// One SSE frame from the raw-HTTP `/rag` route (data: {index,text,final}).
struct RagSSEFrame: Decodable, Sendable {
    let index: Int
    let text: String
    let isFinal: Bool

    // `final` is a Swift keyword; map the JSON key to `isFinal`.
    enum CodingKeys: String, CodingKey {
        case index, text, isFinal = "final"
    }
}
