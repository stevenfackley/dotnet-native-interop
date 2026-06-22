package io.dotnetnativeinterop

import io.dotnetnativeinterop.model.BoundaryEcho
import io.dotnetnativeinterop.model.BoundaryThrow
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

public class BoundaryModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    public fun echoDecodesCamelCase() {
        val s = """{"bytesHex":"48656C6C6F","len":5,"decoded":"Hello","managedThreadId":2,"executeUs":3.4,"ptrIn":"0x16d"}"""
        val e = json.decodeFromString<BoundaryEcho>(s)
        assertEquals("Hello", e.decoded)
        assertEquals(5, e.len)
        assertEquals(2L, e.managedThreadId)
        assertEquals("48656C6C6F", e.bytesHex)
    }

    @Test
    public fun throwDecodes() {
        val s = """{"caught":true,"type":"System.InvalidOperationException","message":"x","status":-5}"""
        val t = json.decodeFromString<BoundaryThrow>(s)
        assertTrue(t.caught)
        assertEquals(-5, t.status)
        assertTrue(t.type.contains("InvalidOperationException"))
    }
}
