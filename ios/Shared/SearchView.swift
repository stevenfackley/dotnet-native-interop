import SwiftUI

/// The Search section (IA collapse spec, 2026-06-21): merges the old "AI" and "Manuals" tabs behind a
/// segmented control. The architectural difference between the two is surfaced explicitly via a one-line
/// note rather than hidden behind two look-alike screens. Both children keep their own body + navigation
/// unmodified — this container only supplies the segmented switch and the note.
struct SearchView: View {
    /// Which search implementation is active. "Engine (FFI)" = the .NET engine over FFI (existing AI
    /// hub); "On-device" = fully on-device ONNX + Core ML, no engine (existing Manuals/EdgeSearch hub).
    enum Engine: String, CaseIterable, Identifiable {
        case ffi = "Engine (FFI)"
        case onDevice = "On-device"
        var id: Self { self }

        var note: String {
            switch self {
            case .ffi:      return "Uses the .NET engine over FFI"
            case .onDevice: return "Runs entirely on-device via ONNX — no engine"
            }
        }
    }

    let search: SemanticSearchService
    let engineRagServices: TransportMap<EngineRagService>

    @State private var engine: Engine = .ffi

    var body: some View {
        VStack(spacing: 0) {
            VStack(spacing: Instrument.Space.xs) {
                Picker("Search engine", selection: $engine) {
                    ForEach(Engine.allCases) { mode in
                        Text(mode.rawValue).tag(mode)
                    }
                }
                .pickerStyle(.segmented)

                Text(engine.note)
                    .font(.caption)
                    .foregroundStyle(Instrument.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.horizontal, Instrument.Space.l)
            .padding(.top, Instrument.Space.s)
            .padding(.bottom, Instrument.Space.xs)

            Group {
                switch engine {
                case .ffi:      AiHubView(search: search, engineRagServices: engineRagServices)
                case .onDevice: EdgeSearchHubView()
                }
            }
        }
        .background(Instrument.bg0)
    }
}
