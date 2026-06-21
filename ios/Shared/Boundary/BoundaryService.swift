import Foundation

enum BoundaryServiceError: Error { case nullResult, startFailed(Int) }

/// The single seam the Boundary screen talks to; `FFIBoundaryService` is the real C-ABI impl,
/// `MockBoundaryService` backs tests and previews.
protocol BoundaryService: Sendable {
    func echo(_ input: String, skipFree: Bool) async throws -> BoundaryEchoTrace
    func throwDemo() async throws -> BoundaryThrow
    func stream(_ prompt: String, maxTokens: Int) -> AsyncThrowingStream<BoundaryStreamToken, Error>
}

/// Microseconds between two ContinuousClock instants (ContinuousClock: iOS 16+;
/// pre-iOS 16 alternative: DispatchTime.now().uptimeNanoseconds deltas / 1000).
private func microseconds(_ a: ContinuousClock.Instant, _ b: ContinuousClock.Instant) -> Double {
    let d = a.duration(to: b)
    return Double(d.components.seconds) * 1_000_000 + Double(d.components.attoseconds) / 1e12
}

/// BoundaryService over the in-process C ABI (Pattern 3 — boundary instrumentation).
struct FFIBoundaryService: BoundaryService {

    // MARK: echo / pixels — synchronous, byte + timing inspector
    func echo(_ input: String, skipFree: Bool) async throws -> BoundaryEchoTrace {
        try await Task.detached(priority: .userInitiated) { () throws -> BoundaryEchoTrace in
            var tid: UInt64 = 0
            pthread_threadid_np(nil, &tid)
            let clock = ContinuousClock()

            let mStart = clock.now
            let bytes = Array(input.utf8)
            let mEnd = clock.now

            let cStart = clock.now
            let ptr: UnsafePointer<CChar>? = bytes.withUnsafeBytes { raw in
                dni_ffi_echo(raw.baseAddress?.assumingMemoryBound(to: CChar.self), Int32(bytes.count))
            }
            let cEnd = clock.now
            guard let ptr else { throw BoundaryServiceError.nullResult }
            let json = String(cString: ptr)

            let fStart = clock.now
            // Leak demo: the CALLER owns the free. Skipping it is what makes the outstanding-bytes counter climb.
            if !skipFree { dni_string_free(ptr) }
            let fEnd = clock.now

            let echo = try JSONDecoder().decode(BoundaryEcho.self, from: Data(json.utf8))
            let timing = PhaseTiming(
                marshalUs: microseconds(mStart, mEnd),
                crossUs: max(0, microseconds(cStart, cEnd) - echo.executeUs), // round-trip minus native execute
                executeUs: echo.executeUs,
                callbackUs: 0,
                freeUs: microseconds(fStart, fEnd))
            return BoundaryEchoTrace(echo: echo, timing: timing, callerThreadId: tid, leakedFree: skipFree)
        }.value
    }

    // MARK: throw — contained managed exception
    func throwDemo() async throws -> BoundaryThrow {
        try await Task.detached(priority: .userInitiated) { () throws -> BoundaryThrow in
            guard let ptr = dni_ffi_throw() else { throw BoundaryServiceError.nullResult }
            defer { dni_string_free(ptr) }
            return try JSONDecoder().decode(BoundaryThrow.self, from: Data(String(cString: ptr).utf8))
        }.value
    }

    // MARK: stream — the off-UI-thread callback hop
    private final class StreamBox {
        let continuation: AsyncThrowingStream<BoundaryStreamToken, Error>.Continuation
        init(_ c: AsyncThrowingStream<BoundaryStreamToken, Error>.Continuation) { self.continuation = c }
    }

    func stream(_ prompt: String, maxTokens: Int) -> AsyncThrowingStream<BoundaryStreamToken, Error> {
        AsyncThrowingStream { continuation in
            let box = StreamBox(continuation)
            let userData = Unmanaged.passRetained(box).toOpaque()
            let userDataBits = UInt(bitPattern: userData) // Sendable bit-pattern for the @Sendable onTermination

            // dni_trace_cb: like dni_token_cb but with (int64 managedThreadId, int64 elapsedUs) appended.
            // @convention(c) closure capturing nothing: required to pass as a C function pointer (Swift 5+).
            let callback: @convention(c) (UnsafeMutableRawPointer?, Int32, UnsafePointer<CChar>?, Int32, Int64, Int64) -> Void = { ud, index, text, isFinal, threadId, elapsedUs in
                guard let ud else { return }
                let box = Unmanaged<StreamBox>.fromOpaque(ud).takeUnretainedValue()
                let token = BoundaryStreamToken(
                    index: Int(index),
                    text: text.map { String(cString: $0) } ?? "",
                    isFinal: isFinal != 0,
                    managedThreadId: Int(threadId),
                    elapsedUs: Int(elapsedUs))
                box.continuation.yield(token)
                if isFinal != 0 { box.continuation.finish() }
            }

            let sessionId = prompt.withCString { p in
                dni_ffi_stream_start(p, Int32(maxTokens), callback, userData)
            }
            guard sessionId > 0 else {
                continuation.finish(throwing: BoundaryServiceError.startFailed(Int(sessionId)))
                Unmanaged<StreamBox>.fromOpaque(userData).release()
                return
            }
            // Cancel+free OUTSIDE the callback: dni_session_free blocks on the .NET drain thread the callback
            // runs on, so freeing there would deadlock (same rule as FFIRagService).
            continuation.onTermination = { _ in
                _ = dni_session_cancel(sessionId)
                _ = dni_session_free(sessionId)
                if let p = UnsafeMutableRawPointer(bitPattern: userDataBits) {
                    Unmanaged<StreamBox>.fromOpaque(p).release()
                }
            }
        }
    }
}

/// Deterministic mock for tests/previews — no FFI, no device needed.
struct MockBoundaryService: BoundaryService {
    func echo(_ input: String, skipFree: Bool) async throws -> BoundaryEchoTrace {
        let hex = input.utf8.map { String(format: "%02X", $0) }.joined()
        let echo = BoundaryEcho(bytesHex: hex, len: input.utf8.count, decoded: input,
                                managedThreadId: 7, executeUs: 4.2, ptrIn: "0x1000")
        let timing = PhaseTiming(marshalUs: 1.1, crossUs: 2.0, executeUs: 4.2, callbackUs: 0, freeUs: 0.5)
        return BoundaryEchoTrace(echo: echo, timing: timing, callerThreadId: 1, leakedFree: skipFree)
    }
    func throwDemo() async throws -> BoundaryThrow {
        BoundaryThrow(caught: true, type: "System.InvalidOperationException",
                      message: "Boundary demo: managed exception crossing prevented.", status: -5)
    }
    func stream(_ prompt: String, maxTokens: Int) -> AsyncThrowingStream<BoundaryStreamToken, Error> {
        AsyncThrowingStream { c in
            Task {
                for i in 0..<3 {
                    c.yield(BoundaryStreamToken(index: i, text: "tok\(i) ", isFinal: false, managedThreadId: 9, elapsedUs: i * 800))
                }
                c.yield(BoundaryStreamToken(index: 3, text: "", isFinal: true, managedThreadId: 9, elapsedUs: 2400))
                c.finish()
            }
        }
    }
}
