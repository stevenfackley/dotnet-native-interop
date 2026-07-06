import SwiftUI

/// Root navigation: 5 top-level sections per the IA collapse spec (2026-06-21), collapsed from the prior
/// 9 tabs. Boundary is the hero/default landing tab; About is demoted to a toolbar ⓘ on Boundary and on
/// each Analysis child (see `AboutToolbarButton`). Search and Analysis are new container screens that
/// reuse the absorbed tabs' existing view bodies unmodified, switched by a segmented control.
struct RootTabView: View {
    @ObservedObject var features: FeaturesViewModel
    @ObservedObject var comparison: ComparisonViewModel
    @ObservedObject var lab: LabViewModel
    @ObservedObject var latency: LatencyViewModel
    let telemetry: TelemetryService
    let search: SemanticSearchService
    let engineRagServices: TransportMap<EngineRagService>
    @StateObject private var boundary = BoundaryViewModel(service: FFIBoundaryService())

    var body: some View {
        TabView {
            BoundaryView(viewModel: boundary, infos: features.orderedInfos, telemetry: telemetry)
                .tabItem { Label("Boundary", systemImage: "arrow.left.arrow.right") }

            FeaturesView(viewModel: features)
                .tabItem { Label("Catalog", systemImage: "checkmark.seal") }

            LabView(lab: lab)
                .tabItem { Label("Lab", systemImage: "cpu") }

            SearchView(search: search, engineRagServices: engineRagServices)
                .tabItem { Label("Search", systemImage: "magnifyingglass") }

            AnalysisView(features: features, comparison: comparison, latency: latency, telemetry: telemetry)
                .tabItem { Label("Analysis", systemImage: "chart.bar.xaxis") }
        }
        .preferredColorScheme(.dark)
        .tint(Instrument.accent)
    }
}
