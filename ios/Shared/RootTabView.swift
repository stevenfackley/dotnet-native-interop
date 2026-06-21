import SwiftUI

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
            BoundaryView(viewModel: boundary)
                .tabItem { Label("Boundary", systemImage: "arrow.left.arrow.right") }

            DashboardView(viewModel: features)
                .tabItem { Label("Dashboard", systemImage: "square.grid.2x2") }

            FeaturesView(viewModel: features)
                .tabItem { Label("Features", systemImage: "checkmark.seal") }

            LabView(lab: lab)
                .tabItem { Label("Lab", systemImage: "cpu") }

            AiHubView(search: search, engineRagServices: engineRagServices)
                .tabItem { Label("AI", systemImage: "sparkles") }

            ComparisonView(model: comparison)
                .tabItem { Label("Compare", systemImage: "chart.bar.xaxis") }

            LatencyHubView(model: latency, telemetry: telemetry)
                .tabItem { Label("Latency", systemImage: "stopwatch") }

            AboutView(infos: features.orderedInfos, telemetry: telemetry)
                .tabItem { Label("About", systemImage: "info.circle") }

            EdgeSearchHubView()
                .tabItem { Label("Manuals", systemImage: "wrench.and.screwdriver") }
        }
        .preferredColorScheme(.dark)
        .tint(Instrument.accent)
    }
}
