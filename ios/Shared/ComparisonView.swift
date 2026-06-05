import SwiftUI

/// Side-by-side transport performance: runs every feature over FFI / HTTP / SQLite and shows the
/// client round-trip time per transport as bars, plus totals.
struct ComparisonView: View {
    @ObservedObject var model: ComparisonViewModel

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button {
                        Task { await model.runComparison() }
                    } label: {
                        if model.running {
                            HStack(spacing: 8) { ProgressView(); Text("Running comparison…") }
                        } else {
                            Label("Run comparison (all transports)", systemImage: "bolt.horizontal.circle")
                        }
                    }
                    .disabled(model.running)
                    Text("Bars show client-side round-trip time. Engine execution is identical across "
                         + "transports — the difference is the transport cost.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }

                if !model.totals.isEmpty {
                    Section("Totals — all features (ms)") {
                        ForEach(TransportKind.allCases) { kind in
                            TimingBar(label: kind.displayName,
                                      ms: model.totals[kind],
                                      maxMs: model.maxTotal,
                                      color: Self.color(kind))
                        }
                    }
                }

                Section("Per feature (ms)") {
                    ForEach(model.descriptors) { descriptor in
                        VStack(alignment: .leading, spacing: 6) {
                            Text(descriptor.title).font(.subheadline)
                            ForEach(TransportKind.allCases) { kind in
                                TimingBar(label: kind.displayName,
                                          ms: model.timings[descriptor.id]?[kind],
                                          maxMs: model.maxForFeature(descriptor.id),
                                          color: Self.color(kind))
                            }
                        }
                        .padding(.vertical, 2)
                    }
                }

                if let errorMessage = model.errorMessage {
                    Section("Error") { Text(errorMessage).foregroundStyle(.red) }
                }
            }
            .navigationTitle("Compare")
            .task { await model.loadDescriptorsIfNeeded() }
        }
    }

    static func color(_ kind: TransportKind) -> Color {
        switch kind {
        case .ffi:    return .green
        case .http:   return .orange
        case .sqlite: return .red
        }
    }
}

/// One labeled horizontal bar: label · proportional bar · numeric ms.
struct TimingBar: View {
    let label: String
    let ms: Double?
    let maxMs: Double
    let color: Color

    var body: some View {
        HStack(spacing: 8) {
            Text(label)
                .font(.caption.monospaced())
                .frame(width: 52, alignment: .leading)
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(.quaternary)
                    Capsule().fill(color).frame(width: barWidth(geo.size.width))
                }
            }
            .frame(height: 14)
            Text(ms.map { String(format: "%.2f", $0) } ?? "—")
                .font(.caption2.monospacedDigit())
                .foregroundStyle(.secondary)
                .frame(width: 62, alignment: .trailing)
        }
    }

    private func barWidth(_ total: CGFloat) -> CGFloat {
        guard let ms, maxMs > 0 else { return 0 }
        return max(2, total * CGFloat(ms / maxMs))
    }
}
