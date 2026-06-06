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
    @Published private(set) var ready = false
    @Published private(set) var allErrorCodes: [String] = []
    @Published private(set) var allTools: [String] = []

    private var engine: EdgeSearchEngine?
    private var loadTask: Task<EdgeSearchEngine, Error>?

    /// Builds the engine once, off-main (loads the ~90 MB ONNX model + SQLite index). Idempotent and
    /// cancellation-safe: every call awaits the same detached load, so a cancelled `.task` (the view
    /// disappearing) neither loses progress nor triggers a second model load.
    func prepare() async {
        guard engine == nil, unavailable == nil else { return }
        let task = loadTask ?? Task.detached(priority: .userInitiated) { try EdgeSearchEngine() }
        loadTask = task
        do {
            let e = try await task.value
            engine = e
            allErrorCodes = e.allErrorCodes.sorted()
            allTools = e.allTools.sorted()
            ready = true
        } catch is CancellationError {
            // View disappeared mid-load; keep loadTask so a re-appear awaits the same load.
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
