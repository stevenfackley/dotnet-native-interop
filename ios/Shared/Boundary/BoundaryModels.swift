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
