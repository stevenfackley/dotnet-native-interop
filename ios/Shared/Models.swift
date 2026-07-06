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

// MARK: - Catalog search/filter/sort (IA collapse spec, 2026-06-21: Catalog's one net-new behavior)

/// C# version-number filter chip. Buckets the "C# N" descriptor version string into a coarse range.
enum VersionBucket: String, CaseIterable, Identifiable {
    case v1to6 = "C# 1–6"
    case v7to10 = "C# 7–10"
    case v11to14 = "C# 11–14"
    var id: Self { self }

    /// True if `version` (e.g. "C# 12") falls inside this bucket's numeric range.
    func matches(_ version: String) -> Bool {
        guard let n = Int(version.replacingOccurrences(of: "C# ", with: "")) else { return false }
        switch self {
        case .v1to6:   return (1...6).contains(n)
        case .v7to10:  return (7...10).contains(n)
        case .v11to14: return (11...14).contains(n)
        }
    }
}

/// Pass/fail filter chip, driven by the last run result (no result yet = matches neither).
enum StatusChip: String, CaseIterable, Identifiable {
    case pass = "✓ pass"
    case fail = "✗ fail"
    var id: Self { self }
}

/// Catalog sort order.
enum CatalogSort: String, CaseIterable, Identifiable {
    case name = "Name"
    case version = "Version"
    case elapsed = "Elapsed"
    var id: Self { self }
}
