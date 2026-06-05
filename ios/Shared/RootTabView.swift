import SwiftUI

/// The app shell: Dashboard / Features / Lab / Compare / About tabs over the shared view models.
struct RootTabView: View {
    @ObservedObject var features: FeaturesViewModel
    @ObservedObject var comparison: ComparisonViewModel
    @ObservedObject var lab: LabViewModel

    var body: some View {
        TabView {
            DashboardView(viewModel: features)
                .tabItem { Label("Dashboard", systemImage: "square.grid.2x2") }

            FeaturesView(viewModel: features)
                .tabItem { Label("Features", systemImage: "checkmark.seal") }

            LabView(lab: lab)
                .tabItem { Label("Lab", systemImage: "cpu") }

            ComparisonView(model: comparison)
                .tabItem { Label("Compare", systemImage: "chart.bar.xaxis") }

            LatencyView(viewModel: features)
                .tabItem { Label("Latency", systemImage: "stopwatch") }

            AboutView(infos: features.orderedInfos)
                .tabItem { Label("About", systemImage: "info.circle") }
        }
    }
}
