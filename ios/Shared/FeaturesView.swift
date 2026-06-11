import SwiftUI

/// Table-of-contents over all features for the SELECTED transport: a transport picker, collapsible
/// version sections, and rows that drill into a per-feature FeatureDetailView.
struct FeaturesView: View {
    @ObservedObject var viewModel: FeaturesViewModel
    @State private var collapsed: Set<String> = []

    var body: some View {
        NavigationStack {
            List {
                Section { TransportPicker(viewModel: viewModel) }
                    .instrumentRow()

                if viewModel.isLoading && viewModel.descriptors.isEmpty {
                    Section {
                        HStack(spacing: 8) { ProgressView(); Text("Loading features…") }
                    }
                    .instrumentRow()
                } else if viewModel.descriptors.isEmpty {
                    Section {
                        Text(viewModel.errorMessage ?? "No features. Tap Refresh.")
                            .foregroundStyle(Instrument.textSecondary)
                    }
                    .instrumentRow()
                } else {
                    groups
                }

                if let errorMessage = viewModel.errorMessage, !viewModel.descriptors.isEmpty {
                    Section {
                        ErrorBanner(message: errorMessage, retry: { Task { await viewModel.load() } })
                    }
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets())
                }
            }
            .navigationTitle("Features · \(viewModel.selected.displayName)")
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Refresh") { Task { await viewModel.load() } }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        viewModel.onlyFailed.toggle()
                    } label: {
                        Image(systemName: viewModel.onlyFailed
                              ? "line.3.horizontal.decrease.circle.fill"
                              : "line.3.horizontal.decrease.circle")
                    }
                }
            }
            .task { if viewModel.descriptors.isEmpty { await viewModel.load() } }
            .instrumentScreen()
        }
    }

    private var groups: some View {
        ForEach(viewModel.grouped, id: \.0) { version, items in
            DisclosureGroup(isExpanded: expansion(for: version)) {
                ForEach(items) { descriptor in
                    NavigationLink {
                        FeatureDetailView(descriptor: descriptor, viewModel: viewModel)
                    } label: {
                        FeatureRow(
                            descriptor: descriptor,
                            status: viewModel.status(descriptor.id),
                            result: viewModel.results[descriptor.id]
                        )
                    }
                }
            } label: {
                HStack {
                    Text(version).font(.headline)
                    Spacer()
                    Text("\(items.count)")
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(Instrument.textSecondary)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 2)
                        .background(.quaternary, in: Capsule())
                }
            }
            .instrumentRow()
        }
    }

    private func expansion(for version: String) -> Binding<Bool> {
        Binding(
            get: { !collapsed.contains(version) },
            set: { open in
                if open { collapsed.remove(version) } else { collapsed.insert(version) }
            }
        )
    }
}

/// One feature row: status icon, title, monospaced id, and last run's elapsed time.
struct FeatureRow: View {
    let descriptor: FeatureDescriptor
    let status: RunStatus
    let result: FeatureResult?

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: status.symbol)
                .foregroundStyle(status.color)
            VStack(alignment: .leading, spacing: 2) {
                Text(descriptor.title).font(.body)
                Text(descriptor.id)
                    .font(.caption2)
                    .foregroundStyle(Instrument.textSecondary)
                    .monospaced()
            }
            Spacer()
            if status == .running {
                ProgressView().scaleEffect(0.7)
            } else if let result {
                Text(String(format: "%.2f ms", result.elapsedMs))
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(Instrument.textTertiary)
            }
        }
        .padding(.vertical, 2)
    }
}
