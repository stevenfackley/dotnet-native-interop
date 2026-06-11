import Foundation

/// Backs the Lab tab: holds every transport's service and the selected transport, and runs a single
/// parametric showcase command (visual or benchmark) over the selected transport. The command is just
/// an id, so it rides the existing `FeatureService.run` path.
@MainActor
final class LabViewModel: ObservableObject {
    @Published var transport: TransportKind = .ffi
    /// Most recent render failure, with command + transport context. Cleared on the next success.
    @Published var lastError: String?

    private let services: TransportMap<FeatureService>

    init(services: TransportMap<FeatureService>) {
        self.services = services
    }

    /// Runs one command over the currently selected transport; nil on error. The failure is never
    /// silent: `lastError` carries the context for the Lab screens to display.
    func render(_ command: String) async -> FeatureResult? {
        do {
            let result = try await services[transport].run(command)
            lastError = nil
            return result
        } catch {
            lastError = "‘\(command)’ over \(transport.displayName) failed: \(error.localizedDescription)"
            return nil
        }
    }
}
