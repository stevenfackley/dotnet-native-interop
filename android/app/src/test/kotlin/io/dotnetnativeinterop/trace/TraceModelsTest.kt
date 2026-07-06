package io.dotnetnativeinterop.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.serialization.json.Json

/**
 * Locks the trace-drain JSON contract to the engine's `EngineTrace.DrainJson()` camelCase output. Also
 * exercises the honesty case: a non-zero `dropped` (ring overflow) must round-trip so the UI can disclose it.
 */
public class TraceModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    public fun parsesDrainWithSpansAndDrops() {
        val drainJson = """
            {
              "nowUs": 204831.4,
              "dropped": 7,
              "capacity": 512,
              "spans": [
                {"name":"pb.decode","startUs":100.0,"durUs":12.5,"requestId":"req-1","status":null},
                {"name":"pb.execute","startUs":112.5,"durUs":250.0,"requestId":"req-1","status":"Ok"},
                {"name":"pb.encode","startUs":362.5,"durUs":8.0,"requestId":"req-1","status":null}
              ]
            }
        """.trimIndent()

        val drain = json.decodeFromString<TraceDrain>(drainJson)
        assertEquals(204831.4, drain.nowUs, 1e-9)
        assertEquals(7L, drain.dropped)   // overflow disclosed
        assertEquals(512, drain.capacity)
        assertEquals(3, drain.spans.size)

        val execute = drain.spans[1]
        assertEquals("pb.execute", execute.name)
        assertEquals(250.0, execute.durUs, 1e-9)
        assertEquals("req-1", execute.requestId)
        assertEquals("Ok", execute.status)
        assertNull(drain.spans[0].status)
    }

    @Test
    public fun defaultsAllowEmptyDrain() {
        val drain = json.decodeFromString<TraceDrain>("""{"nowUs":0.0,"dropped":0,"capacity":512,"spans":[]}""")
        assertEquals(0, drain.spans.size)
        assertEquals(0L, drain.dropped)
    }
}
