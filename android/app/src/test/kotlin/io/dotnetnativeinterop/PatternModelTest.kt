package io.dotnetnativeinterop

import io.dotnetnativeinterop.ui.PatternsJson
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Parses the real patterns.json and verifies structural correctness. */
public class PatternModelTest {

    private val json = Json { ignoreUnknownKeys = true }

    // Inline the minimal JSON to keep tests self-contained — the asset copy
    // happens at build time, not at unit-test classpath time.
    private val patternsJsonSample = """
        {
          "version": 1,
          "recommendation": "use FFI",
          "patterns": [
            {
              "id": "ffi",
              "name": "FFI + Callback",
              "transport": "In-process C ABI",
              "summary": "Direct call.",
              "bestFor": "Hot path.",
              "features": ["Zero IPC"],
              "limitations": ["Complex build"]
            },
            {
              "id": "http",
              "name": "HTTP Loopback",
              "transport": "Kestrel SSE",
              "summary": "SSE.",
              "bestFor": "Debug.",
              "features": ["Familiar REST"],
              "limitations": ["TCP overhead"]
            },
            {
              "id": "grpc",
              "name": "gRPC over UDS",
              "transport": "UDS gRPC",
              "summary": "Typed.",
              "bestFor": "Schema.",
              "features": ["Protobuf"],
              "limitations": ["Size"]
            },
            {
              "id": "sqlite",
              "name": "SQLite WAL",
              "transport": "WAL broker",
              "summary": "Durable.",
              "bestFor": "Queue.",
              "features": ["Durable"],
              "limitations": ["Polling latency"]
            }
          ]
        }
    """.trimIndent()

    @Test
    public fun `parses four patterns with correct ids`() {
        val parsed = json.decodeFromString(PatternsJson.serializer(), patternsJsonSample)
        assertEquals(1, parsed.version)
        assertEquals(4, parsed.patterns.size)

        val ids = parsed.patterns.map { it.id }
        assertEquals(listOf("ffi", "http", "grpc", "sqlite"), ids)
    }

    @Test
    public fun `ffi pattern has features and limitations`() {
        val parsed = json.decodeFromString(PatternsJson.serializer(), patternsJsonSample)
        val ffi = parsed.patterns.find { it.id == "ffi" }
        assertNotNull(ffi)
        assertEquals(1, ffi!!.features.size)
        assertEquals(1, ffi.limitations.size)
    }
}
