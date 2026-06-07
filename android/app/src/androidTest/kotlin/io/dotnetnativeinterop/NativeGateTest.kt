package io.dotnetnativeinterop

import io.dotnetnativeinterop.transport.NativeBridge
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * SP0 native gate. Walks the staged probe ladder on a real arm64 emulator.
 * Emits PASS: lines to logcat (tag NativeGate) mirroring the iOS gate signal.
 */
public class NativeGateTest {

    public companion object {
        private const val TAG = "NativeGate"

        @JvmStatic
        @BeforeClass
        public fun loadLibraries() {
            System.loadLibrary("dni")      // NativeAOT lib first — all C symbols resident
            System.loadLibrary("dni_jni")  // shim registers natives in JNI_OnLoad
        }

        internal fun pass(msg: String) {
            android.util.Log.i(TAG, "PASS: $msg")
        }
    }

    @Test
    public fun s1_bareAbiRoundTrips() {
        assertEquals("dni_initialize", 0, NativeBridge.nativeInitialize())
        pass("dni_initialize == 0")

        val json = requireNotNull(NativeBridge.nativeFeaturesJson()) { "features json null" }
        assertTrue("features json non-empty", json.isNotBlank())
        val arr = JSONArray(json)
        assertTrue("features catalog non-empty", arr.length() > 0)
        pass("dni_features_json -> ${arr.length()} features")

        val firstId = arr.getJSONObject(0).getString("id")
        val run = requireNotNull(NativeBridge.nativeFeatureRun(firstId)) { "feature_run null" }
        assertTrue("feature_run non-empty", run.isNotBlank())
        assertTrue("feature_run ok==true", JSONObject(run).getBoolean("ok"))
        pass("dni_feature_run($firstId).ok == true")
    }
}
