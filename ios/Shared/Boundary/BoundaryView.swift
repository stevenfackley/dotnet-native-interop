import SwiftUI

/// Approach A · A1: swimlane hero + segmented inspector that auto-follows the phase in Auto-step.
/// Also the app's hero/landing tab, first in the tab order — carries the demoted About screen behind an
/// ⓘ toolbar button (IA collapse spec, 2026-06-21: About moves from a top-level tab to a toolbar/sheet).
struct BoundaryView: View {
    @ObservedObject var viewModel: BoundaryViewModel
    let infos: [TransportInfo]
    let telemetry: TelemetryService
    @State private var revealed = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Instrument.Space.l) {
                    PanelHeader("Boundary · trace one FFI call")
                        .revealCard(revealed, delay: 0)

                    presetRow.revealCard(revealed, delay: 0.05)

                    VStack(alignment: .leading, spacing: Instrument.Space.s) {
                        PanelHeader("lifecycle — swimlane")
                        SwimlaneView(activePhase: viewModel.activePhase, streaming: viewModel.preset == .stream)
                        Label("callback fires off the UI thread → hops to @MainActor",
                              systemImage: "exclamationmark.triangle.fill")
                            .font(.caption).foregroundStyle(Instrument.warn)
                    }
                    .instrumentCard()
                    .revealCard(revealed, delay: 0.1)

                    controls.revealCard(revealed, delay: 0.15)

                    if let message = viewModel.errorMessage {
                        ErrorBanner(message: message) { Task { await viewModel.run() } }
                            .revealCard(revealed, delay: 0.2)
                    }

                    inspectorPicker.revealCard(revealed, delay: 0.2)
                    BoundaryInspectorPanel(vm: viewModel).revealCard(revealed, delay: 0.25)

                    trustLink.revealCard(revealed, delay: 0.3)
                }
                .padding(Instrument.Space.l)
            }
            .instrumentScreen()
            .navigationTitle("Boundary")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    AboutToolbarButton(infos: infos, telemetry: telemetry)
                }
            }
        }
        .task { revealed = true }
    }

    private var presetRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: Instrument.Space.s) {
                ForEach(BoundaryPreset.allCases) { preset in
                    let selected = viewModel.preset == preset
                    Text(preset.title)
                        .font(Instrument.panelLabel)
                        .padding(.horizontal, Instrument.Space.m).padding(.vertical, Instrument.Space.s)
                        .background(selected ? Instrument.accent : Instrument.bg2,
                                    in: Capsule())
                        .foregroundStyle(selected ? Instrument.bg0 : Instrument.textSecondary)
                        .onTapGesture { viewModel.preset = preset }
                }
            }
        }
    }

    private var controls: some View {
        HStack(spacing: Instrument.Space.m) {
            Button {
                Task { await viewModel.run() }
            } label: {
                Label(viewModel.preset == .stream ? "Run stream" : "Run", systemImage: "play.fill")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent).tint(Instrument.accent)
            .disabled(viewModel.running)

            Button {
                Task { await viewModel.autoStep() }
            } label: {
                Label("Auto-step", systemImage: "forward.frame").frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered).tint(Instrument.accent)
            .disabled(viewModel.autoStepping)

            if viewModel.preset == .stream && viewModel.running {
                Button(role: .destructive) { viewModel.stop() } label: { Image(systemName: "stop.fill") }
                    .buttonStyle(.bordered).tint(Instrument.fail)
            }
        }
    }

    private var inspectorPicker: some View {
        Picker("inspector", selection: $viewModel.inspector) {
            ForEach(BoundaryInspector.allCases) { seg in Text(seg.label).tag(seg) }
        }
        .pickerStyle(.segmented)
    }

    /// Entry to the Trust inspector (per-transport security posture + the opt-in post-quantum channel).
    private var trustLink: some View {
        NavigationLink {
            TrustView()
        } label: {
            HStack(spacing: Instrument.Space.m) {
                Image(systemName: "lock.shield")
                    .foregroundStyle(Instrument.transportBinary)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Trust · security posture")
                        .font(.subheadline).foregroundStyle(Instrument.textPrimary)
                    Text("Per-transport posture + opt-in post-quantum channel")
                        .font(.caption).foregroundStyle(Instrument.textSecondary)
                }
                Spacer()
                Image(systemName: "chevron.right").foregroundStyle(Instrument.textTertiary)
            }
            .instrumentCard()
        }
        .buttonStyle(.plain)
    }
}

#Preview {
    BoundaryView(viewModel: BoundaryViewModel(service: MockBoundaryService()),
                 infos: [.ffi, .http, .sqlite],
                 telemetry: TelemetryService())
}
