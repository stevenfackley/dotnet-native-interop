package io.dotnetnativeinterop

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.dotnetnativeinterop.feature.FfiFeatureService
import io.dotnetnativeinterop.lab.Benchmark
import io.dotnetnativeinterop.lab.RasterPayload
import io.dotnetnativeinterop.transport.NativeBridge
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device proof that the Lab tab's ShowcaseCommand ids render + decode end-to-end over the real FFI:
 * a parametric Mandelbrot decodes to a Bitmap and a matmul benchmark decodes to a multi-series payload.
 */
@RunWith(AndroidJUnit4::class)
public class LabTabTest {

    public companion object {
        @JvmStatic
        @BeforeClass
        public fun setUp() {
            System.loadLibrary("dni")
            System.loadLibrary("dni_jni")
            check(NativeBridge.nativeInitialize() == 0) { "init failed" }
        }
    }

    @Test
    public fun mandelbrotCommandRendersDecodableFrame(): Unit = runBlocking {
        val r = FfiFeatureService().run("viz-mandelbrot~cx_-0.5~cy_0~zoom_1~iters_50~w_64~h_64")
        assertTrue("ok", r.ok)
        assertTrue("header (was '${r.result.take(12)}')", Regex("^64x64x[13]:").containsMatchIn(r.result))
        val img = RasterPayload.decode(r.result)
        assertEquals(64, img?.width)
        assertEquals(64, img?.height)
        android.util.Log.i("LabTab", "PASS: mandelbrot ${img?.width}x${img?.height}")
    }

    @Test
    public fun matmulBenchmarkDecodes(): Unit = runBlocking {
        val r = FfiFeatureService().run("bench-matmul~max_128")
        assertTrue("ok", r.ok)
        val p = Benchmark.decode(r.result)
        assertTrue("payload decoded", p != null)
        assertTrue(">=2 series", (p?.series?.size ?: 0) >= 2)
        assertTrue("all series have points", p!!.series.all { it.points.isNotEmpty() })
        android.util.Log.i("LabTab", "PASS: matmul series=${p.series.size}")
    }
}
