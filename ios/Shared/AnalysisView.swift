import SwiftUI

/// The Analysis section (IA collapse spec, 2026-06-21): the neutral performance-evidence area, merging
/// the old "Dashboard", "Compare", and "Latency" tabs (the latter already hosts engine telemetry) behind
/// a segmented control — overview → comparison → latency evidence, one story told three ways. Each
/// child keeps its own body + navigation unmodified — this container only supplies the segmented switch.
struct AnalysisView: View {
    enum Focus: String, CaseIterable, Identifiable {
        case overview = "Overview"
        case compare = "Compare"
        case latency = "Latency"
        var id: Self { self }
    }

    @ObservedObject var features: FeaturesViewModel
    @ObservedObject var comparison: ComparisonViewModel
    @ObservedObject var latency: LatencyViewModel
    let telemetry: TelemetryService

    @State private var focus: Focus = .overview

    var body: some View {
        // Wrapped in a NavigationStack so the segmented control gets the same top-inset the single-screen
        // tabs (Boundary/Catalog/Lab) get: on iPad the TabView tab bar is a floating top capsule that
        // overlaps content lacking a nav bar, so a bare top-pinned Picker collided with it. The switched
        // children keep their own NavigationStacks (nested is fine for this static content).
        NavigationStack {
            VStack(spacing: 0) {
                Picker("Analysis focus", selection: $focus) {
                    ForEach(Focus.allCases) { f in Text(f.rawValue).tag(f) }
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, Instrument.Space.l)
                .padding(.vertical, Instrument.Space.s)

                Group {
                    // Each child hosts an AboutToolbarButton in its own NavigationStack's toolbar (IA
                    // collapse spec: About is reachable from Analysis as well as Boundary), hence the
                    // infos/telemetry plumbing.
                    switch focus {
                    case .overview: DashboardView(viewModel: features, telemetry: telemetry)
                    case .compare:  ComparisonView(model: comparison,
                                                   infos: features.orderedInfos, telemetry: telemetry)
                    case .latency:  LatencyHubView(model: latency,
                                                   infos: features.orderedInfos, telemetry: telemetry)
                    }
                }
            }
            .background(Instrument.bg0)
        }
    }
}
