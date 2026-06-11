import SwiftUI

/// The AI tab: on-device semantic search driven by the .NET engine, plus Apple's model for contrast.
struct AiHubView: View {
    let search: SemanticSearchService
    let engineRagServices: TransportMap<EngineRagService>

    var body: some View {
        NavigationStack {
            List {
                Section("On-device, in the .NET engine") {
                    NavigationLink { SemanticSearchView(service: search) } label: {
                        Label("Semantic search", systemImage: "magnifyingglass")
                    }
                }
                .listRowBackground(Instrument.bg1)
                .listRowSeparatorTint(Instrument.hairline)
                Section("Grounded Q&A") {
                    NavigationLink {
                        AskManualsView(search: search, engineServices: engineRagServices)
                    } label: {
                        Label("Ask the Manuals (RAG)", systemImage: "text.book.closed")
                    }
                }
                .listRowBackground(Instrument.bg1)
                .listRowSeparatorTint(Instrument.hairline)
                Section("Apple, for comparison") {
                    NavigationLink { AppleChatView() } label: {
                        Label("Apple chat", systemImage: "apple.logo")
                    }
                }
                .listRowBackground(Instrument.bg1)
                .listRowSeparatorTint(Instrument.hairline)
            }
            .instrumentScreen()
            .navigationTitle("AI")
        }
    }
}
