import SwiftUI

/// The app shell: Dashboard / Features / Compare / About tabs over the shared view models.
struct RootTabView: View {
    @ObservedObject var features: FeaturesViewModel
    @ObservedObject var comparison: ComparisonViewModel

    var body: some View {
        TabView {
            DashboardView(viewModel: features)
                .tabItem { Label("Dashboard", systemImage: "square.grid.2x2") }

            FeaturesView(viewModel: features)
                .tabItem { Label("Features", systemImage: "checkmark.seal") }

            ComparisonView(model: comparison)
                .tabItem { Label("Compare", systemImage: "chart.bar.xaxis") }

            AboutView(infos: features.orderedInfos)
                .tabItem { Label("About", systemImage: "info.circle") }
        }
    }
}
