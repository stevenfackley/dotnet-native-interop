import Foundation

/// Shared JSON decode helper that maps failures onto FeatureServiceError.
enum JSONDecode {
    static func decode<T: Decodable>(_ type: T.Type, from json: String) throws -> T {
        guard let data = json.data(using: .utf8) else { throw FeatureServiceError.nullResult }
        return try decode(type, from: data)
    }

    static func decode<T: Decodable>(_ type: T.Type, from data: Data) throws -> T {
        do {
            return try JSONDecoder().decode(T.self, from: data)
        } catch {
            throw FeatureServiceError.decodeFailed(error.localizedDescription)
        }
    }
}
