import Foundation

/// How a turn currently reads to the user — distinguishes a clean answer from a capped/errored one.
enum TurnOutcome: Sendable { case streaming, answered, stepCapReached, error }

/// One chat turn: the user's query, the streamed/final answer, and (once the turn ends) its honest
/// outcome plus the tool-call spans drained for its strip.
struct ForemanTurn: Identifiable, Sendable {
    let id: UUID
    let query: String
    var answer: String
    var outcome: TurnOutcome
    var toolSteps: Int
    var backend: String?
    var toolSpans: [TraceSpan]

    init(id: UUID = UUID(), query: String, answer: String = "", outcome: TurnOutcome = .streaming,
         toolSteps: Int = 0, backend: String? = nil, toolSpans: [TraceSpan] = []) {
        self.id = id
        self.query = query
        self.answer = answer
        self.outcome = outcome
        self.toolSteps = toolSteps
        self.backend = backend
        self.toolSpans = toolSpans
    }
}

/// Drives the Foreman section: runs one turn at a time over `AgentService`, appending streamed
/// answer fragments to the LAST turn and, on the terminal status fragment, honestly recording how
/// the turn ended (never presenting `.stepCapReached`/`.error` as `.answered`) plus the ACTUAL
/// backend badge read off the wire. After the status arrives, drains the trace ring once for the
/// turn's tool-call strip — see `turnSpans(from:expectedToolSteps:)` for why that correlation is
/// best-effort. Mirrors Android's `AgentViewModel`.
@MainActor
final class ForemanViewModel: ObservableObject {
    @Published var query = ""
    @Published var turns: [ForemanTurn] = []
    @Published var running = false
    @Published var errorMessage: String?

    private let service: AgentService
    private let traceReader: AgentTraceReader
    private var turnTask: Task<Void, Never>?

    init(service: AgentService = FFIAgentService(),
         traceReader: AgentTraceReader = NativeAgentTraceReader()) {
        self.service = service
        self.traceReader = traceReader
    }

    /// Starts a new turn (cancelling any turn still in flight — one active turn at a time) and
    /// returns once it fully resolves: tests await the whole turn, the UI fire-and-forgets from a
    /// button `Task`.
    func ask() async {
        guard let q = QueryInput.sanitize(query) else { return }
        turnTask?.cancel()
        // A prior still-streaming turn (asked again before it finished) is superseded — resolve it
        // honestly here, since its own cancelled task epilogue bails on Task.isCancelled and would
        // otherwise leave it a perpetual spinner. No-op once a turn has already resolved.
        updateLastTurn { if $0.outcome == .streaming { $0.outcome = .error } }

        query = ""
        errorMessage = nil
        running = true
        turns.append(ForemanTurn(query: q))

        let task = Task { @MainActor in
            do {
                for try await fragment in service.run(q) {
                    // Cooperative cancellation means a fragment whose resume was already queued on the
                    // main actor still runs this body after cancel(); without the guard, a superseded
                    // turn's stale text could append into the NEWER turn now occupying the last slot.
                    guard !Task.isCancelled else { break }
                    switch fragment {
                    case .answer(let text):
                        updateLastTurn { $0.answer += text }
                    case .status(let status):
                        await applyStatus(status)
                    }
                }
            } catch {
                // Transport-level failure (e.g. session start rejected) with no status fragment at
                // all — still honestly mark the turn as error, never leave it looking like a clean
                // answer. Skipped when this turn was superseded (see guard below).
                guard !Task.isCancelled else { return }
                updateLastTurn { $0.outcome = .error }
                errorMessage = "Foreman turn failed: \(error.localizedDescription)"
            }
            // A superseded turn (cancelled by a newer ask()) must not touch state that now belongs
            // to the newer turn — its own stream already stopped, and the newer turn owns `running`
            // and the last-turn slot.
            guard !Task.isCancelled else { return }
            // Belt-and-braces for the stuck-spinner root: if the stream completed without ever
            // delivering a terminal status fragment, the last turn is still streaming — mark it
            // error rather than leave a perpetual spinner. No-op when a status already resolved the
            // turn, or the catch above already marked it error.
            updateLastTurn { if $0.outcome == .streaming { $0.outcome = .error } }
            running = false
        }
        turnTask = task
        await task.value
    }

    /// dni_session_cancel-equivalent: stops the in-flight turn (its stream's `onTermination` cancels
    /// and frees the session — never this view model directly). The cancelled turn is resolved HERE:
    /// the cancelled task's own epilogue guards bail on `Task.isCancelled`, so nothing else would ever
    /// move it off `.streaming` — a perpetual spinner. Android parity: a cancelled turn reads as error.
    func cancel() {
        turnTask?.cancel()
        turnTask = nil
        updateLastTurn { if $0.outcome == .streaming { $0.outcome = .error } }
        running = false
    }

    private func applyStatus(_ status: AgentTurnStatus) async {
        let outcome: TurnOutcome
        switch status.stopReason {
        case .Answered: outcome = .answered
        case .StepCapReached: outcome = .stepCapReached
        case .Error: outcome = .error
        }
        // dni_trace_drain is a synchronous native call — hop OFF the main actor for the drain
        // (Android parity: AgentViewModel's withContext(traceDispatcher) hop). Local copy because a
        // detached @Sendable closure can't capture main-actor `self`.
        let reader = traceReader
        let drain = await Task.detached { reader.drain() }.value
        // Superseded while awaiting the drain: the last-turn slot belongs to a newer turn now.
        guard !Task.isCancelled else { return }
        let spans = turnSpans(from: drain, expectedToolSteps: status.toolSteps)
        updateLastTurn {
            $0.outcome = outcome
            $0.toolSteps = status.toolSteps
            $0.backend = status.backend
            $0.toolSpans = spans
        }
    }

    private func updateLastTurn(_ mutate: (inout ForemanTurn) -> Void) {
        guard !turns.isEmpty else { return }
        mutate(&turns[turns.count - 1])
    }
}
