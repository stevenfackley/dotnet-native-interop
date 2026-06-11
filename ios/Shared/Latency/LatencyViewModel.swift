import Foundation

/// Backs the Latency Lab: holds every transport's service and the selected transport, and times
/// client-side round-trips of `ping` / `bench-echo` commands over a chosen transport.
@MainActor
final class LatencyViewModel: ObservableObject {
    @Published var transport: TransportKind = .ffi

    let services: TransportMap<FeatureService>

    init(services: TransportMap<FeatureService>) {
        self.services = services
    }

    /// A measured series. Failed calls are excluded from `samples` (they have no honest timing)
    /// but are counted, so the charts can disclose incomplete data instead of hiding it.
    struct Series {
        var samples: [Double] = []
        var failures: Int = 0
    }

    /// One client-side round-trip of `command` over `kind`, in milliseconds (nil on failure).
    func roundTripMs(_ command: String, on kind: TransportKind) async -> Double? {
        let start = DispatchTime.now().uptimeNanoseconds
        guard (try? await services[kind].run(command)) != nil else { return nil }
        return Double(DispatchTime.now().uptimeNanoseconds - start) / 1_000_000
    }

    /// `count` sequential `ping` round-trips over `kind` (pure transport overhead).
    /// Failure policy (user decision 2026-06-10): skip + disclose — failed pings never produce a
    /// sample, the charts must show the failure count. Mirrored in the Android LatencyViewModel.
    func pingSeries(count: Int, on kind: TransportKind) async -> Series {
        var series = Series()
        series.samples.reserveCapacity(count)
        for _ in 0..<count {
            if let ms = await roundTripMs("ping", on: kind) {
                series.samples.append(ms)
            } else {
                series.failures += 1
            }
        }
        return series
    }
}
