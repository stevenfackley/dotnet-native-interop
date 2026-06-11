import Foundation

/// Backs the semantic-search screen: holds the query, selected corpus, and ranked results.
@MainActor
final class AiViewModel: ObservableObject {
    @Published var query = ""
    @Published var corpus = "features"
    @Published var results: [SearchResult] = []
    @Published var searching = false
    @Published var errorMessage: String?

    private let service: SemanticSearchService
    init(service: SemanticSearchService) { self.service = service }

    func run() async {
        guard let q = QueryInput.sanitize(query) else { return }
        searching = true
        defer { searching = false }
        do {
            results = try await service.search(q, corpus: corpus)
            errorMessage = nil
        } catch {
            errorMessage = "Searching ‘\(corpus)’ failed: \(error.localizedDescription)"
        }
    }
}
