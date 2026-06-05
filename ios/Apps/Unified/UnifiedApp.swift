import SwiftUI

/// Unified app entry: one app, all three transports. Builds the services once and shares them between
/// the Features explorer (selected transport) and the Compare tab (all transports).
@main
struct DotnetNativeInteropUnifiedApp: App {
    @StateObject private var features: FeaturesViewModel
    @StateObject private var comparison: ComparisonViewModel

    init() {
        let services: [TransportKind: FeatureService] = [
            .ffi: FFIFeatureService(),
            .http: HTTPFeatureService(),
            .sqlite: SQLiteFeatureService(),
        ]
        let infos: [TransportKind: TransportInfo] = [
            .ffi: .ffi,
            .http: .http,
            .sqlite: .sqlite,
        ]
        _features = StateObject(wrappedValue: FeaturesViewModel(services: services, infos: infos))
        _comparison = StateObject(wrappedValue: ComparisonViewModel(services: services))
    }

    var body: some Scene {
        WindowGroup {
            RootTabView(features: features, comparison: comparison)
        }
    }
}
