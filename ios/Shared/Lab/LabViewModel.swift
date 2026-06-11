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
    /// silent: `lastError` carries the context for the Lab screens to display. Writes are guarded
    /// so the render loop's per-frame successes/failures don't republish identical state.
    func render(_ command: String) async -> FeatureResult? {
        do {
            let result = try await services[transport].run(command)
            if lastError != nil { lastError = nil }
            return result
        } catch {
            // Strip per-frame parameters (after '~') so the message is stable across retry ticks.
            let name = command.split(separator: "~").first.map(String.init) ?? command
            let message = "‘\(name)’ over \(transport.displayName) failed: \(error.localizedDescription)"
            if lastError != message { lastError = message }
            return nil
        }
    }
}
