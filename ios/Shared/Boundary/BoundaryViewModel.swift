import Foundation

@MainActor
final class BoundaryViewModel: ObservableObject {
    @Published var preset: BoundaryPreset = .echo
    @Published var inspector: BoundaryInspector = .timing
    @Published var input = "Hello"
    @Published var running = false
    @Published var autoStepping = false
    @Published var activePhase: BoundaryPhase?
    @Published var timing = PhaseTiming()
    @Published var echo: BoundaryEcho?
    @Published var thrown: BoundaryThrow?
    @Published var streamTokens: [BoundaryStreamToken] = []
    @Published var ledger: [OwnershipEntry] = []
    @Published var outstandingBytes = 0
    @Published var skipFree = false
    @Published var callerThreadId: UInt64 = 0
    @Published var errorMessage: String?

    private let service: BoundaryService
    private var streamTask: Task<Void, Never>?
    init(service: BoundaryService) { self.service = service }

    /// The thread the managed callback/work ran on (stream's last token, else echo's managed id).
    var callbackThreadId: Int? { streamTokens.last?.managedThreadId ?? echo?.managedThreadId }

    func run() async {
        errorMessage = nil
        switch preset {
        case .echo, .feature, .pixels: await runEcho(large: preset == .pixels)
        case .exception: await runThrow()
        case .stream: runStream()
        }
    }

    private func runEcho(large: Bool) async {
        running = true
        defer { running = false }
        // pixels: a large (image-sized) payload so big-buffer marshalling/ownership stays on the instrumented path.
        let payload = large ? String(repeating: "▇", count: 4096) : input
        do {
            let trace = try await service.echo(payload, skipFree: skipFree)
            echo = trace.echo
            timing = trace.timing
            callerThreadId = trace.callerThreadId
            updateLedger(resultBytes: trace.echo.len, freed: !trace.leakedFree)
        } catch {
            errorMessage = "Echo over FFI failed: \(error.localizedDescription)"
        }
    }

    private func runThrow() async {
        running = true
        defer { running = false }
        do {
            thrown = try await service.throwDemo()   // returns the CONTAINED exception — no crash, no throw here.
            inspector = .error
        } catch {
            errorMessage = "Throw demo failed: \(error.localizedDescription)"
        }
    }

    private func runStream() {
        streamTask?.cancel()
        streamTokens = []
        running = true
        inspector = .threads
        let prompt = input.isEmpty ? "stream demo" : input
        let tokens = service.stream(prompt, maxTokens: 24)
        streamTask = Task { @MainActor in
            do {
                for try await token in tokens {
                    if !token.text.isEmpty { streamTokens.append(token) }
                    if token.isFinal { break }
                }
            } catch {
                errorMessage = "Stream over FFI failed: \(error.localizedDescription)"
            }
            running = false
        }
    }

    func stop() {
        streamTask?.cancel(); streamTask = nil
        running = false
    }

    private func updateLedger(resultBytes: Int, freed: Bool) {
        ledger = [
            OwnershipEntry(buffer: "input utf8", allocatedBy: "host", freedBy: "host", bytes: input.utf8.count, freed: true),
            OwnershipEntry(buffer: "result json", allocatedBy: ".NET",
                           freedBy: freed ? "host · dni_string_free" : "— (leaked)", bytes: resultBytes, freed: freed),
        ]
        outstandingBytes = freed ? 0 : outstandingBytes + resultBytes
    }

    func resetLeak() { outstandingBytes = 0; skipFree = false }

    /// Auto-step: walk the phases on a cadence, auto-selecting each phase's inspector segment.
    func autoStep() async {
        autoStepping = true
        for phase in BoundaryPhase.allCases {
            activePhase = phase
            inspector = segment(for: phase)
            // Task.sleep(for:): iOS 16+. Pre-iOS 16: try? await Task.sleep(nanoseconds: 600_000_000).
            try? await Task.sleep(for: .milliseconds(600))
        }
        activePhase = nil
        autoStepping = false
    }

    private func segment(for phase: BoundaryPhase) -> BoundaryInspector {
        switch phase {
        case .marshal: .bytes
        case .cross: .abi
        case .execute: .timing
        case .callback: .threads
        case .free: .memory
        }
    }
}
