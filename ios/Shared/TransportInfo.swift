import Foundation

/// Identity + tradeoffs of the interop transport an app is built around. Each app injects one so the
/// shared shell (Dashboard, About) can describe the mechanism without knowing which transport it is.
struct TransportInfo: Sendable {
    let id: String           // "ffi" | "http" | "sqlite"
    let displayName: String  // e.g. "FFI + callback"
    let mechanism: String    // one-line description
    let summary: String
    let features: [String]
    let limitations: [String]
}
