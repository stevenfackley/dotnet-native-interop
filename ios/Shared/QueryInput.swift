import Foundation

/// Boundary validation for user text crossing into the native engine. The embedding model only
/// reads ~256 word-pieces, so anything past the cap is dead weight in the FFI marshal.
enum QueryInput {
    static let maxLength = 500

    /// Trimmed, length-capped query — or nil if there's nothing to send.
    static func sanitize(_ raw: String) -> String? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        return String(trimmed.prefix(maxLength))
    }
}
