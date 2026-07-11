import Foundation

/// Drives the Trust inspector. Reads the engine's `trust~posture` (an engine-global command, so any
/// transport can answer it — the in-process FFI catalog is cheapest) and lets the user negotiate the
/// opt-in PQ channel on the binary transport.
///
/// The PQ toggle is HONEST end-to-end: enabling it flips `PbTransport` to secure mode (restarting the pb
/// server in require-PQ) and runs one binary request so a real ML-KEM/ML-DSA handshake completes and the
/// server publishes its live params; only then does the re-fetched posture flip the binary transport to
/// "encrypted" with those params. No fake green lock — plaintext until a handshake genuinely negotiates.
@MainActor
final class TrustViewModel: ObservableObject {
    @Published var report: TrustPostureReport?
    @Published var loading = false
    @Published var negotiating = false
    @Published var pqRequested = false
    @Published var errorMessage: String?

    private let catalog: FeatureService         // FFI — answers the engine-global trust~posture, cheapest
    private let binaryCatalog: FeatureService   // pb — used to force a real handshake when enabling PQ

    init(catalog: FeatureService = FFIFeatureService(),
         binaryCatalog: FeatureService = PbFeatureService()) {
        self.catalog = catalog
        self.binaryCatalog = binaryCatalog
    }

    /// Re-reads the current posture from the engine.
    func refresh() async {
        loading = true
        errorMessage = nil
        do {
            let result = try await catalog.run("trust~posture")
            report = try JSONDecode.decode(TrustPostureReport.self, from: result.result)
        } catch {
            errorMessage = "trust~posture failed: \(error.localizedDescription)"
        }
        loading = false
    }

    /// Turns the PQ channel on/off. On: flip the transport to secure and run one request so a real
    /// handshake completes (and the server publishes live params); then refresh so the posture shows them.
    /// Off: drop back to plaintext. Any failure is surfaced AND the toggle reverts — the switch must never
    /// read ON while the refreshed posture reads PLAINTEXT (repo honesty rule).
    func setPqEnabled(_ enabled: Bool) async {
        let previous = pqRequested
        negotiating = true
        pqRequested = enabled
        errorMessage = nil
        do {
            PbTransport.shared.setSecure(enabled)
            // Force a connection so the handshake runs (publishing live params) or plaintext resumes.
            _ = try await binaryCatalog.run("ping")
        } catch {
            errorMessage = "PQ negotiation failed: \(error.localizedDescription)"
            pqRequested = previous
        }
        negotiating = false
        await refresh()
    }
}
