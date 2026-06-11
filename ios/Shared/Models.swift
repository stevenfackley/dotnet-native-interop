import SwiftUI

// Codable models mirroring the NativeBridge structured-feature JSON (camelCase keys).

/// One language feature's catalog entry: code snippet + its deterministic expected result.
struct FeatureDescriptor: Codable, Identifiable, Sendable {
    let id: String
    let title: String
    let version: String
    let code: String
    let expected: String
}

/// One live execution of a feature: its output and timing. `ok` = output matched `expected`.
struct FeatureResult: Codable, Identifiable, Sendable {
    let id: String
    let result: String
    let elapsedMs: Double
    let ok: Bool
}

/// UI state of a feature row/detail (drives the status icon + color).
enum RunStatus {
    case idle, running, ok, failed

    var symbol: String {
        switch self {
        case .idle:    return "circle"
        case .running: return "circle.dotted"
        case .ok:      return "checkmark.circle.fill"
        case .failed:  return "xmark.circle.fill"
        }
    }

    var color: Color {
        switch self {
        case .idle:    return Instrument.textTertiary
        case .running: return Instrument.accent
        case .ok:      return Instrument.ok
        case .failed:  return Instrument.fail
        }
    }
}
