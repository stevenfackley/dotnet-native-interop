import SwiftUI

/// Trust inspector: the honest per-transport security posture + an opt-in PQ toggle for the binary
/// transport. Told straight — FFI is an in-process boundary, HTTP is PLAINTEXT loopback, SQLCipher is
/// AES-256 at rest, and the binary transport shows plaintext until a real ML-KEM/ML-DSA handshake
/// negotiates a channel (then its LIVE params appear). Pushed from the Boundary hub.
struct TrustView: View {
    @StateObject private var vm = TrustViewModel()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Instrument.Space.l) {
                PanelHeader("Trust · per-transport security posture")

                if let error = vm.errorMessage {
                    ErrorBanner(message: error) { Task { await vm.refresh() } }
                }

                pqToggleCard

                if let report = vm.report {
                    ForEach(report.transports) { TransportPostureCard(posture: $0) }
                    if let pq = report.binaryPqChannel {
                        PqParamsCard(params: pq)
                    }
                } else if vm.loading {
                    ProgressView().frame(maxWidth: .infinity).tint(Instrument.accent)
                }
            }
            .padding(Instrument.Space.l)
        }
        .instrumentScreen()
        .navigationTitle("Trust")
        .task { await vm.refresh() }
    }

    private var pqToggleCard: some View {
        VStack(alignment: .leading, spacing: Instrument.Space.s) {
            PanelHeader("post-quantum channel · binary transport")
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("ML-KEM-768 · ML-DSA-65 · AES-256-GCM")
                        .font(.subheadline).foregroundStyle(Instrument.textPrimary)
                    Text(vm.negotiating ? "negotiating handshake…" : "opt-in; off = plaintext framed protobuf")
                        .font(.caption)
                        .foregroundStyle(vm.negotiating ? Instrument.warn : Instrument.textSecondary)
                }
                Spacer()
                Toggle("", isOn: Binding(
                    get: { vm.pqRequested },
                    set: { on in Task { await vm.setPqEnabled(on) } }))
                    .labelsHidden()
                    .tint(Instrument.transportBinary)
                    .disabled(vm.negotiating)
            }
            Text("Switching restarts the pb server — its PQ mode is fixed per boot (honest, not hot-swapped).")
                .font(.caption2).foregroundStyle(Instrument.textTertiary)
        }
        .instrumentCard()
    }
}

/// One transport's posture: colored dot + name + a security badge, then the honest wire/detail disclosure.
private struct TransportPostureCard: View {
    let posture: TransportPosture

    var body: some View {
        VStack(alignment: .leading, spacing: Instrument.Space.s) {
            HStack {
                Circle().fill(dotColor).frame(width: 8, height: 8)
                Text(posture.transport.uppercased())
                    .font(Instrument.panelLabel).foregroundStyle(Instrument.textPrimary)
                Spacer()
                SecurityBadge(inProcess: posture.inProcess, encrypted: posture.encrypted)
            }
            Text(posture.wire).font(.footnote).foregroundStyle(Instrument.textSecondary)
            Text(posture.detail).font(.footnote).foregroundStyle(Instrument.textSecondary)
        }
        .instrumentCard()
    }

    // Mirrors Instrument.transport(_:); the "sqlcipher" posture token maps to the sqlite color.
    private var dotColor: Color {
        switch posture.transport.lowercased() {
        case "ffi":                 return Instrument.transport(.ffi)
        case "binary":              return Instrument.transportBinary
        case "http":                return Instrument.transport(.http)
        case "sqlcipher", "sqlite": return Instrument.transport(.sqlite)
        default:                    return Instrument.textSecondary
        }
    }
}

/// IN-PROCESS (ffi) / ENCRYPTED (green) / PLAINTEXT (red) — the honest one-word verdict.
private struct SecurityBadge: View {
    let inProcess: Bool
    let encrypted: Bool

    var body: some View {
        let (label, tint): (String, Color) =
            inProcess ? ("IN-PROCESS", Instrument.textSecondary)
            : encrypted ? ("ENCRYPTED", Instrument.ok)
            : ("PLAINTEXT", Instrument.fail)
        return Text(label)
            .font(Instrument.panelLabel)
            .foregroundStyle(tint)
            .padding(.horizontal, Instrument.Space.s)
            .padding(.vertical, 2)
            .background(tint.opacity(0.14), in: RoundedRectangle(cornerRadius: Instrument.Radius.chip))
    }
}

/// The live negotiated PQ params — only shown when a handshake actually completed.
private struct PqParamsCard: View {
    let params: TrustPqChannelParams

    var body: some View {
        VStack(alignment: .leading, spacing: Instrument.Space.s) {
            PanelHeader("live PQ channel · negotiated")
            HStack(spacing: Instrument.Space.l) {
                StatCell(label: "KEM", value: params.kem, tint: Instrument.transportBinary)
                StatCell(label: "SIG", value: params.sig)
                StatCell(label: "CIPHER", value: params.cipher)
            }
            HStack(spacing: Instrument.Space.l) {
                StatCell(label: "KEM PUB", value: "\(params.kemPublicKeyBytes) B")
                StatCell(label: "CIPHERTEXT", value: "\(params.ciphertextBytes) B")
                StatCell(label: "SECRET", value: "\(params.sharedSecretBytes) B")
            }
            Text(String(format: "handshake %.1f µs", params.handshakeUs))
                .font(Instrument.code).foregroundStyle(Instrument.ok)
        }
        .instrumentCard()
    }
}
