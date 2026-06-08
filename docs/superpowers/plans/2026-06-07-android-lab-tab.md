# Android Lab Tab Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the gated Android **Lab** tab placeholder with the four iOS Lab demos (Fractal Explorer, Raymarched 3D, SIMD Matmul chart, Parallel Scaling chart) at 1:1 parity.

**Architecture:** Pure-Kotlin, additive, **no `.so` rebuild and no ABI change**. All four demos drive the existing `FeatureCatalogService.run(id)` (FFI/HTTP/SQLite) with `ShowcaseCommand` ids already compiled into `libdni.so` (`viz-mandelbrot~…`, `viz-raymarch~…`, `bench-matmul~max_…`, `bench-parallel~size_…`). New code: a `WxHxC:base64`→`RasterImage` decoder, benchmark JSON models, command builders, a `LabViewModel`, the screens, a frame loop with FPS readout, and a hand-drawn Compose `Canvas` chart.

**Tech Stack:** Kotlin 2.0.21, Jetpack Compose (Material3, BOM 2024.09.02), kotlinx.serialization, coroutines, JUnit4 + androidx.test. `-Xexplicit-api=strict` (all declarations need visibility modifiers, **including test sources**).

---

## Conventions (every task)

**Branch:** `feat/android-lab-tab` (already created off main).

**Build/test host:** the arm64 emulator on the Mac mini. Source of truth is the Windows repo; sync the changed files into the build overlay, then run gradle there.

**Sync (run from the Windows repo root before each Mac build):**
```bash
cd "C:/Users/steve/projects/dotnet-ios-android-poc-native-frontend" && tar -cf - <changed paths> | ssh steve-mac-mini "cd /Users/steve/dni-rag-build && tar -xf - && echo SYNCED"
```

**Mac PRELUDE (prefix every Mac gradle/adb command):**
```
export ANDROID_HOME=$HOME/Library/Android/sdk JAVA_HOME=$HOME/Library/Java/jdk; export PATH=$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH; GR=$HOME/toolchain/gradle-8.9/bin/gradle; cd /Users/steve/dni-rag-build/android
```
- JVM unit tests: `$GR --no-daemon :app:testDebugUnitTest --tests "io.dotnetnativeinterop.lab.*"`
- Compile check: `$GR --no-daemon :app:assembleDebug`
- Instrumented: ensure emulator up (`adb devices | grep -q emulator-5554 || (nohup emulator -avd dni_arm64 -no-window -no-audio -no-snapshot -gpu swiftshader_indirect >/tmp/emu.log 2>&1 &); adb wait-for-device`), then `$GR --no-daemon :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<FQCN>`
- **zsh gotcha:** wrap in `ssh steve-mac-mini 'zsh -lc "…"'`; never start an `echo` arg with `=`.

**Commit:** Conventional Commits, **no AI/Claude attribution**, subject + body only.

**Scope guard:** Do NOT touch the engine (`core/`), AI tab (`ai/`), EVS tab (`evs/`), catalog/feature services (`feature/FfiFeatureService`, `HttpFeatureService`, `SqliteFeatureService`, `FeatureCatalogService`, `FeaturesViewModel`), transports (`transport/`), or `dni.h`. Lab adds new files under `io.dotnetnativeinterop.lab` + `io.dotnetnativeinterop.ui.tabs`, and edits only `ui/AppShell.kt` to route `Tab.Lab`.

**Existing reusables to consume (do not reimplement):**
- `io.dotnetnativeinterop.feature.FeatureCatalogService` — `suspend fun run(id): FeatureResult`.
- `io.dotnetnativeinterop.feature.defaultServiceFor(t: TransportKind): FeatureCatalogService`.
- `io.dotnetnativeinterop.feature.FfiFeatureService` — used directly in the instrumented test.
- `io.dotnetnativeinterop.model.FeatureResult(id, result, elapsedMs, ok)`, `TransportKind(displayName)` { Ffi("FFI"), Http("HTTP"), Sqlite("SQLCipher") }.
- `io.dotnetnativeinterop.ui.components.TransportPicker(selected, onSelect, modifier)` — segmented transport picker.
- `io.dotnetnativeinterop.transport.NativeBridge` — `nativeInitialize()`, `nativeFeatureRun(id)`.

---

### Task 1: RasterPayload — decode `WxHxC:base64` → RasterImage

**Files:**
- Create: `android/app/src/main/kotlin/io/dotnetnativeinterop/lab/RasterPayload.kt`
- Test: `android/app/src/test/kotlin/io/dotnetnativeinterop/lab/RasterPayloadTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package io.dotnetnativeinterop.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

public class RasterPayloadTest {

    @Test
    public fun decodesRgbPayload() {
        // 2x2x3 = 12 bytes: red, green, blue, white
        val bytes = byteArrayOf(
            255.toByte(), 0, 0,
            0, 255.toByte(), 0,
            0, 0, 255.toByte(),
            255.toByte(), 255.toByte(), 255.toByte(),
        )
        val payload = "2x2x3:" + Base64.getEncoder().encodeToString(bytes)
        val img = RasterPayload.decode(payload)!!
        assertEquals(2, img.width)
        assertEquals(2, img.height)
        assertEquals(0xFFFF0000.toInt(), img.pixels[0]) // red
        assertEquals(0xFF00FF00.toInt(), img.pixels[1]) // green
        assertEquals(0xFF0000FF.toInt(), img.pixels[2]) // blue
        assertEquals(0xFFFFFFFF.toInt(), img.pixels[3]) // white
    }

    @Test
    public fun decodesGrayscaleAndLegacyHeader() {
        val bytes = byteArrayOf(0, 128.toByte(), 255.toByte(), 64)
        val payload = "2x2:" + Base64.getEncoder().encodeToString(bytes) // legacy, C defaults to 1
        val img = RasterPayload.decode(payload)!!
        assertEquals(2, img.width)
        assertEquals(0xFF000000.toInt(), img.pixels[0])
        assertEquals(0xFF808080.toInt(), img.pixels[1])
        assertEquals(0xFFFFFFFF.toInt(), img.pixels[2])
    }

    @Test
    public fun returnsNullOnMalformed() {
        assertNull("no colon", RasterPayload.decode("not-a-payload"))
        assertNull("bad dims", RasterPayload.decode("axb:AAAA"))
        assertNull("wrong length", RasterPayload.decode("2x2x3:" + Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))))
        assertNull("bad base64", RasterPayload.decode("2x2x3:@@@@"))
        assertTrue(true)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Sync the new files, then: `$GR --no-daemon :app:testDebugUnitTest --tests "io.dotnetnativeinterop.lab.RasterPayloadTest"`
Expected: FAIL — unresolved reference `RasterPayload`.

- [ ] **Step 3: Write the implementation**

```kotlin
package io.dotnetnativeinterop.lab

import java.util.Base64

/**
 * A decoded raster frame: ARGB_8888 pixels, row-major. Pure Kotlin (no android.graphics.Bitmap in the
 * decode path) so it is JVM-unit-testable; the UI layer turns this into an ImageBitmap.
 */
public data class RasterImage(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is RasterImage && width == other.width && height == other.height && pixels.contentEquals(other.pixels))

    override fun hashCode(): Int = (width * 31 + height) * 31 + pixels.contentHashCode()
}

/**
 * Decodes the engine's visual payload `"WxHxC:base64"` (C=1 grayscale or C=3 RGB; legacy `"WxH:base64"`
 * is treated as C=1) into ARGB pixels. Returns null on a malformed header, bad base64, or a byte count
 * that does not equal W*H*C. Never throws.
 */
public object RasterPayload {
    public fun decode(payload: String): RasterImage? {
        val colon = payload.indexOf(':')
        if (colon <= 0) return null
        val axes = payload.substring(0, colon).split('x')
        if (axes.size < 2) return null
        val w = axes[0].toIntOrNull() ?: return null
        val h = axes[1].toIntOrNull() ?: return null
        val c = if (axes.size >= 3) (axes[2].toIntOrNull() ?: return null) else 1
        if (w <= 0 || h <= 0 || (c != 1 && c != 3)) return null

        val bytes = try {
            Base64.getDecoder().decode(payload.substring(colon + 1))
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (bytes.size != w * h * c) return null

        val pixels = IntArray(w * h)
        if (c == 1) {
            for (i in pixels.indices) {
                val v = bytes[i].toInt() and 0xFF
                pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        } else {
            var b = 0
            for (i in pixels.indices) {
                val r = bytes[b].toInt() and 0xFF
                val g = bytes[b + 1].toInt() and 0xFF
                val bl = bytes[b + 2].toInt() and 0xFF
                pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
                b += 3
            }
        }
        return RasterImage(w, h, pixels)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `$GR --no-daemon :app:testDebugUnitTest --tests "io.dotnetnativeinterop.lab.RasterPayloadTest"` → PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/io/dotnetnativeinterop/lab/RasterPayload.kt android/app/src/test/kotlin/io/dotnetnativeinterop/lab/RasterPayloadTest.kt
git commit -m "feat(android): Lab RasterPayload — decode WxHxC:base64 to ARGB"
```

---

### Task 2: Benchmark — JSON models + lenient decode

**Files:**
- Create: `android/app/src/main/kotlin/io/dotnetnativeinterop/lab/Benchmark.kt`
- Test: `android/app/src/test/kotlin/io/dotnetnativeinterop/lab/BenchmarkTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package io.dotnetnativeinterop.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

public class BenchmarkTest {

    private val fixture = """
        { "kind": "benchmark", "title": "SIMD matrix multiply",
          "series": [ { "name": "scalar", "points": [ { "x": 64, "y": 1.2 }, { "x": 128, "y": 0.8 } ] },
                      { "name": "SIMD",   "points": [ { "x": 64, "y": 0.2 } ] } ],
          "summary": [ { "label": "peak GFLOPS", "value": "41.8" } ],
          "extraIgnored": true }
    """.trimIndent()

    @Test
    public fun decodesPayload() {
        val p = Benchmark.decode(fixture)!!
        assertEquals("SIMD matrix multiply", p.title)
        assertEquals(2, p.series.size)
        assertEquals("scalar", p.series[0].name)
        assertEquals(2, p.series[0].points.size)
        assertEquals(64.0, p.series[0].points[0].x, 1e-9)
        assertEquals(1.2, p.series[0].points[0].y, 1e-9)
        assertEquals("peak GFLOPS", p.summary[0].label)
        assertEquals("41.8", p.summary[0].value)
    }

    @Test
    public fun returnsNullOnMalformed() {
        assertNull(Benchmark.decode("not json"))
        assertNull(Benchmark.decode("64x64x3:AAAA"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `$GR --no-daemon :app:testDebugUnitTest --tests "io.dotnetnativeinterop.lab.BenchmarkTest"` → FAIL (unresolved `Benchmark`).

- [ ] **Step 3: Write the implementation**

```kotlin
package io.dotnetnativeinterop.lab

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
public data class BenchmarkPoint(val x: Double, val y: Double)

@Serializable
public data class BenchmarkSeries(val name: String, val points: List<BenchmarkPoint>)

@Serializable
public data class SummaryStat(val label: String, val value: String)

@Serializable
public data class BenchmarkPayload(
    val kind: String,
    val title: String,
    val series: List<BenchmarkSeries>,
    val summary: List<SummaryStat>,
)

/** Decodes the engine's benchmark `result` JSON (camelCase). Returns null on any parse failure. */
public object Benchmark {
    private val json = Json { ignoreUnknownKeys = true }

    public fun decode(result: String): BenchmarkPayload? =
        try {
            json.decodeFromString<BenchmarkPayload>(result)
        } catch (_: Exception) {
            null
        }
}
```

- [ ] **Step 4: Run test to verify it passes** → PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/io/dotnetnativeinterop/lab/Benchmark.kt android/app/src/test/kotlin/io/dotnetnativeinterop/lab/BenchmarkTest.kt
git commit -m "feat(android): Lab Benchmark — payload models + lenient JSON decode"
```

---

### Task 3: LabCommands — id builders matching iOS exactly

**Files:**
- Create: `android/app/src/main/kotlin/io/dotnetnativeinterop/lab/LabCommands.kt`
- Test: `android/app/src/test/kotlin/io/dotnetnativeinterop/lab/LabCommandsTest.kt`

The strings must match `ios/Shared/Lab/ShowcaseModels.swift` `currentCommand()` byte-for-byte: mandelbrot uses `%.6f` for cx/cy/zoom and the integer iterations; raymarch uses `%.3f` for angle. Both pin `Locale.ROOT` so a comma decimal separator never appears.

- [ ] **Step 1: Write the failing test**

```kotlin
package io.dotnetnativeinterop.lab

import org.junit.Assert.assertEquals
import org.junit.Test

public class LabCommandsTest {

    @Test
    public fun mandelbrotMatchesIosFormat() {
        assertEquals(
            "viz-mandelbrot~cx_-0.500000~cy_0.000000~zoom_1.000000~iters_220~w_256~h_256",
            LabCommands.mandelbrot(cx = -0.5, cy = 0.0, zoom = 1.0, iters = 220),
        )
    }

    @Test
    public fun raymarchMatchesIosFormat() {
        assertEquals("viz-raymarch~angle_0.000~w_220~h_220", LabCommands.raymarch(angle = 0.0))
        assertEquals("viz-raymarch~angle_1.571~w_220~h_220", LabCommands.raymarch(angle = 1.5708))
    }

    @Test
    public fun benchmarksUseDefaults() {
        assertEquals("bench-matmul~max_384", LabCommands.matmul())
        assertEquals("bench-parallel~size_480", LabCommands.parallel())
        assertEquals("bench-matmul~max_128", LabCommands.matmul(128))
    }
}
```

- [ ] **Step 2: Run test to verify it fails** → FAIL (unresolved `LabCommands`).

- [ ] **Step 3: Write the implementation**

```kotlin
package io.dotnetnativeinterop.lab

import java.util.Locale

/** Builds the parametric ShowcaseCommand ids the Lab demos run over the existing feature path.
 *  Formats mirror the iOS currentCommand() exactly (Locale.ROOT, %.6f / %.3f). */
public object LabCommands {
    private fun d6(v: Double): String = String.format(Locale.ROOT, "%.6f", v)
    private fun d3(v: Double): String = String.format(Locale.ROOT, "%.3f", v)

    public fun mandelbrot(cx: Double, cy: Double, zoom: Double, iters: Int, size: Int = 256): String =
        "viz-mandelbrot~cx_${d6(cx)}~cy_${d6(cy)}~zoom_${d6(zoom)}~iters_${iters}~w_${size}~h_${size}"

    public fun raymarch(angle: Double, size: Int = 220): String =
        "viz-raymarch~angle_${d3(angle)}~w_${size}~h_${size}"

    public fun matmul(max: Int = 384): String = "bench-matmul~max_$max"

    public fun parallel(size: Int = 480): String = "bench-parallel~size_$size"
}
```

- [ ] **Step 4: Run test to verify it passes** → PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/io/dotnetnativeinterop/lab/LabCommands.kt android/app/src/test/kotlin/io/dotnetnativeinterop/lab/LabCommandsTest.kt
git commit -m "feat(android): Lab command builders (iOS-exact id formatting)"
```

---

### Task 4: LabViewModel — shared transport + render over the existing service

**Files:**
- Create: `android/app/src/main/kotlin/io/dotnetnativeinterop/lab/LabViewModel.kt`
- Test: `android/app/src/test/kotlin/io/dotnetnativeinterop/lab/LabViewModelTest.kt`

Plain `ViewModel` (no Application needed — `defaultServiceFor` takes no context, same as `FeaturesViewModel`). One shared `transport` `StateFlow` so all four demos share one picker. `serviceFor` is injectable for testing.

- [ ] **Step 1: Write the failing test**

```kotlin
package io.dotnetnativeinterop.lab

import io.dotnetnativeinterop.feature.FeatureCatalogService
import io.dotnetnativeinterop.model.FeatureDescriptor
import io.dotnetnativeinterop.model.FeatureResult
import io.dotnetnativeinterop.model.TransportKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

public class LabViewModelTest {

    private class FakeService(private val tag: String) : FeatureCatalogService {
        override suspend fun descriptors(): List<FeatureDescriptor> = emptyList()
        override suspend fun run(id: String): FeatureResult = FeatureResult(id, "$tag:$id", 1.0, true)
    }

    @Test
    public fun renderUsesSelectedTransport(): Unit = runBlocking {
        val vm = LabViewModel(serviceFor = { t -> FakeService(t.name) })
        assertEquals("Ffi:cmd", vm.render("cmd")?.result)
        vm.setTransport(TransportKind.Sqlite)
        assertEquals(TransportKind.Sqlite, vm.transport.value)
        assertEquals("Sqlite:cmd", vm.render("cmd")?.result)
    }

    @Test
    public fun renderReturnsNullOnError(): Unit = runBlocking {
        val vm = LabViewModel(serviceFor = {
            object : FeatureCatalogService {
                override suspend fun descriptors() = emptyList<FeatureDescriptor>()
                override suspend fun run(id: String): FeatureResult = error("boom")
            }
        })
        assertEquals(null, vm.render("cmd"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails** → FAIL (unresolved `LabViewModel`).

- [ ] **Step 3: Write the implementation**

```kotlin
package io.dotnetnativeinterop.lab

import androidx.lifecycle.ViewModel
import io.dotnetnativeinterop.feature.FeatureCatalogService
import io.dotnetnativeinterop.feature.defaultServiceFor
import io.dotnetnativeinterop.model.FeatureResult
import io.dotnetnativeinterop.model.TransportKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Backs the Lab tab: one shared selected transport, and a single render() that runs a parametric
 *  ShowcaseCommand id over the existing FeatureCatalogService.run path. */
public class LabViewModel(
    private val serviceFor: (TransportKind) -> FeatureCatalogService = ::defaultServiceFor,
) : ViewModel() {

    private val _transport = MutableStateFlow(TransportKind.Ffi)
    public val transport: StateFlow<TransportKind> = _transport.asStateFlow()

    public fun setTransport(t: TransportKind) { _transport.value = t }

    /** Runs one command over the currently selected transport; null on error (surfaced as a fallback). */
    public suspend fun render(command: String): FeatureResult? =
        runCatching { serviceFor(_transport.value).run(command) }.getOrNull()
}
```

- [ ] **Step 4: Run test to verify it passes** → PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/io/dotnetnativeinterop/lab/LabViewModel.kt android/app/src/test/kotlin/io/dotnetnativeinterop/lab/LabViewModelTest.kt
git commit -m "feat(android): LabViewModel — shared transport + render over feature path"
```

---

### Task 5: RasterCanvas + frame loop (RasterDemoHost)

**Files:**
- Create: `android/app/src/main/kotlin/io/dotnetnativeinterop/ui/tabs/RasterCanvas.kt`

No unit test (Compose UI + native render loop; covered by the smoke + instrumented tests). This holds the `RasterImage → ImageBitmap` bridge, the presentational canvas with the live readout, and the frame loop that mirrors the iOS `RasterDemo.renderLoop` (fps = min(120, 1000/ms); idle 50 ms when not animating and the command is unchanged).

- [ ] **Step 1: Write the implementation**

```kotlin
package io.dotnetnativeinterop.ui.tabs

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.dotnetnativeinterop.lab.RasterImage
import io.dotnetnativeinterop.lab.RasterPayload
import io.dotnetnativeinterop.model.FeatureResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal fun RasterImage.toImageBitmap() =
    Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()

/**
 * Drives a visual demo: renders currentCommand() as fast as the transport allows, re-rendering
 * immediately while animating() and otherwise only when the command changes. Bitmap + FPS state are
 * Compose state on the main thread; render() is suspend and hops to IO inside the service.
 */
@Composable
internal fun RasterDemoHost(
    transportName: String,
    animating: () -> Boolean,
    currentCommand: () -> String,
    advance: () -> Unit,
    render: suspend (String) -> FeatureResult?,
    gestureModifier: Modifier = Modifier,
) {
    var image by remember { mutableStateOf<RasterImage?>(null) }
    var fps by remember { mutableStateOf(0.0) }
    var frameMs by remember { mutableStateOf(0.0) }
    var dims by remember { mutableStateOf("—") }

    LaunchedEffect(Unit) {
        var last = ""
        while (isActive) {
            val command = currentCommand()
            if (animating() || command != last) {
                val start = System.nanoTime()
                val result = render(command)
                if (result != null) {
                    RasterPayload.decode(result.result)?.let {
                        image = it
                        dims = "${it.width}×${it.height}"
                    }
                    val ms = (System.nanoTime() - start) / 1_000_000.0
                    frameMs = ms
                    fps = if (ms > 0) minOf(120.0, 1000.0 / ms) else 0.0
                    last = command
                }
                if (animating()) advance()
            } else {
                delay(50)
            }
        }
    }

    RasterCanvas(image, fps, frameMs, dims, transportName, gestureModifier)
}

/** Presentational frame + live readout (fps · ms/frame · dims · transport). No logic. */
@Composable
internal fun RasterCanvas(
    image: RasterImage?,
    fps: Double,
    frameMs: Double,
    dims: String,
    transport: String,
    gestureModifier: Modifier = Modifier,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(340.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
                .then(gestureModifier),
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) {
                Image(
                    bitmap = image.toImageBitmap(),
                    contentDescription = "rendered frame",
                    filterQuality = FilterQuality.None,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text("Rendering…", color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("%.1f fps".format(fps), style = MaterialTheme.typography.bodySmall)
            Text("%.1f ms".format(frameMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(dims, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(transport, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
```

- [ ] **Step 2: Compile check**

Sync, then `$GR --no-daemon :app:assembleDebug` → BUILD SUCCESSFUL (RasterCanvas has no callers yet; this confirms it compiles).

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/kotlin/io/dotnetnativeinterop/ui/tabs/RasterCanvas.kt
git commit -m "feat(android): Lab RasterCanvas + frame loop (FPS readout, iOS-parity)"
```

---

### Task 6: FractalExplorerScreen

**Files:**
- Create: `android/app/src/main/kotlin/io/dotnetnativeinterop/ui/tabs/FractalExplorerScreen.kt`

Parity with `ios/Shared/Lab/FractalExplorerView.swift`: pinch-zoom `zoom = max(0.2, zoom * gestureZoom)`, drag-pan `span = 3/zoom`, `centerX -= dx/340*span`, iterations slider 32–1000, Dive toggle (`zoom *= 1.03` per frame), Reset, transport picker, caption.

- [ ] **Step 1: Write the implementation**

```kotlin
package io.dotnetnativeinterop.ui.tabs

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.dotnetnativeinterop.lab.LabCommands
import io.dotnetnativeinterop.lab.LabViewModel
import io.dotnetnativeinterop.ui.components.TransportPicker

@Composable
internal fun FractalExplorerScreen(lab: LabViewModel, modifier: Modifier = Modifier) {
    val transport by lab.transport.collectAsStateWithLifecycle()
    var centerX by remember { mutableDoubleStateOf(-0.5) }
    var centerY by remember { mutableDoubleStateOf(0.0) }
    var zoom by remember { mutableDoubleStateOf(1.0) }
    var iterations by remember { mutableFloatStateOf(220f) }
    var diving by remember { mutableStateOf(false) }
    val size = 256

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RasterDemoHost(
            transportName = transport.displayName,
            animating = { diving },
            currentCommand = { LabCommands.mandelbrot(centerX, centerY, zoom, iterations.toInt(), size) },
            advance = { zoom *= 1.03 },
            render = { lab.render(it) },
            gestureModifier = Modifier.pointerInput(Unit) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    zoom = maxOf(0.2, zoom * gestureZoom)
                    val span = 3.0 / zoom
                    centerX -= pan.x / 340.0 * span
                    centerY -= pan.y / 340.0 * span
                }
            },
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = diving, onCheckedChange = { diving = it })
            Text("Dive (auto-zoom)")
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Iterations")
            Slider(
                value = iterations,
                onValueChange = { iterations = it },
                valueRange = 32f..1000f,
                modifier = Modifier.weight(1f),
            )
            Text("${iterations.toInt()}", modifier = Modifier.width(44.dp))
        }
        Button(onClick = { centerX = -0.5; centerY = 0.0; zoom = 1.0; iterations = 220f }) {
            Text("Reset view")
        }
        TransportPicker(selected = transport, onSelect = lab::setTransport, modifier = Modifier.fillMaxWidth())
        Text(
            "Every pixel of this Mandelbrot is computed in C# inside the NativeAOT library and sent as raw "
                + "bytes — no GPU, no shader, no cloud. Switch transport to watch the frame rate change.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
```

- [ ] **Step 2: Compile check** — `$GR --no-daemon :app:assembleDebug` → BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/kotlin/io/dotnetnativeinterop/ui/tabs/FractalExplorerScreen.kt
git commit -m "feat(android): Lab Fractal Explorer (pinch/pan/dive, transport picker)"
```

---

### Task 7: RaymarcherScreen

**Files:**
- Create: `android/app/src/main/kotlin/io/dotnetnativeinterop/ui/tabs/RaymarcherScreen.kt`

Parity with `ios/Shared/Lab/RaymarcherView.swift`: auto-rotate toggle (`angle += 0.03` per frame), drag-orbit (`angle += dx/120`, stops auto-rotate while dragging), transport picker, caption.

- [ ] **Step 1: Write the implementation**

```kotlin
package io.dotnetnativeinterop.ui.tabs

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.dotnetnativeinterop.lab.LabCommands
import io.dotnetnativeinterop.lab.LabViewModel
import io.dotnetnativeinterop.ui.components.TransportPicker

@Composable
internal fun RaymarcherScreen(lab: LabViewModel, modifier: Modifier = Modifier) {
    val transport by lab.transport.collectAsStateWithLifecycle()
    var angle by remember { mutableDoubleStateOf(0.0) }
    var spinning by remember { mutableStateOf(true) }
    val size = 220

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RasterDemoHost(
            transportName = transport.displayName,
            animating = { spinning },
            currentCommand = { LabCommands.raymarch(angle, size) },
            advance = { angle += 0.03 },
            render = { lab.render(it) },
            gestureModifier = Modifier.pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    spinning = false
                    angle += drag.x / 120.0
                }
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = spinning, onCheckedChange = { spinning = it })
            Text("Auto-rotate")
        }
        TransportPicker(selected = transport, onSelect = lab::setTransport, modifier = Modifier.fillMaxWidth())
        Text(
            "A signed-distance-field raymarcher — sphere, ground plane, soft shadow — with every ray "
                + "traced on the CPU in C#. No GPU, no shaders.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
```

- [ ] **Step 2: Compile check** — `$GR --no-daemon :app:assembleDebug` → BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/kotlin/io/dotnetnativeinterop/ui/tabs/RaymarcherScreen.kt
git commit -m "feat(android): Lab Raymarcher (auto-rotate + drag-orbit)"
```

---

### Task 8: BenchmarkChart + BenchmarkScreen

**Files:**
- Create: `android/app/src/main/kotlin/io/dotnetnativeinterop/lab/BenchmarkChart.kt`
- Create: `android/app/src/main/kotlin/io/dotnetnativeinterop/ui/tabs/BenchmarkScreen.kt`

`BenchmarkChart` is a hand-drawn multi-series line chart on a Compose `Canvas` (no chart library) — axes, per-series polyline + points, min/max tick labels, and a legend. `BenchmarkScreen` runs the command over the selected transport, decodes, and shows the chart + summary row (mirrors `BenchmarkDetailView.swift`).

- [ ] **Step 1: Write BenchmarkChart**

```kotlin
package io.dotnetnativeinterop.lab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val seriesColors = listOf(
    Color(0xFF3B82F6), Color(0xFFF59E0B), Color(0xFFEF4444), Color(0xFF10B981),
)

/** Multi-series line+point chart of benchmark series, drawn on a Canvas (no chart dependency). */
@Composable
internal fun BenchmarkChart(series: List<BenchmarkSeries>, modifier: Modifier = Modifier) {
    val allPoints = series.flatMap { it.points }
    if (allPoints.isEmpty()) {
        Text("No data", style = MaterialTheme.typography.bodySmall)
        return
    }
    val minX = allPoints.minOf { it.x }
    val maxX = allPoints.maxOf { it.x }
    val maxY = allPoints.maxOf { it.y }.coerceAtLeast(1e-9)
    val axisColor = MaterialTheme.colorScheme.outline
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer: TextMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = labelColor, fontSize = 10.sp)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(modifier = modifier.fillMaxWidth().height(240.dp)) {
            val padL = 52f
            val padB = 26f
            val padT = 10f
            val padR = 10f
            val w = size.width - padL - padR
            val h = size.height - padT - padB
            val spanX = (maxX - minX).coerceAtLeast(1e-9)
            fun sx(x: Double) = padL + ((x - minX) / spanX * w).toFloat()
            fun sy(y: Double) = padT + (h - (y / maxY * h)).toFloat()

            drawLine(axisColor, Offset(padL, padT), Offset(padL, padT + h), strokeWidth = 2f)
            drawLine(axisColor, Offset(padL, padT + h), Offset(padL + w, padT + h), strokeWidth = 2f)

            series.forEachIndexed { i, s ->
                val color = seriesColors[i % seriesColors.size]
                val pts = s.points.sortedBy { it.x }
                for (j in 1 until pts.size) {
                    drawLine(
                        color,
                        Offset(sx(pts[j - 1].x), sy(pts[j - 1].y)),
                        Offset(sx(pts[j].x), sy(pts[j].y)),
                        strokeWidth = 3f,
                    )
                }
                pts.forEach { drawCircle(color, 4f, Offset(sx(it.x), sy(it.y))) }
            }

            drawText(measurer, "%.1f".format(maxY), Offset(2f, padT - 4f), labelStyle)
            drawText(measurer, "0", Offset(2f, padT + h - 6f), labelStyle)
            drawText(measurer, minX.toInt().toString(), Offset(padL, padT + h + 4f), labelStyle)
            drawText(measurer, maxX.toInt().toString(), Offset(padL + w - 28f, padT + h + 4f), labelStyle)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            series.forEachIndexed { i, s ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(10.dp).background(seriesColors[i % seriesColors.size]))
                    Text(s.name, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
```

- [ ] **Step 2: Write BenchmarkScreen**

```kotlin
package io.dotnetnativeinterop.ui.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.dotnetnativeinterop.lab.Benchmark
import io.dotnetnativeinterop.lab.BenchmarkChart
import io.dotnetnativeinterop.lab.BenchmarkPayload
import io.dotnetnativeinterop.lab.LabViewModel
import io.dotnetnativeinterop.ui.components.TransportPicker
import kotlinx.coroutines.launch

@Composable
internal fun BenchmarkScreen(
    lab: LabViewModel,
    command: String,
    modifier: Modifier = Modifier,
) {
    val transport by lab.transport.collectAsStateWithLifecycle()
    var payload by remember { mutableStateOf<BenchmarkPayload?>(null) }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = {
                scope.launch {
                    running = true
                    error = null
                    val r = lab.render(command)
                    when {
                        r == null -> error = "The native library returned no data."
                        else -> {
                            val p = Benchmark.decode(r.result)
                            if (p == null) error = "Could not decode benchmark JSON." else payload = p
                        }
                    }
                    running = false
                }
            },
            enabled = !running,
        ) {
            if (running) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Running…")
                }
            } else {
                Text("Run benchmark")
            }
        }

        TransportPicker(selected = transport, onSelect = lab::setTransport, modifier = Modifier.fillMaxWidth())
        Text(
            "The benchmark executes inside the NativeAOT library and returns its series as JSON over the "
                + "selected transport.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        payload?.let { p ->
            Text(p.title, style = MaterialTheme.typography.titleMedium)
            BenchmarkChart(p.series)
            Text("Summary", style = MaterialTheme.typography.titleMedium)
            p.summary.forEach { stat ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stat.label, style = MaterialTheme.typography.bodyMedium)
                    Text(stat.value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}
```

- [ ] **Step 3: Compile check** — `$GR --no-daemon :app:assembleDebug` → BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/kotlin/io/dotnetnativeinterop/lab/BenchmarkChart.kt android/app/src/main/kotlin/io/dotnetnativeinterop/ui/tabs/BenchmarkScreen.kt
git commit -m "feat(android): Lab benchmark chart (Canvas) + run/decode screen"
```

---

### Task 9: LabScreen list + internal routing + AppShell wiring

**Files:**
- Create: `android/app/src/main/kotlin/io/dotnetnativeinterop/ui/tabs/LabScreen.kt`
- Modify: `android/app/src/main/kotlin/io/dotnetnativeinterop/ui/AppShell.kt`

`LabScreen` owns internal route state (list ↔ detail), mirroring the AppShell Features detail pattern. The AppShell top bar stays "Lab"; each detail shows its own title + a back affordance.

- [ ] **Step 1: Write LabScreen**

```kotlin
package io.dotnetnativeinterop.ui.tabs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.dotnetnativeinterop.lab.LabCommands
import io.dotnetnativeinterop.lab.LabViewModel

internal sealed interface LabRoute {
    data object List : LabRoute
    data object Fractal : LabRoute
    data object Raymarch : LabRoute
    data class Bench(val title: String, val command: String) : LabRoute
}

@Composable
internal fun LabScreen(lab: LabViewModel, modifier: Modifier = Modifier) {
    var route by remember { mutableStateOf<LabRoute>(LabRoute.List) }

    when (val r = route) {
        LabRoute.List -> LabList(onOpen = { route = it }, modifier = modifier)
        LabRoute.Fractal -> LabDetail("Fractal Explorer", { route = LabRoute.List }, modifier) {
            FractalExplorerScreen(lab)
        }
        LabRoute.Raymarch -> LabDetail("Raymarched 3D", { route = LabRoute.List }, modifier) {
            RaymarcherScreen(lab)
        }
        is LabRoute.Bench -> LabDetail(r.title, { route = LabRoute.List }, modifier) {
            BenchmarkScreen(lab, r.command)
        }
    }
}

@Composable
private fun LabList(onOpen: (LabRoute) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier) {
        item {
            Text(
                "Visual — every pixel computed in C#",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        item {
            ListItem(
                headlineContent = { Text("Fractal Explorer") },
                supportingContent = { Text("Interactive colorized Mandelbrot") },
                modifier = Modifier.clickable { onOpen(LabRoute.Fractal) },
            )
        }
        item {
            ListItem(
                headlineContent = { Text("Raymarched 3D") },
                supportingContent = { Text("CPU signed-distance-field scene") },
                modifier = Modifier.clickable { onOpen(LabRoute.Raymarch) },
            )
        }
        item {
            Text(
                "Benchmarks — NativeAOT throughput",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        item {
            ListItem(
                headlineContent = { Text("SIMD Matrix Multiply") },
                supportingContent = { Text("Scalar vs SIMD GFLOPS") },
                modifier = Modifier.clickable { onOpen(LabRoute.Bench("SIMD Matmul", LabCommands.matmul())) },
            )
        }
        item {
            ListItem(
                headlineContent = { Text("Parallel Scaling") },
                supportingContent = { Text("Single-thread vs Parallel.For") },
                modifier = Modifier.clickable { onOpen(LabRoute.Bench("Parallel Scaling", LabCommands.parallel())) },
            )
        }
    }
}

@Composable
private fun LabDetail(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
            leadingContent = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )
        content()
    }
}
```

- [ ] **Step 2: Wire AppShell**

In `android/app/src/main/kotlin/io/dotnetnativeinterop/ui/AppShell.kt`, replace the `Tab.Lab -> GatedTabScreen(...)` branch with:

```kotlin
                Tab.Lab -> {
                    val lab: io.dotnetnativeinterop.lab.LabViewModel = viewModel()
                    LabScreen(lab, content)
                }
```

Then remove the now-unused `import io.dotnetnativeinterop.ui.tabs.GatedTabScreen` line if no other tab references `GatedTabScreen` (Stream uses `InferenceScreen`; after this change Lab was the last `GatedTabScreen` caller). Leave `GatedTabScreen.kt` in place (harmless, reusable for any future gate).

- [ ] **Step 3: Compile check** — sync `AppShell.kt` + `LabScreen.kt`, then `$GR --no-daemon :app:assembleDebug` → BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/kotlin/io/dotnetnativeinterop/ui/tabs/LabScreen.kt android/app/src/main/kotlin/io/dotnetnativeinterop/ui/AppShell.kt
git commit -m "feat(android): Lab tab list + routing; wire AppShell Tab.Lab"
```

---

### Task 10: On-device proof — instrumented LabTabTest (real FFI)

**Files:**
- Create: `android/app/src/androidTest/kotlin/io/dotnetnativeinterop/LabTabTest.kt`

Runs the actual `ShowcaseCommand` ids through the real FFI on the emulator and validates both decoders end-to-end. No `AssetExtractor` (Lab uses no ONNX assets); no warm-up loop (ShowcaseCommand is synchronous compute, not lazy ONNX). Load the native libs + `nativeInitialize()` like `AiTabTest`, then run via `FfiFeatureService` directly.

- [ ] **Step 1: Write the test**

```kotlin
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
```

- [ ] **Step 2: Run on the emulator**

Sync the test, ensure the emulator is up, then:
`$GR --no-daemon :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.dotnetnativeinterop.LabTabTest`
Expected: BUILD SUCCESSFUL, 2 tests passed; logcat `EvsTab`-style `PASS:` lines via `adb logcat -d -s LabTab:I`.

If `mandelbrotCommandRendersDecodableFrame` fails on the header: the engine may emit C=1 for `viz-mandelbrot` (grayscale) — the regex already allows `[13]`. If the decode returns null, inspect the real `result` prefix (logged) and fix `RasterPayload` — do NOT weaken the assertion.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/androidTest/kotlin/io/dotnetnativeinterop/LabTabTest.kt
git commit -m "test(android): Lab — ShowcaseCommand renders + decodes on device (FFI)"
```

---

### Task 11: Smoke + PR

**Files:** none (verification + delivery).

- [ ] **Step 1: Full debug build** — `$GR --no-daemon :app:assembleDebug` → BUILD SUCCESSFUL.

- [ ] **Step 2: Manual smoke** — install the debug APK, launch, open the drawer (tap the hamburger ~`72 160`; do NOT left-edge swipe — that is the Android back gesture), tap **Lab**, open **Fractal Explorer**, confirm the image renders and the FPS readout ticks. Screenshot the Lab list + Fractal, pull to the repo, send to the user, then delete the local PNG (do not commit it).

- [ ] **Step 3: Push + PR**

```bash
git push -u origin feat/android-lab-tab
gh pr create --base main --head feat/android-lab-tab --title "feat(android): Lab tab — visual compute + benchmarks (pure-Kotlin parity)" --body "<summary: four iOS Lab demos over the existing dni_feature_run command-in-id path; no ABI change, no native rebuild; on-device LabTabTest + JVM unit tests green>"
```

- [ ] **Step 4: Watch CI** — `gh run watch <ci-android run id> --exit-status`; confirm `ci-android` green. Report the PR URL.

---

## Self-Review (completed by plan author)

**Spec coverage:** Fractal (T6), Raymarcher (T7), Matmul + Parallel charts (T8 chart + T9 list entries), transport picker on every demo (T6/T7/T8 via `TransportPicker` + shared `LabViewModel` transport), `WxHxC:base64` decode (T1), benchmark JSON (T2), command-in-id builders (T3), frame loop + FPS readout (T5), LabScreen list + AppShell route (T9), on-device proof (T10), smoke + PR (T11). Error handling: `RasterPayload`/`Benchmark` return null on bad input (T1/T2); `RasterDemoHost` keeps the last good frame; `BenchmarkScreen` shows an error line. No engine/AI/EVS/transport changes.

**Placeholder scan:** No TBD/TODO; every code step has complete code. (The PR body in T11 Step 3 is intentionally a fill-at-time summary, not code.)

**Type consistency:** `LabViewModel.render(command): FeatureResult?` used consistently in T5/T6/T7/T8. `RasterPayload.decode → RasterImage?` (T1) consumed in T5/T10. `Benchmark.decode → BenchmarkPayload?` (T2) consumed in T8/T10. `LabCommands.mandelbrot/raymarch/matmul/parallel` (T3) used in T6/T7/T9. `TransportKind.displayName` used for the readout. `RasterImage.toImageBitmap()` defined in T5, used only there.
