import Foundation

/// FeatureService over the loopback raw-socket HTTP server (Pattern 1). Starts the server (idempotent)
/// to get its 127.0.0.1 port, then issues plain HTTP GETs and decodes the JSON.
struct HTTPFeatureService: FeatureService {

    func descriptors() async throws -> [FeatureDescriptor] {
        let data = try await get("/features")
        return try JSONDecode.decode([FeatureDescriptor].self, from: data)
    }

    func run(_ id: String) async throws -> FeatureResult {
        let escaped = id.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? id
        let data = try await get("/feature/run/\(escaped)")
        return try JSONDecode.decode(FeatureResult.self, from: data)
    }

    private func get(_ path: String) async throws -> Data {
        let port = Int(ondevicellm_http_start())
        guard port > 0 else { throw FeatureServiceError.nullResult }
        guard let url = URL(string: "http://127.0.0.1:\(port)\(path)") else {
            throw FeatureServiceError.nullResult
        }
        let (data, response) = try await URLSession.shared.data(from: url)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            let code = (response as? HTTPURLResponse)?.statusCode ?? -1
            throw FeatureServiceError.decodeFailed("HTTP \(code)")
        }
        return data
    }
}

extension TransportInfo {
    static let http = TransportInfo(
        id: "http",
        displayName: "HTTP loopback",
        mechanism: "Raw System.Net.Sockets server on 127.0.0.1 — REST + JSON over the loopback.",
        summary: "A minimal hand-rolled HTTP/1.1 server (no ASP.NET) runs inside the library on a "
            + "loopback port. The UI fetches features and results with stock URLSession — debuggable "
            + "with curl, but pays TCP + HTTP + JSON overhead to talk to itself.",
        features: [
            "Familiar REST boundary, debuggable with curl",
            "Stock URLSession client; language-agnostic",
            "Loopback avoids the iOS Local Network prompt",
        ],
        limitations: [
            "TCP + HTTP + JSON overhead to talk to yourself",
            "Dynamic port handshake on each start",
            "iOS suspends the listener when backgrounded",
        ])
}
