import Foundation

/// Backs the Lab tab: holds every transport's service and the selected transport, and runs a single
/// parametric showcase command (visual or benchmark) over the selected transport. The command is just
/// an id, so it rides the existing `FeatureService.run` path.
@MainActor
final class LabViewModel: ObservableObject {
    @Published var transport: TransportKind = .ffi

    private let services: [TransportKind: FeatureService]

    init(services: [TransportKind: FeatureService]) {
        self.services = services
    }

    /// Runs one command over the currently selected transport; nil on error (surfaced as a fallback view).
    func render(_ command: String) async -> FeatureResult? {
        try? await services[transport]?.run(command)
    }
}
