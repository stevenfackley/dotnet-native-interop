import SwiftUI

/// On-device "Manuals" search: the query is embedded by ONNX Runtime + Core ML (Apple Neural Engine)
/// and cosine-ranked against a SQLite vector index the .NET publisher compiled offline. No network.
struct EdgeSearchView: View {
    @StateObject private var model = EdgeSearchViewModel()

    var body: some View {
        List {
            if let reason = model.unavailable {
                Section {
                    ErrorBanner(message: reason)
                        .listRowInsets(EdgeInsets())
                }
                .listRowBackground(Color.clear)
            } else if !model.ready {
                Section {
                    HStack {
                        ProgressView()
                        Text("Loading the on-device search engine…").foregroundStyle(Instrument.textSecondary)
                    }
                }
                .instrumentRow()
            } else {
                Section {
                    HStack {
                        TextField("Search manuals… e.g. \u{201C}compressor won\u{2019}t start\u{201D}", text: $model.query)
                            .textInputAutocapitalization(.never)
                            .onSubmit { Task { await model.run() } }
                        Button {
                            Task { await model.run() }
                        } label: {
                            if model.searching { ProgressView() } else { Image(systemName: "magnifyingglass") }
                        }
                        .disabled(model.searching)
                    }
                    Text("Embedded on-device by ONNX Runtime + Core ML (Apple Neural Engine), then cosine-"
                         + "ranked against a SQLite index the .NET publisher compiled offline. No network.")
                        .font(.caption).foregroundStyle(Instrument.textSecondary)
                }
                .instrumentRow()

                if !model.allErrorCodes.isEmpty || !model.allTools.isEmpty {
                    Section("Filters") {
                        if !model.allErrorCodes.isEmpty {
                            FacetRow(title: "Error codes", all: model.allErrorCodes, active: $model.activeErrorCodes)
                        }
                        if !model.allTools.isEmpty {
                            FacetRow(title: "Tools", all: model.allTools, active: $model.activeTools)
                        }
                    }
                    .instrumentRow()
                }

                if let error = model.errorMessage {
                    Section {
                        ErrorBanner(message: error)
                            .listRowInsets(EdgeInsets())
                    }
                    .listRowBackground(Color.clear)
                }

                if !model.hits.isEmpty {
                    Section("Results") {
                        ForEach(model.hits) { EdgeHitRow(hit: $0) }
                    }
                    .instrumentRow()
                } else if !model.query.isEmpty && !model.searching {
                    Section { Text("No matches \u{2265} 70% similarity.").foregroundStyle(Instrument.textSecondary) }
                    .listRowBackground(Instrument.bg1)
                }
            }
        }
        .instrumentScreen()
        .navigationTitle("Edge Vector Search")
        .task { await model.prepare() }
    }
}

/// A horizontally-scrolling set of toggle chips for one metadata facet.
private struct FacetRow: View {
    let title: String
    let all: [String]
    @Binding var active: Set<String>

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.caption).foregroundStyle(Instrument.textSecondary)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack {
                    ForEach(all, id: \.self) { tag in
                        let on = active.contains(tag)
                        Button(tag) { if on { active.remove(tag) } else { active.insert(tag) } }
                            .buttonStyle(.bordered)
                            .tint(on ? Instrument.accent : Instrument.textTertiary)
                    }
                }
            }
        }
    }
}

/// One ranked result: section title, snippet, a similarity bar, and any error codes.
private struct EdgeHitRow: View {
    let hit: EdgeSearchHit

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(hit.chunk.sectionTitle).font(.headline)
            Text(hit.chunk.contentText).font(.subheadline).foregroundStyle(Instrument.textSecondary).lineLimit(4)
            HStack {
                ProgressView(value: Double(max(0, min(1, hit.score))))
                    .tint(Instrument.accent)
                    .background(Instrument.bg2, in: Capsule())
                Text(String(format: "%.0f%%", hit.score * 100))
                    .font(.caption2.monospacedDigit())
                    .contentTransition(.numericText())
            }
            if !hit.chunk.errorCodes.isEmpty {
                Text("Codes: " + hit.chunk.errorCodes.joined(separator: ", "))
                    .font(.caption2).foregroundStyle(Instrument.textTertiary)
            }
        }
        .padding(.vertical, 2)
    }
}
