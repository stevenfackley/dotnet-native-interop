import SwiftUI

/// The AI tab: on-device semantic search driven by the .NET engine, plus Apple's model for contrast.
struct AiHubView: View {
    let search: SemanticSearchService

    var body: some View {
        NavigationStack {
            List {
                Section("On-device, in the .NET engine") {
                    NavigationLink { SemanticSearchView(service: search) } label: {
                        Label("Semantic search", systemImage: "magnifyingglass")
                    }
                }
                Section("Apple, for comparison") {
                    NavigationLink { AppleChatView() } label: {
                        Label("Apple chat", systemImage: "apple.logo")
                    }
                }
            }
            .navigationTitle("AI")
        }
    }
}
