import SwiftUI

/// Segmented control that switches the active transport (and reloads the catalog through it).
struct TransportPicker: View {
    @ObservedObject var viewModel: FeaturesViewModel

    var body: some View {
        Picker("Transport", selection: Binding(
            get: { viewModel.selected },
            set: { kind in Task { await viewModel.selectTransport(kind) } }
        )) {
            ForEach(TransportKind.allCases) { kind in
                Text(kind.displayName).tag(kind)
            }
        }
        .pickerStyle(.segmented)
    }
}
