import SwiftUI

/// Catalog: table-of-contents over all features for the SELECTED transport — a transport picker, a
/// search field, version/status filter chips, a sort order, and rows that drill into a per-feature
/// FeatureDetailView. Version sort (the default) shows collapsible version sections; name/elapsed
/// sorts show one flat, globally ordered list. Search/filter/sort is the Catalog's one net-new
/// behavior from the IA collapse spec (2026-06-21) — 57 items is past scan-only.
struct FeaturesView: View {
    @ObservedObject var viewModel: FeaturesViewModel
    @State private var collapsed: Set<String> = []

    var body: some View {
        NavigationStack {
            List {
                Section { TransportPicker(viewModel: viewModel) }
                    .instrumentRow()

                Section { filterChips }
                    .instrumentRow()
                    .listRowSeparator(.hidden)

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
                } else if viewModel.filteredAndSorted.isEmpty {
                    Section {
                        Text("No features match your search or filters.")
                            .foregroundStyle(Instrument.textSecondary)
                    }
                    .instrumentRow()
                } else if viewModel.sort == .version {
                    groups
                } else {
                    // Name/elapsed sorts render ONE flat list — keeping the always-on version
                    // sections would silently defeat the global ordering the user just asked for.
                    flatRows
                }

                if let errorMessage = viewModel.errorMessage, !viewModel.descriptors.isEmpty {
                    Section {
                        ErrorBanner(message: errorMessage, retry: { Task { await viewModel.load() } })
                    }
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets())
                }
            }
            .searchable(text: $viewModel.searchText, prompt: "Search the catalog…")
            .navigationTitle("Catalog · \(viewModel.selected.displayName)")
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Refresh") { Task { await viewModel.load() } }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    sortMenu
                }
            }
            .task { if viewModel.descriptors.isEmpty { await viewModel.load() } }
            .instrumentScreen()
        }
    }

    /// Sort menu — replaces a plain toggle since there are now three orders, not just a pass/fail flip.
    private var sortMenu: some View {
        Menu {
            Picker("Sort", selection: $viewModel.sort) {
                ForEach(CatalogSort.allCases) { order in
                    Text("Sort by \(order.rawValue)").tag(order)
                }
            }
        } label: {
            Image(systemName: "arrow.up.arrow.down.circle")
        }
    }

    /// Version-bucket + pass/fail filter chips. Single-select within each group: tapping a chip
    /// replaces that group's active filter; tapping the active chip again clears it. The version
    /// group and the status group combine (AND) across the divider.
    private var filterChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: Instrument.Space.s) {
                ForEach(VersionBucket.allCases) { bucket in
                    chip(bucket.rawValue, selected: viewModel.versionFilter == bucket) {
                        viewModel.versionFilter = (viewModel.versionFilter == bucket) ? nil : bucket
                    }
                }
                Rectangle()
                    .fill(Instrument.hairline)
                    .frame(width: 1, height: 18)
                ForEach(StatusChip.allCases) { status in
                    chip(status.rawValue, selected: viewModel.statusFilter == status) {
                        viewModel.statusFilter = (viewModel.statusFilter == status) ? nil : status
                    }
                }
            }
        }
    }

    /// A real Button (not Text + tap gesture) so VoiceOver gets the button trait and can activate it;
    /// .isSelected exposes the active-filter state the capsule tint shows visually.
    private func chip(_ title: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(Instrument.panelLabel)
                .padding(.horizontal, Instrument.Space.m).padding(.vertical, Instrument.Space.s)
                .background(selected ? Instrument.accent : Instrument.bg2, in: Capsule())
                .foregroundStyle(selected ? Instrument.bg0 : Instrument.textSecondary)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    /// Flat, globally ordered rows for the name/elapsed sorts. Reuses the exact grouped-row shape
    /// (NavigationLink + FeatureRow) — no sections, no new visual language.
    private var flatRows: some View {
        Section {
            ForEach(viewModel.filteredAndSorted) { descriptor in
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
        }
        .instrumentRow()
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
