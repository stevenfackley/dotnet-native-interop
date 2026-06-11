import Foundation

/// Drives the Compare tab: runs each feature over every transport and records the client-side
/// round-trip time (Swift wall-clock around the call) — the real cost difference, since the engine's
/// execution time is identical across transports.
@MainActor
final class ComparisonViewModel: ObservableObject {
    @Published var descriptors: [FeatureDescriptor] = []
    @Published var timings: [String: [TransportKind: Double]] = [:]   // featureId -> transport -> ms
    @Published var running = false
    @Published var errorMessage: String?

    private let services: TransportMap<FeatureService>

    init(services: TransportMap<FeatureService>) {
        self.services = services
    }

    func loadDescriptorsIfNeeded() async {
        guard descriptors.isEmpty else { return }
        do {
            descriptors = try await services.ffi.descriptors()
        } catch {
            errorMessage = "Loading the catalog over FFI failed: \(error.localizedDescription)"
        }
    }

    func runComparison() async {
        running = true
        defer { running = false }
        await loadDescriptorsIfNeeded()
        timings = [:]
        var failed: [String] = []
        for descriptor in descriptors {
            for kind in TransportKind.allCases {
                let service = services[kind]
                var callFailed = false
                let ms = await Self.measureMs {
                    do { _ = try await service.run(descriptor.id) } catch { callFailed = true }
                }
                if callFailed {
                    failed.append("\(descriptor.id) (\(kind.displayName))")
                } else {
                    timings[descriptor.id, default: [:]][kind] = ms
                }
            }
        }
        errorMessage = failed.isEmpty ? nil : "Skipped failed runs: \(failed.joined(separator: ", "))"
    }

    /// Total round-trip ms per transport across all measured features.
    var totals: [TransportKind: Double] {
        var out: [TransportKind: Double] = [:]
        for perFeature in timings.values {
            for (kind, ms) in perFeature {
                out[kind, default: 0] += ms
            }
        }
        return out
    }

    var maxTotal: Double { totals.values.max() ?? 0 }

    func maxForFeature(_ id: String) -> Double {
        timings[id]?.values.max() ?? 0
    }

    private static func measureMs(_ work: () async -> Void) async -> Double {
        let start = Date()
        await work()
        return Date().timeIntervalSince(start) * 1000
    }
}
