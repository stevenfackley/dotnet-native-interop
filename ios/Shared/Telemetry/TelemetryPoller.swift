import Foundation

/// Polls the engine telemetry on a timer while running, keeping the latest stats and a short
/// heap-size history (MB) for charting. State lives here (not @State) so the loop reads it live.
@MainActor
final class TelemetryPoller: ObservableObject {
    @Published var stats: EngineStats?
    @Published var heapHistory: [Double] = []
    @Published var committedHistory: [Double] = []
    @Published var errorMessage: String?

    private let service: TelemetryService
    private let intervalNs: UInt64
    private let historyCap = 120

    init(service: TelemetryService, intervalMs: UInt64 = 300) {
        self.service = service
        self.intervalNs = intervalMs * 1_000_000
    }

    func loop() async {
        while !Task.isCancelled {
            do {
                let snapshot = try await service.stats()
                stats = snapshot
                heapHistory.append(Double(snapshot.heapBytes) / 1_048_576)
                committedHistory.append(Double(snapshot.committedBytes) / 1_048_576)
                if heapHistory.count > historyCap {
                    let excess = heapHistory.count - historyCap
                    heapHistory.removeFirst(excess)
                    committedHistory.removeFirst(excess)
                }
                errorMessage = nil
            } catch {
                errorMessage = error.localizedDescription
            }
            try? await Task.sleep(nanoseconds: intervalNs)
        }
    }
}
