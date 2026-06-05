import SwiftUI
#if canImport(FoundationModels)
import FoundationModels
#endif

/// Apple's on-device language model (iOS 26 `FoundationModels`), for comparison with the .NET search.
/// Gated behind availability — degrades to an explanatory card on ineligible devices. Pure Swift, no engine.
struct AppleChatView: View {
    @State private var prompt = ""
    @State private var answer = ""
    @State private var thinking = false
    @State private var unavailableReason: String?

    var body: some View {
        List {
            Section {
                Text("Apple's on-device model (Foundation Models), shown for contrast — this one is driven "
                     + "from Swift, not the .NET engine.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            if let reason = unavailableReason {
                Section {
                    ContentUnavailableView("Apple Intelligence unavailable", systemImage: "sparkles.slash",
                                           description: Text(reason))
                }
            } else {
                Section {
                    HStack {
                        TextField("Ask Apple's model…", text: $prompt)
                            .onSubmit { Task { await ask() } }
                        Button {
                            Task { await ask() }
                        } label: {
                            if thinking { ProgressView() } else { Image(systemName: "paperplane.fill") }
                        }
                        .disabled(thinking || prompt.isEmpty)
                    }
                }
                if !answer.isEmpty {
                    Section("Response") { Text(answer) }
                }
            }
        }
        .navigationTitle("Apple chat")
        .task { checkAvailability() }
    }

    private func checkAvailability() {
        #if canImport(FoundationModels)
        if #available(iOS 26.0, *) {
            switch SystemLanguageModel.default.availability {
            case .available: unavailableReason = nil
            case .unavailable(let reason): unavailableReason = "\(reason)"
            @unknown default: unavailableReason = "Unknown availability."
            }
        } else {
            unavailableReason = "Requires iOS 26 or later."
        }
        #else
        unavailableReason = "FoundationModels isn't available in this build."
        #endif
    }

    private func ask() async {
        #if canImport(FoundationModels)
        if #available(iOS 26.0, *) {
            thinking = true
            defer { thinking = false }
            answer = ""
            do {
                let session = LanguageModelSession()
                let stream = session.streamResponse(to: prompt)
                for try await partial in stream {
                    answer = partial.content
                }
            } catch {
                answer = "Error: \(error.localizedDescription)"
            }
        }
        #endif
    }
}
