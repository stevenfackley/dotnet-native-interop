package io.dotnetnativeinterop.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

public class BenchmarkTest {

    private val fixture = """
        { "kind": "benchmark", "title": "SIMD matrix multiply",
          "series": [ { "name": "scalar", "points": [ { "x": 64, "y": 1.2 }, { "x": 128, "y": 0.8 } ] },
                      { "name": "SIMD",   "points": [ { "x": 64, "y": 0.2 } ] } ],
          "summary": [ { "label": "peak GFLOPS", "value": "41.8" } ],
          "extraIgnored": true }
    """.trimIndent()

    @Test
    public fun decodesPayload() {
        val p = Benchmark.decode(fixture)!!
        assertEquals("SIMD matrix multiply", p.title)
        assertEquals(2, p.series.size)
        assertEquals("scalar", p.series[0].name)
        assertEquals(2, p.series[0].points.size)
        assertEquals(64.0, p.series[0].points[0].x, 1e-9)
        assertEquals(1.2, p.series[0].points[0].y, 1e-9)
        assertEquals("peak GFLOPS", p.summary[0].label)
        assertEquals("41.8", p.summary[0].value)
    }

    @Test
    public fun returnsNullOnMalformed() {
        assertNull(Benchmark.decode("not json"))
        assertNull(Benchmark.decode("64x64x3:AAAA"))
    }
}
