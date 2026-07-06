import Foundation

/// Shared state for the Dashboard and Features explorer. Holds every transport's service and the
/// currently selected one; the catalog and per-feature results shown are for the selected transport.
@MainActor
final class FeaturesViewModel: ObservableObject {
    @Published var selected: TransportKind = .ffi
    @Published var descriptors: [FeatureDescriptor] = []
    @Published var results: [String: FeatureResult] = [:]
    @Published var runCounts: [String: Int] = [:]
    @Published var running: Set<String> = []
    @Published var isLoading = false
    @Published var errorMessage: String?

    // Catalog search/filter/sort (IA collapse spec, 2026-06-21).
    @Published var searchText = ""
    @Published var versionFilter: VersionBucket?
    @Published var statusFilter: StatusChip?
    @Published var sort: CatalogSort = .version

    private let services: TransportMap<FeatureService>
    let infos: TransportMap<TransportInfo>

    init(services: TransportMap<FeatureService>, infos: TransportMap<TransportInfo>) {
        self.services = services
        self.infos = infos
    }

    private var service: FeatureService { services[selected] }
    var transport: TransportInfo { infos[selected] }
    var orderedInfos: [TransportInfo] { TransportKind.allCases.map { infos[$0] } }

    /// Descriptors grouped into ordered (version, items) sections, after search text + the version/status
    /// filter chips. Used by the catalog's version-sort mode only — the name/elapsed sorts render the
    /// flat `filteredAndSorted` directly, since version sections would defeat a global ordering.
    var grouped: [(String, [FeatureDescriptor])] {
        let shown = filteredAndSorted
        return shown.map(\.version).uniqued().map { version in
            (version, shown.filter { $0.version == version })
        }
    }

    /// Descriptors after search text + the version/status filter chips, ordered per the active sort
    /// (57 items is past scan-only — the catalog's one net-new behavior from the IA collapse spec).
    /// Rendered flat by the catalog for the name/elapsed sorts, and via `grouped` for version sort.
    var filteredAndSorted: [FeatureDescriptor] {
        var shown = descriptors

        if let statusFilter {
            shown = shown.filter { descriptor in
                let ok = results[descriptor.id]?.ok
                return statusFilter == .pass ? ok == true : ok == false
            }
        }
        if let versionFilter {
            shown = shown.filter { versionFilter.matches($0) }
        }
        if !searchText.isEmpty {
            let query = searchText.lowercased()
            shown = shown.filter {
                $0.title.lowercased().contains(query) || $0.id.lowercased().contains(query)
            }
        }

        // All three sorts are made stable explicitly (enumerated offset as tiebreak): Swift's
        // sorted(by:) does not guarantee stability, and ties are common (equal elapsed for un-run
        // items, equal version numbers). Ties keep the engine's catalog order.
        switch sort {
        case .name:
            return shown.enumerated().sorted { a, b in
                let cmp = a.element.title.localizedStandardCompare(b.element.title)
                return cmp == .orderedSame ? a.offset < b.offset : cmp == .orderedAscending
            }.map(\.element)
        case .version:
            // Explicit sort, newest C# version first — the engine's catalog concatenates its Modern
            // and Classic blocks (LanguageFeatures*.cs), so load order is NOT version-ordered.
            return shown.enumerated().sorted { a, b in
                let (va, vb) = (a.element.versionNumber, b.element.versionNumber)
                return va == vb ? a.offset < b.offset : va > vb
            }.map(\.element)
        case .elapsed:
            return shown.enumerated().sorted { a, b in
                let (ea, eb) = (results[a.element.id]?.elapsedMs ?? -1, results[b.element.id]?.elapsedMs ?? -1)
                return ea == eb ? a.offset < b.offset : ea > eb
            }.map(\.element)
        }
    }

    func status(_ id: String) -> RunStatus {
        if running.contains(id) { return .running }
        guard let result = results[id] else { return .idle }
        return result.ok ? .ok : .failed
    }

    func isRunning(_ id: String) -> Bool { running.contains(id) }

    /// Switches transport, clears the per-transport results, and reloads via the new transport.
    func selectTransport(_ kind: TransportKind) async {
        guard kind != selected else { return }
        selected = kind
        results = [:]
        running = []
        await load()
    }

    func load() async {
        isLoading = true
        defer { isLoading = false }
        do {
            descriptors = try await service.descriptors()
            errorMessage = nil
        } catch {
            errorMessage = "Loading the \(selected.displayName) catalog failed: \(error.localizedDescription)"
        }
    }

    func run(_ id: String) async {
        running.insert(id)
        defer { running.remove(id) }
        do {
            results[id] = try await service.run(id)
            runCounts[id, default: 0] += 1
            errorMessage = nil
        } catch {
            errorMessage = "Running ‘\(id)’ over \(selected.displayName) failed: \(error.localizedDescription)"
        }
    }

    func runAll() async {
        for descriptor in descriptors {
            await run(descriptor.id)
        }
    }

    var ranCount: Int { results.count }
    var okCount: Int { results.values.filter(\.ok).count }
    var totalElapsedMs: Double { results.values.reduce(0) { $0 + $1.elapsedMs } }
}

extension Array where Element: Hashable {
    /// First-occurrence-preserving de-dup, used to derive ordered version sections.
    func uniqued() -> [Element] {
        var seen = Set<Element>()
        return filter { seen.insert($0).inserted }
    }
}
