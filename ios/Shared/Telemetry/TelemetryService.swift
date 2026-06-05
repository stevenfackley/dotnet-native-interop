import Foundation

/// Reads live engine stats over the in-process C ABI. FFI-only: runtime introspection is engine-global,
/// not transport-specific. Copies + frees the returned heap UTF-8 JSON, then decodes it.
struct TelemetryService: Sendable {
    func stats() async throws -> EngineStats {
        let json = try await Task.detached(priority: .utility) { () throws -> String in
            guard let ptr = dni_engine_stats() else { throw FeatureServiceError.nullResult }
            defer { dni_string_free(ptr) }
            return String(cString: ptr)
        }.value
        return try JSONDecode.decode(EngineStats.self, from: json)
    }
}
