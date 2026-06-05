import Foundation

/// The single seam each transport app implements: fetch the catalog, run one feature by id.
/// FFI / HTTP / SQLite each provide a concrete conformer; the shared UI is transport-agnostic.
protocol FeatureService: Sendable {
    func descriptors() async throws -> [FeatureDescriptor]
    func run(_ id: String) async throws -> FeatureResult
}

enum FeatureServiceError: LocalizedError {
    case nullResult
    case decodeFailed(String)

    var errorDescription: String? {
        switch self {
        case .nullResult:          return "The native library returned no data."
        case .decodeFailed(let m): return "Couldn't decode the native response: \(m)"
        }
    }
}
