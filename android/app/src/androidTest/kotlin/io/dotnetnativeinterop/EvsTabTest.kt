package io.dotnetnativeinterop

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.dotnetnativeinterop.evs.EdgeSearchEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
public class EvsTabTest {

    @Test
    public fun topHitMatchesPublishedFixture(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val dir = AssetExtractor.ensure(app)
        val hits = EdgeSearchEngine(dir).search("compressor won't start")
        assertTrue("hits non-empty", hits.isNotEmpty())
        assertTrue("top score >= 0.70 (was ${hits.firstOrNull()?.score})", hits.first().score >= 0.70f)
        assertEquals("top hit == fixture expectedTopChunkId", "hvac-001#2", hits.first().chunk.chunkId)
        android.util.Log.i("EvsTab", "PASS: top=${hits.first().chunk.chunkId} score=${hits.first().score} hits=${hits.size}")
    }
}
