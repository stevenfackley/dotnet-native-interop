import Foundation

/// FeatureService over the in-process C ABI (Pattern 3). Calls the structured exports on a
/// background thread, copies the returned heap UTF-8 JSON, frees it, and decodes it.
struct FFIFeatureService: FeatureService {

    func descriptors() async throws -> [FeatureDescriptor] {
        let json = try await Task.detached(priority: .userInitiated) { () throws -> String in
            guard let ptr = dni_features_json() else { throw FeatureServiceError.nullResult }
            defer { dni_string_free(ptr) }
            return String(cString: ptr)
        }.value
        return try JSONDecode.decode([FeatureDescriptor].self, from: json)
    }

    func run(_ id: String) async throws -> FeatureResult {
        let json = try await Task.detached(priority: .userInitiated) { () throws -> String in
            let ptr: UnsafePointer<CChar>? = id.withCString { dni_feature_run($0) }
            guard let ptr else { throw FeatureServiceError.nullResult }
            defer { dni_string_free(ptr) }
            return String(cString: ptr)
        }.value
        return try JSONDecode.decode(FeatureResult.self, from: json)
    }
}

extension TransportInfo {
    static let ffi = TransportInfo(
        id: "ffi",
        displayName: "FFI + callback",
        mechanism: "In-process C ABI — structured JSON over UnmanagedCallersOnly exports.",
        summary: "Features run inside a NativeAOT library loaded directly into this process. Each call "
            + "returns JSON in-memory — no sockets, no server, no cross-process serialization.",
        features: [
            "Zero IPC — direct in-memory calls",
            "Lowest latency, highest throughput",
            "No server to bind, no extra OS permissions",
        ],
        limitations: [
            "Manual UTF-8 marshalling and function-pointer ABI",
            "A managed crash takes down the host process",
            "No process isolation between UI and engine",
        ])
}
