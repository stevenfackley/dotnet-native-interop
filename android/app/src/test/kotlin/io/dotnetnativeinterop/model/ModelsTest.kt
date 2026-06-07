package io.dotnetnativeinterop.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

public class ModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test public fun parsesDescriptorArray() {
        val src = """[{"id":"collection-expressions","title":"Collection expressions","version":"C# 12","code":"int[] a=[1];","expected":"a=[1]"}]"""
        val list = json.decodeFromString<List<FeatureDescriptor>>(src)
        assertEquals(1, list.size)
        assertEquals("collection-expressions", list[0].id)
        assertEquals("C# 12", list[0].version)
    }

    @Test public fun parsesRunResult() {
        val r = json.decodeFromString<FeatureResult>("""{"id":"x","result":"ok","elapsedMs":0.5,"ok":true}""")
        assertEquals("x", r.id); assertTrue(r.ok); assertEquals(0.5, r.elapsedMs, 0.0001)
    }

    @Test public fun parsesEngineStats() {
        val s = json.decodeFromString<EngineStats>("""{"gcGen0":1,"gcGen1":0,"gcGen2":0,"heapBytes":10,"committedBytes":20,"allocatedBytes":5,"gcPauseMs":0.1,"threadCount":4,"processorCount":8,"uptimeMs":12.3}""")
        assertEquals(8, s.processorCount)
    }
}
