import Foundation

/// Runs on-device semantic search over the in-process C ABI (`dni_search`). FFI-only — the engine owns
/// the model and corpora. Copies + frees the returned heap UTF-8 JSON, then decodes it.
struct SemanticSearchService: Sendable {
    func search(_ query: String, corpus: String) async throws -> [SearchResult] {
        let json = try await Task.detached(priority: .userInitiated) { () throws -> String in
            let ptr: UnsafePointer<CChar>? = query.withCString { q in
                corpus.withCString { c in dni_search(q, c) }
            }
            guard let ptr else { throw FeatureServiceError.nullResult }
            defer { dni_string_free(ptr) }
            return String(cString: ptr)
        }.value
        return try JSONDecode.decode([SearchResult].self, from: json)
    }
}
