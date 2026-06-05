import Foundation

/// The three interop transports the unified app can drive. All are served by the same embedded
/// NativeAOT library; they differ only in how the data crosses to Swift.
enum TransportKind: String, CaseIterable, Identifiable, Sendable {
    case ffi
    case http
    case sqlite

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .ffi:    return "FFI"
        case .http:   return "HTTP"
        case .sqlite: return "SQLite"
        }
    }
}
