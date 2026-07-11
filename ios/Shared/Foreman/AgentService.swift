import Foundation

/// Errors surfaced by the agent seam itself (session-start rejection). Everything downstream of a
/// successful start arrives as fragments — including engine-side failures, which come back as an
/// honest `.Error` status fragment rather than a thrown error.
enum AgentServiceError: LocalizedError {
    case startFailed(Int)

    var errorDescription: String? {
        switch self {
        case .startFailed(let code): return "The engine couldn't start the Foreman turn (status \(code))."
        }
    }
}

/// Streams one Foreman turn: zero or more answer-text fragments, then exactly one terminal status
/// fragment (see `parseAgentFragment` for the 0x01 discriminator).
protocol AgentService: Sendable {
    func run(_ query: String) -> AsyncThrowingStream<AgentFragment, Error>
}

/// Foreman turn over the in-process FFI (`dni_agent_session_start`). Same Unmanaged-box bridge from
/// the per-token C callback into an async stream as `FFIRagService` — it shares the SAME
/// `dni_token_cb` ABI and the SAME `dni_session_cancel`/`dni_session_free` lifecycle as every other
/// session (no new lifecycle exports). The session is cancelled+freed on stream termination (never
/// inside the callback, which runs on the .NET drain thread — `dni_session_free` blocks on that pump
/// and would deadlock).
final class FFIAgentService: AgentService, @unchecked Sendable {
    private final class Box {
        let continuation: AsyncThrowingStream<AgentFragment, Error>.Continuation
        init(_ c: AsyncThrowingStream<AgentFragment, Error>.Continuation) { self.continuation = c }
    }

    func run(_ query: String) -> AsyncThrowingStream<AgentFragment, Error> {
        AsyncThrowingStream { continuation in
            let box = Box(continuation)
            let userData = Unmanaged.passRetained(box).toOpaque()
            // Capture the pointer as a Sendable integer bit-pattern for the @Sendable onTermination
            // closure (Swift 6 strict concurrency rejects capturing the raw pointer directly).
            let userDataBits = UInt(bitPattern: userData)

            let callback: @convention(c) (UnsafeMutableRawPointer?, Int32, UnsafePointer<CChar>?, Int32) -> Void = { ud, _, text, isFinal in
                guard let ud else { return }
                let box = Unmanaged<Box>.fromOpaque(ud).takeUnretainedValue()
                if let text, text.pointee != 0 {
                    // parseAgentFragment throws only while JSON-decoding a status fragment (a
                    // malformed terminal marker). Contain it: a @convention(c) callback can't rethrow
                    // across the C boundary, and dropping the fragment would leave the turn stuck at
                    // streaming forever (a silent-broken spinner, against the repo's DNA). Instead
                    // surface an honest Error status so a bad terminal fragment shows as an errored
                    // turn — same rationale as Android's AgentService.kt.
                    let fragment = (try? parseAgentFragment(String(cString: text)))
                        ?? .status(AgentTurnStatus(stopReason: .Error, toolSteps: 0,
                                                   backend: "unknown (status fragment unparseable)"))
                    box.continuation.yield(fragment)
                }
                // Terminal marker per abi/dni.h: an EMPTY fragment with is_final=1 — just finish.
                if isFinal != 0 { box.continuation.finish() }
            }

            let sessionId = query.withCString { q in
                dni_agent_session_start(q, callback, userData)
            }

            guard sessionId > 0 else {
                continuation.finish(throwing: AgentServiceError.startFailed(Int(sessionId)))
                Unmanaged<Box>.fromOpaque(userData).release()
                return
            }

            // Cancel+free must run OFF the terminating thread. `onTermination` fires SYNCHRONOUSLY from
            // `continuation.finish()`, which on normal completion is called INSIDE the @convention(c)
            // callback on the .NET drain thread — and `dni_session_free` blocks on that same pump, so
            // freeing there risks a deadlock the moment the engine's free joins the drain task. On the
            // user-cancel path it would instead fire on the MainActor and stall the UI. A detached task
            // hops both off the terminating thread. (FFIRagService has the same latent pattern inline;
            // left there to scope this change, flagged for a follow-up.)
            continuation.onTermination = { _ in
                Task.detached {
                    _ = dni_session_cancel(sessionId)
                    _ = dni_session_free(sessionId)
                    if let p = UnsafeMutableRawPointer(bitPattern: userDataBits) {
                        Unmanaged<Box>.fromOpaque(p).release()
                    }
                }
            }
        }
    }
}

/// Deterministic scripted turn for tests/previews — no FFI, no engine, no device needed. Yields the
/// scripted fragments in order, then either finishes cleanly or throws `failure` (the shape a
/// rejected session start takes in `FFIAgentService`).
struct MockAgentService: AgentService {
    var script: [AgentFragment] = [
        .answer("Use filter OF-2231 "),
        .answer("and torque the drain plug to 18 Nm."),
        .status(AgentTurnStatus(stopReason: .Answered, toolSteps: 1,
                                backend: "scripted routing — no on-device LLM present")),
    ]
    var failure: AgentServiceError? = nil

    func run(_ query: String) -> AsyncThrowingStream<AgentFragment, Error> {
        AsyncThrowingStream { continuation in
            for fragment in script { continuation.yield(fragment) }
            if let failure {
                continuation.finish(throwing: failure)
            } else {
                continuation.finish()
            }
        }
    }
}
