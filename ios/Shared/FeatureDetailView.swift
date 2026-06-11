import SwiftUI

/// Drill-down page for a single feature: shows the code + expected, runs it live on demand, and
/// renders the result with timing. Mirrors the reference project's CapabilityDetailView.
struct FeatureDetailView: View {
    let descriptor: FeatureDescriptor
    @ObservedObject var viewModel: FeaturesViewModel

    private var result: FeatureResult? { viewModel.results[descriptor.id] }
    private var isRunning: Bool { viewModel.isRunning(descriptor.id) }
    private var isVisual: Bool { VisualFeature.isVisual(descriptor.id) }

    var body: some View {
        List {
            Section("Overview") {
                LabeledContent("Feature") {
                    Text(descriptor.id).font(.callout.monospaced()).foregroundStyle(Instrument.textSecondary)
                }
                LabeledContent("Version") { VersionBadge(version: descriptor.version) }
                VStack(alignment: .leading, spacing: 6) {
                    Text("Code").font(.caption).foregroundStyle(Instrument.textSecondary)
                    CodeBlock(code: descriptor.code)
                }
                if isVisual {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Preview (every pixel computed in .NET)")
                            .font(.caption).foregroundStyle(Instrument.textSecondary)
                        FractalImageView(payload: descriptor.expected)
                    }
                } else {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Expected").font(.caption).foregroundStyle(Instrument.textSecondary)
                        Text(descriptor.expected).font(.callout.monospaced()).textSelection(.enabled)
                    }
                }
            }
            .instrumentRow()

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
            .instrumentRow()

            if let result {
                Section("Result") {
                    LabeledContent("Status") {
                        StatusBadge(status: result.ok ? .ok : .failed,
                                    label: result.ok ? "Works" : "Mismatch")
                    }
                    LabeledContent("Elapsed") {
                        Text(String(format: "%.3f ms", result.elapsedMs))
                            .monospacedDigit()
                            .contentTransition(.numericText())
                            .animation(.snappy, value: viewModel.runCounts[descriptor.id])
                    }
                    LabeledContent("Runs") {
                        Text("\(viewModel.runCounts[descriptor.id] ?? 1)")
                            .monospacedDigit()
                            .contentTransition(.numericText())
                            .foregroundStyle(Instrument.textSecondary)
                            .animation(.snappy, value: viewModel.runCounts[descriptor.id])
                    }
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Output").font(.caption).foregroundStyle(Instrument.textSecondary)
                        if isVisual {
                            FractalImageView(payload: result.result)
                        } else {
                            Text(result.result).font(.callout.monospaced()).textSelection(.enabled)
                        }
                    }
                    if !result.ok {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Expected").font(.caption).foregroundStyle(Instrument.textSecondary)
                            if isVisual {
                                FractalImageView(payload: descriptor.expected)
                            } else {
                                Text(descriptor.expected)
                                    .font(.callout.monospaced())
                                    .foregroundStyle(Instrument.textSecondary)
                            }
                        }
                    }
                }
                .instrumentRow()
            }

            if let errorMessage = viewModel.errorMessage {
                Section {
                    ErrorBanner(message: errorMessage, retry: { Task { await viewModel.run(descriptor.id) } })
                }
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets())
            }
        }
        .navigationTitle(descriptor.title)
        .navigationBarTitleDisplayMode(.inline)
        .instrumentScreen()
    }
}
