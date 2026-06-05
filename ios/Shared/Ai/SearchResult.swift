import Foundation

/// One ranked corpus entry from `dni_search` (camelCase JSON).
struct SearchResult: Codable, Sendable, Identifiable {
    let text: String
    let score: Double
    var id: String { text }
}
