package io.dotnetnativeinterop

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.dotnetnativeinterop.transport.NativeBridge
import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
public class OnnxGateTest {

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
    public fun semanticSearchReturnsRankedResults() {
        val json = requireNotNull(NativeBridge.nativeSearch("how do I reset the device", "facts")) {
            "nativeSearch returned null"
        }
        val arr = JSONArray(json)
        assertTrue("results non-empty (json=$json)", arr.length() > 0)
        var prev = Double.MAX_VALUE
        for (i in 0 until arr.length()) {
            val score = arr.getJSONObject(i).getDouble("score")
            assertTrue("scores descending", score <= prev)
            prev = score
        }
        android.util.Log.i("OnnxGate", "PASS: dni_search -> ${arr.length()} ranked results; top=${arr.getJSONObject(0)}")
    }
}
