import Foundation

/// FeatureService over an encrypted SQLCipher database (Pattern 4). The native library round-trips the
/// catalog and each result through a key-encrypted on-disk SQLite file (SQLCipher, PRAGMA key) and
/// returns JSON. The native side owns the key — the Swift client can't read SQLCipher — so this client
/// just calls the exports and decodes the JSON.
struct SQLiteFeatureService: FeatureService {

    func descriptors() async throws -> [FeatureDescriptor] {
        let json = try await Task.detached(priority: .userInitiated) { () throws -> String in
            guard let ptr = dni_sqlite_features() else { throw FeatureServiceError.nullResult }
            defer { dni_string_free(ptr) }
            return String(cString: ptr)
        }.value
        return try JSONDecode.decode([FeatureDescriptor].self, from: json)
    }

    func run(_ id: String) async throws -> FeatureResult {
        let json = try await Task.detached(priority: .userInitiated) { () throws -> String in
            let ptr: UnsafePointer<CChar>? = id.withCString { dni_sqlite_run($0) }
            guard let ptr else { throw FeatureServiceError.nullResult }
            defer { dni_string_free(ptr) }
            return String(cString: ptr)
        }.value
        return try JSONDecode.decode(FeatureResult.self, from: json)
    }
}

extension TransportInfo {
    static let sqlite = TransportInfo(
        id: "sqlite",
        displayName: "SQLCipher",
        mechanism: "Encrypted on-disk SQLite (SQLCipher, PRAGMA key) — data round-trips through ciphertext.",
        summary: "The catalog and each run's result are written to and read back from an on-disk SQLite "
            + "database encrypted at rest with SQLCipher (PRAGMA key). The native side owns the key and "
            + "returns JSON. Durable and encrypted, but the disk + cipher round-trip makes it the "
            + "highest-latency transport.",
        features: [
            "Encrypted at rest (SQLCipher / AES)",
            "Durable — results persist on disk",
            "Decoupled producer/consumer via the file",
        ],
        limitations: [
            "Disk write + read round-trip per call",
            "Cipher overhead on every page",
            "Highest end-to-end latency",
        ])
}
