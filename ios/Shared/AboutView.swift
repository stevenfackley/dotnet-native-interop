import SwiftUI

/// The project's story: architecture, why NativeAOT, why each transport, the per-transport tradeoffs,
/// and a live snapshot of the running engine's runtime facts.
struct AboutView: View {
    let infos: [TransportInfo]
    let telemetry: TelemetryService

    @State private var stats: EngineStats?

    private let architecture = """
    iOS app (SwiftUI)
      │  ffi · http · sqlcipher
      ▼
    dni  —  NativeAOT shared library (dni.dylib)
      │  C ABI + 3 transport hosts
      ▼
    DotnetNativeInterop.Engine  (pure .NET, AOT-safe)
    """

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("One NativeAOT .NET library, three interop transports. The same C# / .NET feature "
                         + "catalog runs in-process inside the AOT image; the app compares transport cost "
                         + "and shows the runtime's behaviour live.")
                        .font(.callout)
                }
                .instrumentRow()

                Section("Architecture") {
                    Text(architecture).font(.system(.caption, design: .monospaced))
                }
                .instrumentRow()

                Section("Why NativeAOT") {
                    Text("The engine is compiled ahead-of-time straight to a native binary — no JIT, no "
                         + "runtime install, no separate process. It loads directly into the UI process, so "
                         + "FFI calls are in-memory and there's no JIT warmup (see the Latency jitter view).")
                        .font(.callout)
                }
                .instrumentRow()

                Section("Why these transports") {
                    bullet("Raw-socket HTTP, not Kestrel — ASP.NET Core ships no NativeAOT runtime pack for "
                           + "mobile RIDs, so the HTTP host is a hand-rolled HTTP/1.1 + SSE server.")
                    bullet("SQLCipher, not e_sqlite3 — the default SQLite bundle has no iOS native lib; "
                           + "e_sqlcipher is the only one with iOS static libs, so the store is encrypted at rest for free.")
                    bullet("gRPC is kept in the tree but excluded — no NativeAOT mobile runtime pack.")
                }
                .instrumentRow()

                Section("Live runtime facts") {
                    if let s = stats {
                        LabeledContent("cores", value: "\(s.processorCount)")
                        LabeledContent("managed heap", value: String(format: "%.1f MB", Double(s.heapBytes) / 1_048_576))
                        LabeledContent("GC collections", value: "\(s.gcGen0)/\(s.gcGen1)/\(s.gcGen2)")
                        LabeledContent("threads", value: "\(s.threadCount)")
                        LabeledContent("uptime", value: String(format: "%.0f s", s.uptimeMs / 1000))
                    } else {
                        Text("Reading engine telemetry…").font(.caption).foregroundStyle(Instrument.textSecondary)
                    }
                }
                .instrumentRow()

                ForEach(infos, id: \.id) { info in
                    Section(info.displayName) {
                        Text(info.summary).font(.callout)
                        ForEach(info.features, id: \.self) { feature in
                            Label { Text(feature) } icon: {
                                Image(systemName: "checkmark.circle.fill").foregroundStyle(Instrument.ok)
                            }
                        }
                        ForEach(info.limitations, id: \.self) { limitation in
                            Label { Text(limitation) } icon: {
                                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(Instrument.warn)
                            }
                        }
                    }
                    .instrumentRow()
                }
            }
            .navigationTitle("About")
            .task { stats = try? await telemetry.stats() }
            .instrumentScreen()
        }
    }

    private func bullet(_ text: String) -> some View {
        Label { Text(text) } icon: { Image(systemName: "circle.fill").font(.system(size: 5)) }
    }
}
