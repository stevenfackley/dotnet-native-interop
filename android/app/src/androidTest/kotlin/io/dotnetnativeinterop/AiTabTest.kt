package io.dotnetnativeinterop

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.dotnetnativeinterop.ai.FfiRagService
import io.dotnetnativeinterop.ai.SearchService
import io.dotnetnativeinterop.transport.NativeBridge
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
public class AiTabTest {

    public companion object {
        @JvmStatic
        @BeforeClass
        public fun setUp() {
            System.loadLibrary("dni")
            System.loadLibrary("dni_jni")
            val app = ApplicationProvider.getApplicationContext<android.app.Application>()
            val dir = AssetExtractor.ensure(app)
            check(NativeBridge.nativeSetAssetsDir(dir.absolutePath) == 0) { "set assets dir failed" }
            check(NativeBridge.nativeInitialize() == 0) { "init failed" }
        }
    }

    @Test
    public fun semanticSearchReturnsRankedResults(): Unit = runBlocking {
        val results = SearchService().search("encrypt my data", "facts")
        assertTrue("results non-empty", results.isNotEmpty())
        var prev = Double.MAX_VALUE
        for (r in results) { assertTrue("descending", r.score <= prev); prev = r.score }
        android.util.Log.i("AiTab", "PASS: search -> ${results.size} results; top=${results.first()}")
    }

    @Test
    public fun ragAnswerStreams(): Unit = runBlocking {
        val fragments = withTimeout(60_000) {
            FfiRagService().answer("how do I reset the device").toList()
        }
        assertTrue("answer streamed >=1 non-empty fragment", fragments.any { it.isNotBlank() })
        android.util.Log.i("AiTab", "PASS: rag -> ${fragments.size} fragments; answer='${fragments.joinToString("").take(80)}'")
    }
}
