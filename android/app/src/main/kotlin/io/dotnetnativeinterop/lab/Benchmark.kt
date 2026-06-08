package io.dotnetnativeinterop.lab

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
public data class BenchmarkPoint(val x: Double, val y: Double)

@Serializable
public data class BenchmarkSeries(val name: String, val points: List<BenchmarkPoint>)

@Serializable
public data class SummaryStat(val label: String, val value: String)

@Serializable
public data class BenchmarkPayload(
    val kind: String,
    val title: String,
    val series: List<BenchmarkSeries>,
    val summary: List<SummaryStat>,
)

/** Decodes the engine's benchmark `result` JSON (camelCase). Returns null on any parse failure. */
public object Benchmark {
    private val json = Json { ignoreUnknownKeys = true }

    public fun decode(result: String): BenchmarkPayload? =
        try {
            json.decodeFromString<BenchmarkPayload>(result)
        } catch (_: Exception) {
            null
        }
}
