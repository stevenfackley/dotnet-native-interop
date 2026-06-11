import Foundation

/// Boundary validation for user text crossing into the native engine. The cap bounds the FFI
/// marshal without cutting below the embedding model's own ~256-word-piece window (a pasted
/// error-log excerpt is the EVS demo's core use case — don't silently drop its tail).
enum QueryInput {
    static let maxLength = 2000

    /// Trimmed, length-capped query — or nil if there's nothing to send.
    static func sanitize(_ raw: String) -> String? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        return String(trimmed.prefix(maxLength))
    }
}
