import Foundation

/// Backs the Latency Lab: holds every transport's service and the selected transport, and times
/// client-side round-trips of `ping` / `bench-echo` commands over a chosen transport.
@MainActor
final class LatencyViewModel: ObservableObject {
    @Published var transport: TransportKind = .ffi

    let services: [TransportKind: FeatureService]

    init(services: [TransportKind: FeatureService]) {
        self.services = services
    }

    /// One client-side round-trip of `command` over `kind`, in milliseconds (nil on failure).
    func roundTripMs(_ command: String, on kind: TransportKind) async -> Double? {
        guard let service = services[kind] else { return nil }
        let start = DispatchTime.now().uptimeNanoseconds
        guard (try? await service.run(command)) != nil else { return nil }
        return Double(DispatchTime.now().uptimeNanoseconds - start) / 1_000_000
    }

    /// `count` sequential `ping` round-trips over `kind` (pure transport overhead).
    func pingSeries(count: Int, on kind: TransportKind) async -> [Double] {
        var samples: [Double] = []
        samples.reserveCapacity(count)
        for _ in 0..<count {
            if let ms = await roundTripMs("ping", on: kind) { samples.append(ms) }
        }
        return samples
    }
}
