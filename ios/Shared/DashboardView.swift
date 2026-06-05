import SwiftUI

/// Overview tab: the active transport (switchable), a "Run all" action, and aggregate results.
struct DashboardView: View {
    @ObservedObject var viewModel: FeaturesViewModel

    var body: some View {
        NavigationStack {
            List {
                Section("Transport") {
                    TransportPicker(viewModel: viewModel)
                    Text(viewModel.transport.mechanism)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                Section("Run") {
                    Button {
                        Task { await viewModel.runAll() }
                    } label: {
                        Label("Run all features", systemImage: "play.circle.fill")
                    }
                    .disabled(viewModel.descriptors.isEmpty || !viewModel.running.isEmpty)

                    if !viewModel.running.isEmpty {
                        HStack(spacing: 8) {
                            ProgressView()
                            Text("Running \(viewModel.running.count)…")
                        }
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    }
                }

                Section("Summary") {
                    LabeledContent("Features") {
                        Text("\(viewModel.descriptors.count)").monospacedDigit()
                    }
                    LabeledContent("Ran") {
                        Text("\(viewModel.ranCount)").monospacedDigit()
                    }
                    LabeledContent("Passing") {
                        Text("\(viewModel.okCount)/\(viewModel.ranCount)")
                            .monospacedDigit()
                            .foregroundStyle(
                                viewModel.ranCount > 0 && viewModel.okCount == viewModel.ranCount
                                ? .green : .primary)
                    }
                    if viewModel.totalElapsedMs > 0 {
                        LabeledContent("Engine time") {
                            Text(String(format: "%.2f ms", viewModel.totalElapsedMs)).monospacedDigit()
                        }
                    }
                }

                if let errorMessage = viewModel.errorMessage {
                    Section("Error") { Text(errorMessage).foregroundStyle(.red) }
                }
            }
            .navigationTitle("Dashboard")
            .task { if viewModel.descriptors.isEmpty { await viewModel.load() } }
        }
    }
}
