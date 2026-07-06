import SwiftUI

/// The Analysis section's "Overview" segment, styled as the instrument's front panel: the active
/// transport (switchable), a "Run all" action, and live aggregate results (IA collapse spec, 2026-06-21 —
/// was the standalone "Dashboard" tab).
struct DashboardView: View {
    @ObservedObject var viewModel: FeaturesViewModel
    @State private var revealed = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Instrument.Space.l) {
                    transportCard.revealCard(revealed, delay: 0)
                    summaryCard.revealCard(revealed, delay: 0.08)
                    runCard.revealCard(revealed, delay: 0.16)

                    if let errorMessage = viewModel.errorMessage {
                        // Retry reloads the catalog — only offer it when that's what failed.
                        // A failed feature run is retried from its own row, not here.
                        if viewModel.descriptors.isEmpty {
                            ErrorBanner(message: errorMessage) {
                                Task { await viewModel.load() }
                            }
                        } else {
                            ErrorBanner(message: errorMessage)
                        }
                    }
                }
                .padding(Instrument.Space.l)
            }
            .background(Instrument.bg0)
            .navigationTitle("Dashboard")
            .onAppear { revealed = true }
            .task { if viewModel.descriptors.isEmpty { await viewModel.load() } }
        }
    }

    private var transportCard: some View {
        VStack(alignment: .leading, spacing: Instrument.Space.m) {
            PanelHeader("Transport")
            TransportPicker(viewModel: viewModel)
            HStack(spacing: Instrument.Space.s) {
                TransportDot(kind: viewModel.selected)
                Text(viewModel.transport.mechanism)
                    .font(.footnote)
                    .foregroundStyle(Instrument.textSecondary)
            }
        }
        .instrumentCard()
    }

    private var summaryCard: some View {
        VStack(alignment: .leading, spacing: Instrument.Space.m) {
            PanelHeader("Engine self-check")
            HStack(alignment: .top) {
                StatCell(label: "Features", value: "\(viewModel.descriptors.count)")
                StatCell(label: "Passing", value: passingText, tint: passingTint)
                StatCell(label: "Engine time", value: engineTimeText)
            }
        }
        .instrumentCard()
    }

    private var runCard: some View {
        VStack(alignment: .leading, spacing: Instrument.Space.m) {
            PanelHeader("Run")
            Button {
                Task { await viewModel.runAll() }
            } label: {
                HStack(spacing: Instrument.Space.s) {
                    if viewModel.running.isEmpty {
                        Label("Run all features", systemImage: "play.fill")
                    } else {
                        ProgressView()
                        Text("Running \(viewModel.running.count)…")
                    }
                }
                .font(.subheadline.weight(.semibold))
                .frame(maxWidth: .infinity)
                .padding(.vertical, Instrument.Space.s)
            }
            .buttonStyle(.borderedProminent)
            .disabled(viewModel.descriptors.isEmpty || !viewModel.running.isEmpty)
        }
        .instrumentCard()
    }

    private var passingText: String {
        viewModel.ranCount == 0 ? "—" : "\(viewModel.okCount)/\(viewModel.ranCount)"
    }

    private var passingTint: Color {
        guard viewModel.ranCount > 0 else { return Instrument.textPrimary }
        return viewModel.okCount == viewModel.ranCount ? Instrument.ok : Instrument.fail
    }

    private var engineTimeText: String {
        viewModel.totalElapsedMs > 0 ? String(format: "%.2f ms", viewModel.totalElapsedMs) : "—"
    }
}
