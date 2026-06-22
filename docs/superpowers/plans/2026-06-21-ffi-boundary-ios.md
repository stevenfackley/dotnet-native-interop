# FFI Boundary — iOS Screen (Plan B) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a first-class **Boundary** tab to the iOS app (SwiftUI) that traces one FFI call end-to-end — swimlane hero + segmented inspector (Approach A · A1) — over the three Plan A exports.

**Architecture:** New self-contained `ios/Shared/Boundary/` module: Codable models → `BoundaryService`/`FFIBoundaryService` (mirrors the existing `FFIFeatureService` for the synchronous calls and `FFIRagService`'s `Unmanaged`-box `@convention(c)` bridge for the streaming callback) → `@MainActor BoundaryViewModel` → `SwimlaneView` + segment panels + `BoundaryView`, wired into `RootTabView`. Logic (decode + VM state) is unit-tested with XCTest against a `MockBoundaryService`; the FFI/visual layers are verified on device.

**Tech Stack:** Swift 5.9 / SwiftUI, iOS 17 deployment target, the in-process C ABI via the existing `BridgingHeader.h` (already `#import`s `dni.h`), the "Precision Instrument" design system (`Instrument` tokens + `InstrumentComponents`).

**Spec:** `docs/superpowers/specs/2026-06-21-ffi-boundary-showcase-design.md`. **Depends on Plan A** (the `dni_ffi_echo` / `dni_ffi_throw` / `dni_ffi_stream_start` exports + `dni_trace_cb`, already implemented). Plan B Task 8 rebuilds the iOS framework so Swift sees the new exports.

**Branch:** continue on `feat/ffi-boundary-showcase`.

**Parity note:** Adding a 9th tab temporarily overflows the iOS tab bar into "More" — accepted per the additive-expansion order (Boundary lands now; the 5-tab collapse is spec 2). Boundary is placed **first** so the hero is in the visible set and is the default landing tab.

---

## File Structure

| File | Create/Modify | Responsibility |
|------|---------------|----------------|
| `ios/Shared/Boundary/BoundaryModels.swift` | Create | Codable `BoundaryEcho`/`BoundaryThrow`, `BoundaryStreamToken`, `PhaseTiming`, `OwnershipEntry`, `BoundaryEchoTrace`, and the `BoundaryPreset`/`BoundaryPhase`/`BoundaryInspector` enums. |
| `ios/Shared/Boundary/BoundaryService.swift` | Create | `BoundaryService` protocol + `FFIBoundaryService` (echo/throw/stream over the C ABI) + `MockBoundaryService`. |
| `ios/Shared/Boundary/BoundaryViewModel.swift` | Create | `@MainActor` state machine: presets, run/auto-step, timing, ledger, leak toggle, errors. |
| `ios/Shared/Boundary/SwimlaneView.swift` | Create | The hero: lane diagram + traveling token + the callback thread-hop. |
| `ios/Shared/Boundary/BoundaryInspectorPanels.swift` | Create | The six segment panels (bytes/µs/memory/threads/ABI/err). |
| `ios/Shared/Boundary/BoundaryView.swift` | Create | The A1 screen assembling header, preset row, swimlane, controls, segmented inspector. |
| `ios/Tests/BoundaryModelsTests.swift` | Create | Decode tests (no FFI). |
| `ios/Tests/BoundaryViewModelTests.swift` | Create | VM-logic tests against the mock (no FFI). |
| `ios/Shared/RootTabView.swift` | Modify | Register the Boundary tab (first) + own its view model. |

Files under `ios/Shared/` are auto-included by xcodegen's `- path: Shared` glob — no `project.yml` edit needed. `ios/Tests/` is the existing `DotnetNativeInteropTests` target.

---

## Task 1: Models + decode tests

**Files:**
- Create: `ios/Shared/Boundary/BoundaryModels.swift`
- Test: `ios/Tests/BoundaryModelsTests.swift`

- [ ] **Step 1: Write the failing decode tests**

Create `ios/Tests/BoundaryModelsTests.swift`:
```swift
import XCTest
@testable import DotnetNativeInteropUnified

final class BoundaryModelsTests: XCTestCase {
    func testBoundaryEchoDecodesCamelCase() throws {
        let json = #"{"bytesHex":"48656C6C6F","len":5,"decoded":"Hello","managedThreadId":2,"executeUs":3.4,"ptrIn":"0x16d4e08"}"#
        let echo = try JSONDecoder().decode(BoundaryEcho.self, from: Data(json.utf8))
        XCTAssertEqual(echo.decoded, "Hello")
        XCTAssertEqual(echo.bytesHex, "48656C6C6F")
        XCTAssertEqual(echo.len, 5)
        XCTAssertEqual(echo.managedThreadId, 2)
        XCTAssertEqual(echo.executeUs, 3.4, accuracy: 1e-9)
        XCTAssertEqual(echo.ptrIn, "0x16d4e08")
    }

    func testBoundaryThrowDecodes() throws {
        let json = #"{"caught":true,"type":"System.InvalidOperationException","message":"contained","status":-5}"#
        let t = try JSONDecoder().decode(BoundaryThrow.self, from: Data(json.utf8))
        XCTAssertTrue(t.caught)
        XCTAssertEqual(t.status, -5)
        XCTAssertTrue(t.type.contains("InvalidOperationException"))
    }

    func testPhaseTimingTotal() {
        let t = PhaseTiming(marshalUs: 1, crossUs: 2, executeUs: 4, callbackUs: 0, freeUs: 1)
        XCTAssertEqual(t.totalUs, 8, accuracy: 1e-9)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run (on the Mac mini): `xcodebuild -project ios/DotnetNativeInteropApp.xcodeproj -scheme DotnetNativeInteropTests test -destination 'platform=iOS Simulator,name=iPhone 16'`
Expected: FAIL — `cannot find 'BoundaryEcho' in scope` (types not written yet).

- [ ] **Step 3: Write the models**

Create `ios/Shared/Boundary/BoundaryModels.swift`:
```swift
import SwiftUI

/// The traced-call presets in the Boundary preset row.
enum BoundaryPreset: String, CaseIterable, Identifiable {
    case echo, feature, pixels, stream, exception
    var id: String { rawValue }
    /// Row label (the `exception` case is shown as "throw" — `throw` is a Swift keyword).
    var title: String {
        switch self {                       // switch expression: Swift 5.9+. Older: add `return` to each arm.
        case .echo: "echo"
        case .feature: "feature"
        case .pixels: "pixels"
        case .stream: "stream"
        case .exception: "throw"
        }
    }
}

/// Lifecycle phases of one FFI call, in order, for the swimlane + Auto-step.
enum BoundaryPhase: String, CaseIterable, Identifiable {
    case marshal, cross, execute, callback, free
    var id: String { rawValue }
}

/// The inspector segment shown below the swimlane.
enum BoundaryInspector: String, CaseIterable, Identifiable {
    case bytes, timing, memory, threads, abi, error
    var id: String { rawValue }
    var label: String {
        switch self {
        case .bytes: "bytes"
        case .timing: "µs"
        case .memory: "memory"
        case .threads: "threads"
        case .abi: "ABI"
        case .error: "err"
        }
    }
}

/// `dni_ffi_echo` result — native-measured. camelCase auto-maps to the C# record JSON.
struct BoundaryEcho: Codable, Sendable {
    let bytesHex: String
    let len: Int
    let decoded: String
    let managedThreadId: Int
    let executeUs: Double
    let ptrIn: String
}

/// `dni_ffi_throw` result — a managed exception contained at the boundary.
struct BoundaryThrow: Codable, Sendable {
    let caught: Bool
    let type: String
    let message: String
    let status: Int
}

/// One token from `dni_ffi_stream_start`'s extended callback (adds threadId + elapsedUs vs dni_token_cb).
struct BoundaryStreamToken: Sendable {
    let index: Int
    let text: String
    let isFinal: Bool
    let managedThreadId: Int
    let elapsedUs: Int
}

/// Per-phase µs split. marshal/cross/free are frontend-measured; execute is native (`executeUs`).
struct PhaseTiming: Sendable, Equatable {
    var marshalUs: Double = 0
    var crossUs: Double = 0
    var executeUs: Double = 0
    var callbackUs: Double = 0
    var freeUs: Double = 0
    var totalUs: Double { marshalUs + crossUs + executeUs + callbackUs + freeUs }
}

/// One row of the memory-ownership ledger.
struct OwnershipEntry: Identifiable, Sendable {
    let id = UUID()
    let buffer: String
    let allocatedBy: String
    let freedBy: String
    let bytes: Int
    let freed: Bool
}

/// Full result of one echo trace: native echo + frontend timing + thread/leak context.
struct BoundaryEchoTrace: Sendable {
    let echo: BoundaryEcho
    let timing: PhaseTiming
    let callerThreadId: UInt64
    let leakedFree: Bool
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `xcodebuild -project ios/DotnetNativeInteropApp.xcodeproj -scheme DotnetNativeInteropTests test -destination 'platform=iOS Simulator,name=iPhone 16'`
Expected: PASS — all three `BoundaryModelsTests` green.

- [ ] **Step 5: Commit**

```powershell
git add ios/Shared/Boundary/BoundaryModels.swift ios/Tests/BoundaryModelsTests.swift
git commit -m "feat(ffi-boundary): iOS Boundary models + decode tests"
```

---

## Task 2: BoundaryService (FFI echo/throw/stream + mock)

**Files:**
- Create: `ios/Shared/Boundary/BoundaryService.swift`

- [ ] **Step 1: Write the service**

Create `ios/Shared/Boundary/BoundaryService.swift`:
```swift
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
```

- [ ] **Step 2: Build the app target to verify the service compiles (Mac)**

Run: `cd ios && xcodegen generate && xcodebuild -project DotnetNativeInteropApp.xcodeproj -scheme DotnetNativeInteropUnified -destination 'platform=iOS Simulator,name=iPhone 16' build`
Expected: PASS — the `@convention(c)` extended callback, the `Unmanaged` box, and the three `dni_ffi_*` symbols resolve (Plan A's `dni.h` is already `#import`ed by `BridgingHeader.h`; Task 8 rebuilds the framework binary, but the simulator build links the header-declared symbols from the existing xcframework once rebuilt — if symbols are unresolved here, run Task 8's framework rebuild first).

- [ ] **Step 3: Commit**

```powershell
git add ios/Shared/Boundary/BoundaryService.swift
git commit -m "feat(ffi-boundary): iOS BoundaryService (echo/throw/stream) + mock"
```

---

## Task 3: BoundaryViewModel + tests

**Files:**
- Create: `ios/Shared/Boundary/BoundaryViewModel.swift`
- Test: `ios/Tests/BoundaryViewModelTests.swift`

- [ ] **Step 1: Write the failing VM tests**

Create `ios/Tests/BoundaryViewModelTests.swift`:
```swift
import XCTest
@testable import DotnetNativeInteropUnified

@MainActor
final class BoundaryViewModelTests: XCTestCase {
    func testEchoPopulatesTimingAndLedger() async {
        let vm = BoundaryViewModel(service: MockBoundaryService())
        vm.preset = .echo
        vm.input = "Hello"
        await vm.run()
        XCTAssertEqual(vm.echo?.decoded, "Hello")
        XCTAssertGreaterThan(vm.timing.totalUs, 0)
        XCTAssertEqual(vm.ledger.count, 2)
        XCTAssertEqual(vm.outstandingBytes, 0)
        XCTAssertNil(vm.errorMessage)
    }

    func testLeakAccumulatesOutstandingBytes() async {
        let vm = BoundaryViewModel(service: MockBoundaryService())
        vm.preset = .echo
        vm.skipFree = true
        await vm.run()
        XCTAssertGreaterThan(vm.outstandingBytes, 0)
        XCTAssertEqual(vm.ledger.last?.freed, false)
    }

    func testThrowIsContained() async {
        let vm = BoundaryViewModel(service: MockBoundaryService())
        vm.preset = .exception
        await vm.run()
        XCTAssertEqual(vm.thrown?.caught, true)
        XCTAssertEqual(vm.thrown?.status, -5)
        XCTAssertNil(vm.errorMessage)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `xcodebuild -project ios/DotnetNativeInteropApp.xcodeproj -scheme DotnetNativeInteropTests test -destination 'platform=iOS Simulator,name=iPhone 16'`
Expected: FAIL — `cannot find 'BoundaryViewModel' in scope`.

- [ ] **Step 3: Write the view model**

Create `ios/Shared/Boundary/BoundaryViewModel.swift`:
```swift
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `xcodebuild -project ios/DotnetNativeInteropApp.xcodeproj -scheme DotnetNativeInteropTests test -destination 'platform=iOS Simulator,name=iPhone 16'`
Expected: PASS — all three `BoundaryViewModelTests` green.

- [ ] **Step 5: Commit**

```powershell
git add ios/Shared/Boundary/BoundaryViewModel.swift ios/Tests/BoundaryViewModelTests.swift
git commit -m "feat(ffi-boundary): iOS BoundaryViewModel + logic tests"
```

---

## Task 4: SwimlaneView (the hero + thread-hop)

**Files:**
- Create: `ios/Shared/Boundary/SwimlaneView.swift`

- [ ] **Step 1: Write the swimlane**

Create `ios/Shared/Boundary/SwimlaneView.swift`:
```swift
import SwiftUI

/// The lifecycle swimlane: one lane per layer, with the active phase highlighting its lane(s) and a
/// traveling token. The callback phase is the signature moment — the token hops from `worker` up to `UI`.
/// iOS has NO JNI lane (that hop only exists on Android).
struct SwimlaneView: View {
    let activePhase: BoundaryPhase?
    let streaming: Bool

    private enum Lane: Int, CaseIterable, Identifiable {
        case ui, binding, cabi, net, worker
        var id: Int { rawValue }
        var name: String {
            switch self {
            case .ui: "UI thread"
            case .binding: "binding"
            case .cabi: "C ABI"
            case .net: ".NET AOT"
            case .worker: "worker"
            }
        }
        var tint: Color {
            switch self {
            case .ui, .cabi: Instrument.accent
            case .binding: Instrument.textSecondary
            case .net, .worker: Instrument.ok
            }
        }
    }

    /// Which lane holds the token for a given phase.
    private func lane(for phase: BoundaryPhase) -> Lane {
        switch phase {
        case .marshal: .binding
        case .cross: .cabi
        case .execute: .net
        case .callback: .ui      // the hop destination
        case .free: .ui
        }
    }

    private func isHot(_ lane: Lane) -> Bool {
        guard let activePhase else { return false }
        if activePhase == .callback { return lane == .worker || lane == .ui } // both lanes light during the hop
        return self.lane(for: activePhase) == lane
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Instrument.Space.s) {
            ForEach(Lane.allCases) { lane in
                HStack(spacing: Instrument.Space.s) {
                    Text(lane.name)
                        .font(Instrument.panelLabel)
                        .foregroundStyle(isHot(lane) ? Instrument.accent : Instrument.textTertiary)
                        .frame(width: 72, alignment: .trailing)
                    track(lane)
                }
            }
        }
        .animation(.spring(duration: 0.4), value: activePhase)
    }

    private func track(_ lane: Lane) -> some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: 3)
                    .fill(Instrument.bg2)
                RoundedRectangle(cornerRadius: 3)
                    .strokeBorder(isHot(lane) ? Instrument.accent : Instrument.hairline,
                                  lineWidth: isHot(lane) ? 1.5 : 1)
                if showsToken(lane) {
                    Circle()
                        .fill(lane.tint)
                        .frame(width: 9, height: 9)
                        .shadow(color: lane.tint.opacity(0.7), radius: isHopping ? 6 : 0)
                        .offset(x: tokenX(lane, width: geo.size.width))
                }
            }
        }
        .frame(height: 16)
    }

    private var isHopping: Bool { activePhase == .callback }

    private func showsToken(_ lane: Lane) -> Bool {
        guard let activePhase else { return false }
        if activePhase == .callback { return lane == .worker || lane == .ui }
        return self.lane(for: activePhase) == lane
    }

    /// Token x within a lane: progresses left→right across phases; on the hop it sits at the right edge.
    private func tokenX(_ lane: Lane, width: CGFloat) -> CGFloat {
        guard let activePhase, let idx = BoundaryPhase.allCases.firstIndex(of: activePhase) else { return 4 }
        let progress = CGFloat(idx) / CGFloat(BoundaryPhase.allCases.count - 1)
        return max(4, min(width - 13, progress * (width - 13)))
    }
}

#Preview {
    SwimlaneView(activePhase: .callback, streaming: true)
        .padding()
        .background(Instrument.bg0)
}
```

- [ ] **Step 2: Build to verify it compiles (Mac)**

Run: `cd ios && xcodebuild -project DotnetNativeInteropApp.xcodeproj -scheme DotnetNativeInteropUnified -destination 'platform=iOS Simulator,name=iPhone 16' build`
Expected: PASS.

- [ ] **Step 3: Commit**

```powershell
git add ios/Shared/Boundary/SwimlaneView.swift
git commit -m "feat(ffi-boundary): iOS swimlane with callback thread-hop"
```

---

## Task 5: Inspector panels

**Files:**
- Create: `ios/Shared/Boundary/BoundaryInspectorPanels.swift`

- [ ] **Step 1: Write the six segment panels**

Create `ios/Shared/Boundary/BoundaryInspectorPanels.swift`:
```swift
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
```

- [ ] **Step 2: Build to verify (Mac)**

Run: `cd ios && xcodebuild -project DotnetNativeInteropApp.xcodeproj -scheme DotnetNativeInteropUnified -destination 'platform=iOS Simulator,name=iPhone 16' build`
Expected: PASS.

- [ ] **Step 3: Commit**

```powershell
git add ios/Shared/Boundary/BoundaryInspectorPanels.swift
git commit -m "feat(ffi-boundary): iOS inspector segment panels"
```

---

## Task 6: BoundaryView (the A1 screen)

**Files:**
- Create: `ios/Shared/Boundary/BoundaryView.swift`

- [ ] **Step 1: Write the screen**

Create `ios/Shared/Boundary/BoundaryView.swift`:
```swift
import SwiftUI

/// Approach A · A1: swimlane hero + segmented inspector that auto-follows the phase in Auto-step.
struct BoundaryView: View {
    @ObservedObject var viewModel: BoundaryViewModel
    @State private var revealed = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Instrument.Space.l) {
                PanelHeader("Boundary · trace one FFI call")
                    .revealCard(revealed, delay: 0)

                presetRow.revealCard(revealed, delay: 0.05)

                VStack(alignment: .leading, spacing: Instrument.Space.s) {
                    PanelHeader("lifecycle — swimlane")
                    SwimlaneView(activePhase: viewModel.activePhase, streaming: viewModel.preset == .stream)
                    Label("callback fires off the UI thread → hops to @MainActor",
                          systemImage: "exclamationmark.triangle.fill")
                        .font(.caption).foregroundStyle(Instrument.warn)
                }
                .instrumentCard()
                .revealCard(revealed, delay: 0.1)

                controls.revealCard(revealed, delay: 0.15)

                if let message = viewModel.errorMessage {
                    ErrorBanner(message: message) { Task { await viewModel.run() } }
                        .revealCard(revealed, delay: 0.2)
                }

                inspectorPicker.revealCard(revealed, delay: 0.2)
                BoundaryInspectorPanel(vm: viewModel).revealCard(revealed, delay: 0.25)
            }
            .padding(Instrument.Space.l)
        }
        .instrumentScreen()
        .task { revealed = true }
    }

    private var presetRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: Instrument.Space.s) {
                ForEach(BoundaryPreset.allCases) { preset in
                    let selected = viewModel.preset == preset
                    Text(preset.title)
                        .font(Instrument.panelLabel)
                        .padding(.horizontal, Instrument.Space.m).padding(.vertical, Instrument.Space.s)
                        .background(selected ? Instrument.accent : Instrument.bg2,
                                    in: Capsule())
                        .foregroundStyle(selected ? Instrument.bg0 : Instrument.textSecondary)
                        .onTapGesture { viewModel.preset = preset }
                }
            }
        }
    }

    private var controls: some View {
        HStack(spacing: Instrument.Space.m) {
            Button {
                Task { await viewModel.run() }
            } label: {
                Label(viewModel.preset == .stream ? "Run stream" : "Run", systemImage: "play.fill")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent).tint(Instrument.accent)
            .disabled(viewModel.running)

            Button {
                Task { await viewModel.autoStep() }
            } label: {
                Label("Auto-step", systemImage: "forward.frame").frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered).tint(Instrument.accent)
            .disabled(viewModel.autoStepping)

            if viewModel.preset == .stream && viewModel.running {
                Button(role: .destructive) { viewModel.stop() } label: { Image(systemName: "stop.fill") }
                    .buttonStyle(.bordered).tint(Instrument.fail)
            }
        }
    }

    private var inspectorPicker: some View {
        Picker("inspector", selection: $viewModel.inspector) {
            ForEach(BoundaryInspector.allCases) { seg in Text(seg.label).tag(seg) }
        }
        .pickerStyle(.segmented)
    }
}

#Preview {
    BoundaryView(viewModel: BoundaryViewModel(service: MockBoundaryService()))
}
```

- [ ] **Step 2: Build to verify (Mac)**

Run: `cd ios && xcodebuild -project DotnetNativeInteropApp.xcodeproj -scheme DotnetNativeInteropUnified -destination 'platform=iOS Simulator,name=iPhone 16' build`
Expected: PASS.

- [ ] **Step 3: Commit**

```powershell
git add ios/Shared/Boundary/BoundaryView.swift
git commit -m "feat(ffi-boundary): iOS BoundaryView (A1 screen)"
```

---

## Task 7: Register the Boundary tab

**Files:**
- Modify: `ios/Shared/RootTabView.swift`

- [ ] **Step 1: Own the view model**

In `ios/Shared/RootTabView.swift`, add this stored property to `RootTabView` (right after `let engineRagServices: TransportMap<EngineRagService>` on line 10):
```swift
    @StateObject private var boundary = BoundaryViewModel(service: FFIBoundaryService())
```

- [ ] **Step 2: Add the tab first**

In the same file, insert the Boundary tab as the FIRST child of `TabView { ` (immediately after `TabView {` on line 13, before `DashboardView`):
```swift
            BoundaryView(viewModel: boundary)
                .tabItem { Label("Boundary", systemImage: "arrow.left.arrow.right") }

```

- [ ] **Step 3: Build to verify (Mac)**

Run: `cd ios && xcodegen generate && xcodebuild -project DotnetNativeInteropApp.xcodeproj -scheme DotnetNativeInteropUnified -destination 'platform=iOS Simulator,name=iPhone 16' build`
Expected: PASS — Boundary appears as the first tab.

- [ ] **Step 4: Commit**

```powershell
git add ios/Shared/RootTabView.swift
git commit -m "feat(ffi-boundary): register Boundary as the first iOS tab"
```

---

## Task 8: Rebuild framework + on-device verification (Mac mini)

**Files:** none (build + manual verification)

- [ ] **Step 1: Rebuild the dni xcframework (ABI changed in Plan A)**

On the Mac mini (per `docs/ios-build-deploy-runbook.md`), backgrounded:
```bash
ssh steve@steve-mac-mini "zsh -lc 'cd /Users/steve/dotnet-ios-android-poc-native-frontend && bash build/build-ios-framework.sh'"
```
Expected: `dni.xcframework` rebuilt (device + simulator) containing the new `dni_ffi_*` symbols + updated `dni.h`.

- [ ] **Step 2: Run the unit tests on the simulator**

```bash
ssh steve@steve-mac-mini "zsh -lc 'cd /Users/steve/dotnet-ios-android-poc-native-frontend/ios && xcodegen generate && xcodebuild -project DotnetNativeInteropApp.xcodeproj -scheme DotnetNativeInteropTests test -destination \"platform=iOS Simulator,name=iPhone 16\"'"
```
Expected: PASS — `BoundaryModelsTests` + `BoundaryViewModelTests` green.

- [ ] **Step 3: Build + install on device, walk the screen**

Use the fast device path from the runbook (`build-ios-framework-device.sh` is already covered by Step 1's full build; build/sign/install the app). On device, confirm:
- Boundary is the first tab.
- `echo`: bytes panel shows hex + decoded + ptr; µs panel shows marshal/cross/execute·native/free/total.
- `pixels`: large buffer echoes; ledger shows the larger `result json` bytes.
- `stream`: tokens arrive; threads panel shows caller vs managed-callback thread ids differ; the swimlane shows the worker→UI hop.
- `throw`: error panel shows "contained at the boundary — no crash", status -5; app does not crash.
- `memory` + "simulate missing free": outstanding-bytes climbs; Reset clears it.
- `Auto-step`: phases walk and the inspector auto-follows.

- [ ] **Step 4: Screenshot the screen on device (visual-parity rule)**

Capture the Boundary screen (echo run + stream hop) and attach to the PR. This satisfies the "screenshot before calling visual work done" rule.

---

## Self-Review

**Spec coverage:** swimlane hero + thread-hop (Task 4) ✓; byte inspector (Task 5 BytesPanel) ✓; ownership ledger + leak toggle (MemoryPanel + VM) ✓; µs strip with frontend/native honesty labelled (TimingPanel) ✓; ABI ⇄ Swift map (AbiPanel) ✓; error containment (ErrorContainmentPanel + throw preset) ✓; 5 presets (BoundaryPreset, run routing) ✓; segmented inspector auto-following in Auto-step (VM.autoStep + segment(for:)) ✓; A1 layout (BoundaryView) ✓; first-tab hero (Task 7) ✓; parity note re: 9-tab interim ✓; build/verify on Mac (Task 8) ✓.

**Placeholder scan:** every code/command step is complete; no TBD. The "one tap down" full-ABI/overhead bench is intentionally *not* a new screen — AbiPanel carries the relevant mapping and the overhead comparison lives in the existing Compare/Latency tabs (referenced, not stubbed).

**Type consistency:** `BoundaryService` signatures (`echo(_:skipFree:)`, `throwDemo()`, `stream(_:maxTokens:)`) are identical in protocol, `FFIBoundaryService`, `MockBoundaryService`, and every VM call. `BoundaryEchoTrace`/`PhaseTiming`/`OwnershipEntry` fields used by the VM and panels match their Task 1 definitions. The extended `@convention(c)` signature `(UnsafeMutableRawPointer?, Int32, UnsafePointer<CChar>?, Int32, Int64, Int64) -> Void` matches `dni_trace_cb` in `dni.h` and `dni_ffi_stream_start`'s C# parameter.

**Version-gated comments (your rule):** `ContinuousClock` (iOS 16+), `Task.sleep(for:)` (iOS 16+), switch expressions (Swift 5.9), `@convention(c)`/`@_silgen_name` (Swift 5+ vs bridging-header), `.numericText()` via `StatCell` (iOS 17+, already in the shared component) — each annotated at its site or in the ABI panel copy.

**Execution note:** Tasks 1 & 3 are true TDD (XCTest), but the tests RUN on the Mac mini simulator, not on Windows — author them here, run them in Task 8 Step 2 (or earlier if a Mac session is live). Tasks 2/4/5/6/7 are build-gated on the Mac; Task 8 is the device walk.
