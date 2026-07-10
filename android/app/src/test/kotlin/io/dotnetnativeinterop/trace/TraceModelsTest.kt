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

    @Test
    public fun parsesAgentToolSpanWithToolArgsAndToolResultTags() {
        // The additive fields dni_trace_drain now carries for agent.tool.<name> spans (see EngineTrace.cs
        // / ForemanAgent.cs) — a plain Kotlin property match on the C# source-gen camelCase names, no
        // custom SerialName mapping needed.
        val drainJson = """
            {
              "nowUs": 500.0,
              "dropped": 0,
              "capacity": 512,
              "spans": [
                {"name":"agent.tool.search_manuals","startUs":0.0,"durUs":5.0,"requestId":null,"status":null,
                 "toolArgs":"{\"query\":\"E3\"}","toolResult":"{\"snippets\":[\"a\"]}"}
              ]
            }
        """.trimIndent()

        val drain = json.decodeFromString<TraceDrain>(drainJson)
        val span = drain.spans[0]
        assertEquals("{\"query\":\"E3\"}", span.toolArgs)
        assertEquals("{\"snippets\":[\"a\"]}", span.toolResult)
    }

    @Test
    public fun toolArgsAndToolResultDefaultToNullForOlderEngineBuildsMissingTheFields() {
        // Backward compatibility: a drain payload from an engine build that predates this feature has no
        // toolArgs/toolResult keys at all — must still parse, defaulting both to null, not throw.
        val drainJson = """
            {"nowUs":0.0,"dropped":0,"capacity":512,
             "spans":[{"name":"pb.execute","startUs":0.0,"durUs":1.0,"requestId":null,"status":null}]}
        """.trimIndent()

        val drain = json.decodeFromString<TraceDrain>(drainJson)
        assertNull(drain.spans[0].toolArgs)
        assertNull(drain.spans[0].toolResult)
    }
}
