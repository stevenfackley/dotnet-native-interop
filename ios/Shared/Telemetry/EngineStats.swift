import Foundation

/// Codable mirror of the engine's `dni_engine_stats` JSON (camelCase).
struct EngineStats: Codable, Sendable {
    let gcGen0: Int
    let gcGen1: Int
    let gcGen2: Int
    let heapBytes: Int
    let committedBytes: Int
    let allocatedBytes: Int
    let gcPauseMs: Double
    let threadCount: Int
    let processorCount: Int
    let uptimeMs: Double
}
