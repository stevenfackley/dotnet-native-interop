import Foundation

// Codable mirrors of the engine's BenchmarkPayload JSON (camelCase keys), decoded with JSONDecode.

struct BenchmarkPoint: Codable, Sendable {
    let x: Double
    let y: Double
}

struct BenchmarkSeries: Codable, Sendable, Identifiable {
    let name: String
    let points: [BenchmarkPoint]
    var id: String { name }
}

struct SummaryStat: Codable, Sendable, Identifiable {
    let label: String
    let value: String
    var id: String { label }
}

struct BenchmarkPayload: Codable, Sendable {
    let kind: String
    let title: String
    let series: [BenchmarkSeries]
    let summary: [SummaryStat]
}
