import SwiftUI

/// On-device semantic search powered entirely by the .NET engine: the query is embedded by an
/// all-MiniLM model running in NativeAOT and cosine-ranked against the chosen corpus.
struct SemanticSearchView: View {
    @StateObject private var model: AiViewModel

    init(service: SemanticSearchService) {
        _model = StateObject(wrappedValue: AiViewModel(service: service))
    }

    var body: some View {
        List {
            Section {
                Picker("Corpus", selection: $model.corpus) {
                    Text("App features").tag("features")
                    Text("Facts").tag("facts")
                }
                .pickerStyle(.segmented)
                HStack {
                    TextField("Search… e.g. \u{201C}encrypt my data\u{201D}", text: $model.query)
                        .textInputAutocapitalization(.never)
                        .onSubmit { Task { await model.run() } }
                    Button {
                        Task { await model.run() }
                    } label: {
                        if model.searching { ProgressView() } else { Image(systemName: "magnifyingglass") }
                    }
                    .disabled(model.searching)
                }
                Text("The query is embedded by an all-MiniLM model running in the NativeAOT .NET engine, "
                     + "then cosine-ranked against the corpus — all in-process, no cloud.")
                    .font(.caption).foregroundStyle(Instrument.textSecondary)
            }
            .listRowBackground(Instrument.bg1)
            .listRowSeparatorTint(Instrument.hairline)

            if let error = model.errorMessage {
                Section {
                    ErrorBanner(message: error, retry: { Task { await model.run() } })
                }
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets())
            }

            if !model.results.isEmpty {
                Section("Results") {
                    ForEach(model.results) { result in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(result.text)
                            ProgressView(value: max(0, min(1, result.score)))
                                .tint(Instrument.accent)
                            Text(String(format: "similarity %.3f", result.score))
                                .font(.caption2.monospacedDigit())
                                .foregroundStyle(Instrument.textSecondary)
                                .contentTransition(.numericText())
                        }
                        .padding(.vertical, 2)
                    }
                }
                .listRowBackground(Instrument.bg1)
                .listRowSeparatorTint(Instrument.hairline)
            }
        }
        .instrumentScreen()
        .navigationTitle("Semantic search")
    }
}
