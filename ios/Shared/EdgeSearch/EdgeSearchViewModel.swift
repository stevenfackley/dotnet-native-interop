import Foundation

/// Backs the Manuals screen: query, metadata filters, ranked hits, busy/error state. Loads the engine
/// lazily off the main actor (the ONNX model + index) on first appearance, so app startup isn't blocked.
@MainActor
final class EdgeSearchViewModel: ObservableObject {
    @Published var query = ""
    @Published var hits: [EdgeSearchHit] = []
    @Published var activeErrorCodes: Set<String> = []
    @Published var activeTools: Set<String> = []
    @Published var searching = false
    @Published var errorMessage: String?
    @Published private(set) var unavailable: String?
    @Published private(set) var allErrorCodes: [String] = []
    @Published private(set) var allTools: [String] = []

    private var engine: EdgeSearchEngine?

    /// Builds the engine once, off-main. Sets `unavailable` (→ graceful card) on failure.
    func prepare() async {
        guard engine == nil, unavailable == nil else { return }
        do {
            let e = try await Task.detached(priority: .userInitiated) { try EdgeSearchEngine() }.value
            engine = e
            allErrorCodes = e.allErrorCodes.sorted()
            allTools = e.allTools.sorted()
        } catch {
            unavailable = error.localizedDescription
        }
    }

    func run() async {
        guard let engine else { return }
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { hits = []; return }
        searching = true
        defer { searching = false }
        do {
            let raw = try await Task.detached(priority: .userInitiated) { try engine.search(q) }.value
            hits = raw.filter { hit in
                (activeErrorCodes.isEmpty || !activeErrorCodes.isDisjoint(with: hit.chunk.errorCodes))
                    && (activeTools.isEmpty || !activeTools.isDisjoint(with: hit.chunk.toolsRequired))
            }
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
