import SwiftUI

/// Drill-down page for a single feature: shows the code + expected, runs it live on demand, and
/// renders the result with timing. Mirrors the reference project's CapabilityDetailView.
struct FeatureDetailView: View {
    let descriptor: FeatureDescriptor
    @ObservedObject var viewModel: FeaturesViewModel

    private var result: FeatureResult? { viewModel.results[descriptor.id] }
    private var isRunning: Bool { viewModel.isRunning(descriptor.id) }

    var body: some View {
        List {
            Section("Overview") {
                LabeledContent("Feature") {
                    Text(descriptor.id).font(.callout.monospaced()).foregroundStyle(.secondary)
                }
                LabeledContent("Version") { VersionBadge(version: descriptor.version) }
                VStack(alignment: .leading, spacing: 6) {
                    Text("Code").font(.caption).foregroundStyle(.secondary)
                    CodeBlock(code: descriptor.code)
                }
                VStack(alignment: .leading, spacing: 4) {
                    Text("Expected").font(.caption).foregroundStyle(.secondary)
                    Text(descriptor.expected).font(.callout.monospaced()).textSelection(.enabled)
                }
            }

            Section {
                Button {
                    Task { await viewModel.run(descriptor.id) }
                } label: {
                    if isRunning {
                        HStack(spacing: 8) { ProgressView(); Text("Running…") }
                    } else {
                        Label(result == nil ? "Run" : "Run again", systemImage: "play.fill")
                    }
                }
                .disabled(isRunning)
            }

            if let result {
                Section("Result") {
                    LabeledContent("Status") {
                        StatusBadge(status: result.ok ? .ok : .failed,
                                    label: result.ok ? "Works" : "Mismatch")
                    }
                    LabeledContent("Elapsed") {
                        Text(String(format: "%.3f ms", result.elapsedMs)).monospacedDigit()
                    }
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Output").font(.caption).foregroundStyle(.secondary)
                        Text(result.result).font(.callout.monospaced()).textSelection(.enabled)
                    }
                    if !result.ok {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Expected").font(.caption).foregroundStyle(.secondary)
                            Text(descriptor.expected)
                                .font(.callout.monospaced())
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }

            if let errorMessage = viewModel.errorMessage {
                Section("Error") {
                    Text(errorMessage).foregroundStyle(.red).textSelection(.enabled)
                }
            }
        }
        .navigationTitle(descriptor.title)
        .navigationBarTitleDisplayMode(.inline)
    }
}
