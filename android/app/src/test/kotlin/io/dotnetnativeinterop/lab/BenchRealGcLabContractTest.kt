package io.dotnetnativeinterop.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the bench-real / gclab BenchmarkPayload shape to the engine's ShowcaseJson output (camelCase),
 * specifically the series names and summary LABELS the Latency drill-downs match by prefix. If the engine
 * renames "collection mode", "clamped", "gen0 collections", "avg bytes/rep" etc., the UI's honesty
 * disclosures would silently blank — this test fails first.
 */
public class BenchRealGcLabContractTest {

    // Mirrors RealPayloadBenchmark.Run("catalog", 5, clamped:false).
    private val benchRealJson = """
        {"kind":"benchmark","title":"Realistic payload bench (catalog, 5 reps)",
         "series":[{"name":"payloadBytes","points":[{"x":1,"y":2048},{"x":2,"y":2048}]},
                   {"name":"serializeUs","points":[{"x":1,"y":12.5},{"x":2,"y":9.1}]}],
         "summary":[{"label":"reps (effective)","value":"5"},{"label":"clamped","value":"no"},
                    {"label":"total bytes","value":"10240"},{"label":"avg bytes/rep","value":"2048"},
                    {"label":"total serialize","value":"0.06 ms"},{"label":"avg serialize/rep","value":"12.5 µs"}]}
    """.trimIndent()

    // Mirrors GcLab.Run("pinned", 64, 5, clamped:false) — the forced-collection disclosure case.
    private val gcLabJson = """
        {"kind":"benchmark","title":"GC Lab (pinned, 64 MB, 5s)",
         "series":[{"name":"heapMB","points":[{"x":0.1,"y":40.0}]},{"name":"committedMB","points":[{"x":0.1,"y":72.0}]}],
         "summary":[{"label":"preset","value":"pinned"},{"label":"mb (effective)","value":"64"},
                    {"label":"secs (effective)","value":"5"},{"label":"clamped","value":"no"},
                    {"label":"collection mode","value":"forced full GC ~10/s (reclaims LOH/pinned in-window)"},
                    {"label":"gen0 collections (incl. forced)","value":"12"},
                    {"label":"gen1 collections (incl. forced)","value":"12"},
                    {"label":"gen2 collections (incl. forced)","value":"12"},
                    {"label":"GC pause Δ","value":"5.40 ms"},
                    {"label":"heap before → after","value":"10.0 → 40.0 MB"},
                    {"label":"committed before → after","value":"20.0 → 72.0 MB"},
                    {"label":"allocated Δ","value":"320.0 MB"}]}
    """.trimIndent()

    private fun summaryValue(p: BenchmarkPayload, prefix: String): String? =
        p.summary.firstOrNull { it.label.startsWith(prefix) }?.value

    @Test
    public fun benchRealSeriesAndSummaryLabels() {
        val p = Benchmark.decode(benchRealJson)!!
        assertEquals(listOf("payloadBytes", "serializeUs"), p.series.map { it.name })
        assertEquals("no", summaryValue(p, "clamped"))
        assertEquals("2048", summaryValue(p, "avg bytes/rep"))
        assertNotNull(summaryValue(p, "avg serialize/rep")) // label may carry "(incl. cold start)"
    }

    @Test
    public fun gcLabDisclosesForcedCollectionModeAndCounters() {
        val p = Benchmark.decode(gcLabJson)!!
        assertEquals(listOf("heapMB", "committedMB"), p.series.map { it.name })

        val mode = summaryValue(p, "collection mode")!!
        assertTrue("forced GC must NOT be presented as organic", mode.startsWith("forced"))

        assertEquals("12", summaryValue(p, "gen0 collections")) // matches "gen0 collections (incl. forced)"
        assertNotNull(summaryValue(p, "GC pause Δ"))
        assertEquals("no", summaryValue(p, "clamped"))
    }
}
