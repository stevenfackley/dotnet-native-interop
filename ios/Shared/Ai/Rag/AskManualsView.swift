import SwiftUI

struct AskManualsView: View {
    @StateObject private var model: RagViewModel

    init(search: SemanticSearchService, engineServices: [TransportKind: EngineRagService]) {
        _model = StateObject(wrappedValue: RagViewModel(search: search, engineServices: engineServices))
    }

    var body: some View {
        List {
            Section {
                Picker("Engine transport", selection: $model.transport) {
                    ForEach(TransportKind.allCases) { Text($0.displayName).tag($0) }
                }
                .pickerStyle(.segmented)
                HStack {
                    TextField("Ask the manuals… e.g. \"compressor won't start\"", text: $model.query)
                        .textInputAutocapitalization(.never)
                        .onSubmit { Task { await model.ask() } }
                    Button {
                        Task { await model.ask() }
                    } label: {
                        if model.engineRunning || model.appleRunning { ProgressView() }
                        else { Image(systemName: "paperplane.fill") }
                    }
                    .disabled(model.query.isEmpty)
                }
                Text("Retrieval runs in the .NET engine over the manuals corpus; the engine answer "
                     + "streams over the selected transport, shown beside Apple's on-device model "
                     + "answering the same retrieved context.")
                    .font(.caption).foregroundStyle(.secondary)
            }

            if let error = model.errorMessage {
                Section { Text(error).foregroundStyle(.red) }
            }

            if !model.sources.isEmpty {
                Section("Sources (retrieved)") {
                    ForEach(model.sources) { source in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(source.text).font(.callout)
                            Text(String(format: "similarity %.3f", source.score))
                                .font(.caption2.monospacedDigit()).foregroundStyle(.secondary)
                        }
                    }
                }
            }

            if !model.engineAnswer.isEmpty || model.engineRunning {
                Section(engineHeader) {
                    Text(model.engineAnswer.isEmpty ? "…" : model.engineAnswer)
                }
            }

            Section("Apple Foundation Models") {
                if let why = model.appleUnavailable {
                    ContentUnavailableView("Apple model unavailable",
                                           systemImage: "sparkles.slash", description: Text(why))
                } else {
                    Text(model.appleAnswer.isEmpty ? (model.appleRunning ? "…" : "Ask to compare.")
                                                   : model.appleAnswer)
                }
            }
        }
        .navigationTitle("Ask the Manuals")
    }

    private var engineHeader: String {
        var parts = ["Engine (\(model.transport.displayName))"]
        if let f = model.engineFirstTokenMs { parts.append(String(format: "first %.0f ms", f)) }
        if let t = model.engineTotalMs { parts.append(String(format: "total %.0f ms", t)) }
        return parts.joined(separator: " · ")
    }
}
