import Foundation

@MainActor
final class RagViewModel: ObservableObject {
    @Published var query = ""
    @Published var transport: TransportKind = .ffi
    @Published var sources: [SearchResult] = []
    @Published var engineAnswer = ""
    @Published var appleAnswer = ""
    @Published var engineRunning = false
    @Published var appleRunning = false
    @Published var engineFirstTokenMs: Double?
    @Published var engineTotalMs: Double?
    @Published var errorMessage: String?
    @Published var appleUnavailable: String? = AppleRagService.availabilityMessage()

    private let search: SemanticSearchService
    private let engineServices: TransportMap<EngineRagService>
    private let apple = AppleRagService()
    private var engineTask: Task<Void, Never>?
    private var appleTask: Task<Void, Never>?

    init(search: SemanticSearchService, engineServices: TransportMap<EngineRagService>) {
        self.search = search
        self.engineServices = engineServices
    }

    func ask() async {
        guard let q = QueryInput.sanitize(query) else { return }

        cancel()
        errorMessage = nil
        engineAnswer = ""
        appleAnswer = ""
        engineFirstTokenMs = nil
        engineTotalMs = nil

        // 1) Shared retrieval — feeds BOTH panes identical context.
        do {
            sources = try await search.search(q, corpus: "manuals")
        } catch {
            errorMessage = "Retrieving sources failed: \(error.localizedDescription)"
            return
        }

        // 2) Engine pane over the selected transport (appends deltas).
        let service = engineServices[transport]
        let kind = transport
        engineRunning = true
        let start = Date()
        engineTask = Task { @MainActor in
            do {
                for try await delta in service.answer(to: q) {
                    if engineFirstTokenMs == nil {
                        engineFirstTokenMs = Date().timeIntervalSince(start) * 1000
                    }
                    engineAnswer += delta
                }
            } catch {
                errorMessage = "Engine answer over \(kind.displayName) failed: \(error.localizedDescription)"
            }
            engineTotalMs = Date().timeIntervalSince(start) * 1000
            engineRunning = false
        }

        // 3) Apple pane over the SAME sources (replaces with cumulative snapshots).
        if appleUnavailable == nil {
            appleRunning = true
            let snapshot = sources
            appleTask = Task { @MainActor in
                do {
                    for try await whole in apple.answer(to: q, sources: snapshot) {
                        appleAnswer = whole
                    }
                } catch {
                    if appleUnavailable == nil { appleUnavailable = error.localizedDescription }
                }
                appleRunning = false
            }
        }
    }

    func cancel() {
        engineTask?.cancel(); engineTask = nil
        appleTask?.cancel(); appleTask = nil
        engineRunning = false
        appleRunning = false
    }
}
