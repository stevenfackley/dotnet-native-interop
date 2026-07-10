import SwiftUI

@main
struct DotnetNativeInteropUnifiedApp: App {
    @StateObject private var features: FeaturesViewModel
    @StateObject private var comparison: ComparisonViewModel
    @StateObject private var lab: LabViewModel
    @StateObject private var latency: LatencyViewModel
    private let telemetry = TelemetryService()
    private let search = SemanticSearchService()
    private let engineRagServices = TransportMap<EngineRagService>(
        ffi: FFIRagService(),
        binary: PbRagService(),
        http: HTTPRagService(),
        sqlite: SQLiteRagService()
    )

    init() {
        let services = TransportMap<FeatureService>(
            ffi: FFIFeatureService(),
            binary: PbFeatureService(),
            http: HTTPFeatureService(),
            sqlite: SQLiteFeatureService()
        )
        let infos = TransportMap<TransportInfo>(ffi: .ffi, binary: .binary, http: .http, sqlite: .sqlite)
        _features = StateObject(wrappedValue: FeaturesViewModel(services: services, infos: infos))
        _comparison = StateObject(wrappedValue: ComparisonViewModel(services: services))
        _lab = StateObject(wrappedValue: LabViewModel(services: services))
        _latency = StateObject(wrappedValue: LatencyViewModel(services: services))
    }

    var body: some Scene {
        WindowGroup {
            RootTabView(features: features, comparison: comparison, lab: lab,
                        latency: latency, telemetry: telemetry, search: search,
                        engineRagServices: engineRagServices)
        }
    }
}
