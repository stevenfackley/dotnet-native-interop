import SwiftUI

/// The AI tab: on-device semantic search driven by the .NET engine, plus Apple's model for contrast.
struct AiHubView: View {
    let search: SemanticSearchService
    let engineRagServices: [TransportKind: EngineRagService]

    var body: some View {
        NavigationStack {
            List {
                Section("On-device, in the .NET engine") {
                    NavigationLink { SemanticSearchView(service: search) } label: {
                        Label("Semantic search", systemImage: "magnifyingglass")
                    }
                }
                Section("Grounded Q&A") {
                    NavigationLink {
                        AskManualsView(search: search, engineServices: engineRagServices)
                    } label: {
                        Label("Ask the Manuals (RAG)", systemImage: "text.book.closed")
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
