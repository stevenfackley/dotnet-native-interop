package io.dotnetnativeinterop

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.dotnetnativeinterop.evs.EdgeSearchEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device proof that the Kotlin EVS pipeline (WordPiece tokenizer -> onnxruntime-android encoder ->
 * SQLite index -> cosine) reproduces the .NET EdgeIndexPublisher ranking for the fixture query.
 *
 * The production threshold is 0.70 to mirror iOS. all-MiniLM cosines for genuine matches run below
 * that: the fixture's top hit (hvac-001#2) scores ~0.565 against the published query vector, so the
 * 0.70 default deliberately returns nothing for this query (iOS-parity behavior, asserted below).
 * The ranking proof therefore queries unthresholded (minScore = 0f) and checks the order, not a floor.
 */
@RunWith(AndroidJUnit4::class)
public class EvsTabTest {

    @Test
    public fun topHitMatchesPublishedFixture(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val dir = AssetExtractor.ensure(app)
        val engine = EdgeSearchEngine(dir)

        // Unthresholded ranking == the real pipeline proof (encoder output shape, BLOB decode, cosine order).
        val ranked = engine.search("compressor won't start", minScore = 0f)
        assertTrue("ranking non-empty", ranked.isNotEmpty())
        assertEquals("top hit == fixture expectedTopChunkId", "hvac-001#2", ranked.first().chunk.chunkId)
        val top = ranked.first().score
        // Ground truth vs the C#-published query vector is ~0.565; >0.5 guards against encoder regression.
        assertTrue("encoder sane (top ~0.565, was $top)", top > 0.5f)

        // iOS-parity: the production 0.70 threshold filters this query to nothing (top < 0.70).
        val atProd = engine.search("compressor won't start")
        assertTrue("0.70 parity filters this query (top $top < 0.70)", atProd.isEmpty())

        android.util.Log.i(
            "EvsTab",
            "PASS: top=${ranked.first().chunk.chunkId} score=$top ranked=${ranked.size} atProd=${atProd.size}",
        )
    }
}
