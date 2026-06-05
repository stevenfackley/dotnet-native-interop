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

    private let services: [TransportKind: FeatureService]

    init(services: [TransportKind: FeatureService]) {
        self.services = services
    }

    func loadDescriptorsIfNeeded() async {
        guard descriptors.isEmpty, let ffi = services[.ffi] else { return }
        do {
            descriptors = try await ffi.descriptors()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func runComparison() async {
        running = true
        defer { running = false }
        await loadDescriptorsIfNeeded()
        timings = [:]
        for descriptor in descriptors {
            for kind in TransportKind.allCases {
                guard let service = services[kind] else { continue }
                let ms = await Self.measureMs { _ = try? await service.run(descriptor.id) }
                timings[descriptor.id, default: [:]][kind] = ms
            }
        }
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
