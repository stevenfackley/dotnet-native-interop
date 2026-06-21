import SwiftUI

/// The panel shown below the swimlane for the selected inspector segment. Each reads the VM.
struct BoundaryInspectorPanel: View {
    @ObservedObject var vm: BoundaryViewModel

    var body: some View {
        Group {
            switch vm.inspector {
            case .bytes: BytesPanel(echo: vm.echo)
            case .timing: TimingPanel(timing: vm.timing)
            case .memory: MemoryPanel(vm: vm)
            case .threads: ThreadsPanel(vm: vm)
            case .abi: AbiPanel(preset: vm.preset)
            case .error: ErrorContainmentPanel(thrown: vm.thrown)
            }
        }
        .instrumentCard()
    }
}

private struct BytesPanel: View {
    let echo: BoundaryEcho?
    var body: some View {
        VStack(alignment: .leading, spacing: Instrument.Space.s) {
            PanelHeader("bytes · marshalling")
            if let echo {
                Text(echo.bytesHex.chunked(2).joined(separator: " "))
                    .font(Instrument.code).foregroundStyle(Instrument.accent)
                    .textSelection(.enabled)
                HStack {
                    StatCell(label: "decoded", value: "\"\(echo.decoded.prefix(24))\"")
                    StatCell(label: "len", value: "\(echo.len) B")
                    StatCell(label: "ptr in", value: echo.ptrIn)
                }
            } else {
                Text("Run echo to inspect the UTF-8 bytes.").font(.footnote).foregroundStyle(Instrument.textTertiary)
            }
        }
    }
}

private struct TimingPanel: View {
    let timing: PhaseTiming
    var body: some View {
        VStack(alignment: .leading, spacing: Instrument.Space.s) {
            PanelHeader("µs · per phase")
            HStack {
                StatCell(label: "marshal", value: fmt(timing.marshalUs))
                StatCell(label: "cross", value: fmt(timing.crossUs))
                StatCell(label: "execute·native", value: fmt(timing.executeUs), tint: Instrument.ok)
            }
            HStack {
                StatCell(label: "callback", value: fmt(timing.callbackUs))
                StatCell(label: "free", value: fmt(timing.freeUs))
                StatCell(label: "total", value: fmt(timing.totalUs), tint: Instrument.accent)
            }
            Text("marshal/cross/free are frontend-measured; execute is native (dni reports executeUs).")
                .font(.caption2).foregroundStyle(Instrument.textTertiary)
        }
    }
    private func fmt(_ us: Double) -> String { String(format: "%.1f µs", us) }
}

private struct MemoryPanel: View {
    @ObservedObject var vm: BoundaryViewModel
    var body: some View {
        VStack(alignment: .leading, spacing: Instrument.Space.s) {
            PanelHeader("memory · ownership ledger")
            ForEach(vm.ledger) { row in
                HStack {
                    Text(row.buffer).font(Instrument.code).foregroundStyle(Instrument.textPrimary)
                    Spacer()
                    Text("\(row.bytes) B").font(Instrument.code).foregroundStyle(Instrument.textSecondary)
                    Text(row.freed ? "freed" : "leaked")
                        .font(Instrument.panelLabel)
                        .foregroundStyle(row.freed ? Instrument.ok : Instrument.fail)
                }
                Text("alloc \(row.allocatedBy) → free \(row.freedBy)")
                    .font(.caption2).foregroundStyle(Instrument.textTertiary)
            }
            Divider().overlay(Instrument.hairline)
            HStack {
                Toggle("simulate missing free", isOn: $vm.skipFree)
                    .font(.footnote).tint(Instrument.fail)
                Spacer()
                StatCell(label: "outstanding", value: "\(vm.outstandingBytes) B",
                         tint: vm.outstandingBytes > 0 ? Instrument.fail : Instrument.ok)
            }
            if vm.outstandingBytes > 0 {
                Button("Reset (free leaked)") { vm.resetLeak() }
                    .font(.footnote.weight(.semibold)).buttonStyle(.bordered).tint(Instrument.accent)
            }
        }
    }
}

private struct ThreadsPanel: View {
    @ObservedObject var vm: BoundaryViewModel
    var body: some View {
        VStack(alignment: .leading, spacing: Instrument.Space.s) {
            PanelHeader("threads · the hop")
            HStack {
                StatCell(label: "caller (UI)", value: "#\(vm.callerThreadId)")
                StatCell(label: "managed callback",
                         value: vm.callbackThreadId.map { "#\($0)" } ?? "—",
                         tint: Instrument.warn)
            }
            Label("Callback fires on a .NET background thread — the host must hop to @MainActor.",
                  systemImage: "exclamationmark.triangle.fill")
                .font(.caption).foregroundStyle(Instrument.warn)
            if !vm.streamTokens.isEmpty {
                Text("\(vm.streamTokens.count) tokens · last @ \(vm.streamTokens.last?.elapsedUs ?? 0) µs")
                    .font(Instrument.code).foregroundStyle(Instrument.textSecondary)
            }
        }
    }
}

private struct AbiPanel: View {
    let preset: BoundaryPreset
    private var rows: [(String, String, String)] {
        // (C ABI, Swift binding, note). @_silgen_name/@convention(c): Swift 5+; older toolchains use a
        // bridging-header decl. [UnmanagedCallersOnly]/delegate* unmanaged: .NET 5+ / C# 9+.
        switch preset {
        case .stream:
            return [("dni_ffi_stream_start(const char*, int32_t, dni_trace_cb, void*)",
                     "dni_ffi_stream_start(_:_:_:_:) -> Int64", "session id > 0"),
                    ("dni_trace_cb(void*, int32_t, const char*, int32_t, int64_t, int64_t)",
                     "@convention(c) (UnsafeMutableRawPointer?, Int32, UnsafePointer<CChar>?, Int32, Int64, Int64) -> Void",
                     "extended: +threadId +elapsedUs")]
        case .exception:
            return [("dni_ffi_throw(void) -> const char*", "dni_ffi_throw() -> UnsafePointer<CChar>?", "{caught,type,message,status}")]
        default:
            return [("dni_ffi_echo(const char*, int32_t) -> const char*",
                     "dni_ffi_echo(_:_:) -> UnsafePointer<CChar>?", "caller frees via dni_string_free")]
        }
    }
    var body: some View {
        VStack(alignment: .leading, spacing: Instrument.Space.s) {
            PanelHeader("ABI · C ⇄ Swift")
            ForEach(rows, id: \.0) { c, swift, note in
                VStack(alignment: .leading, spacing: 2) {
                    Text(c).font(Instrument.code).foregroundStyle(Instrument.accent)
                    Text(swift).font(Instrument.code).foregroundStyle(Instrument.textSecondary)
                    Text(note).font(.caption2).foregroundStyle(Instrument.textTertiary)
                }
            }
        }
    }
}

private struct ErrorContainmentPanel: View {
    let thrown: BoundaryThrow?
    var body: some View {
        VStack(alignment: .leading, spacing: Instrument.Space.s) {
            PanelHeader("error · containment")
            if let thrown {
                Label("contained at the boundary — no crash", systemImage: "checkmark.shield.fill")
                    .font(.footnote).foregroundStyle(Instrument.ok)
                StatCell(label: "type", value: thrown.type)
                StatCell(label: "status", value: "\(thrown.status)", tint: Instrument.warn)
                Text(thrown.message).font(.footnote).foregroundStyle(Instrument.textSecondary)
            } else {
                Text("Run the throw preset: a managed exception is caught at the ABI and returned as a status.")
                    .font(.footnote).foregroundStyle(Instrument.textTertiary)
            }
        }
    }
}

private extension String {
    /// Split into fixed-size chunks (for hex pairs).
    func chunked(_ size: Int) -> [String] {
        var out: [String] = []
        var i = startIndex
        while i < endIndex {
            let j = index(i, offsetBy: size, limitedBy: endIndex) ?? endIndex
            out.append(String(self[i..<j])); i = j
        }
        return out
    }
}
