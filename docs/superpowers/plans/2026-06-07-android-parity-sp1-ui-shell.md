# SP1 — Android UI Parity (Full Shell + Core Trio) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the full Android navigation shell (8 iOS tabs + the kept Stream screen) with Dashboard, Features (+detail), Compare, Latency, and About functional on the proven native ABI, and AI/Manuals/Lab as honest "needs native gate" placeholders.

**Architecture:** Material3 `ModalNavigationDrawer` + a `selectedTab` state (no navigation-compose). MVVM mirroring iOS: `Screen → ViewModel → FeatureCatalogService → {Ffi|Http|Sqlite}FeatureService → NativeBridge/OkHttp`. The catalog stack is brand-new and additive; the existing `transport/` streaming clients, `data/`, and `ui/InferenceScreen.kt` are untouched.

**Tech Stack:** Kotlin 2.0, Jetpack Compose (Material3, BOM 2024.09.02), androidx lifecycle-viewmodel-compose, kotlinx.serialization, OkHttp, coroutines. Build/proof on the arm64 emulator via the Mac mini.

**Spec:** `docs/superpowers/specs/2026-06-07-android-parity-sp1-ui-shell-design.md`

---

## Conventions (read once)

**Build host.** No .NET/engine changes in SP1 — the existing `jniLibs/arm64-v8a/libdni.so` (+ `libe_sqlcipher.so`) from SP0 already provide every export the UI calls. You only build the Android app.

**Mac overlay + build (used by every "verify on emulator" step).** Edit on Windows, then overlay the changed files into the Mac snapshot and build/test there. Env prelude (`PRELUDE`) and helpers:

```bash
# From the Windows repo root, sync specific paths to the Mac snapshot:
#   tar -cf - <paths...> | ssh steve-mac-mini "cd /Users/steve/dni-rag-build && tar -xf -"
#
# Remote prelude (prepend to every ssh 'zsh -lc "..."'):
PRELUDE='export ANDROID_HOME=$HOME/Library/Android/sdk JAVA_HOME=$HOME/Library/Java/jdk; export PATH=$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH; GR=$HOME/toolchain/gradle-8.9/bin/gradle; cd /Users/steve/dni-rag-build/android'
#
# Compile-only check (fast):           $GR --no-daemon :app:assembleDebug
# Instrumented test (emulator up):     $GR --no-daemon :app:connectedDebugAndroidTest \
#     -Pandroid.testInstrumentationRunnerArguments.class=io.dotnetnativeinterop.Sp1ShellTest
```

zsh gotcha: never start an `echo` argument with `=` (equals-expansion). Quote markers: `echo "-- x --"`.

**Models JSON.** The engine emits **camelCase** JSON. All `@Serializable` models use a `Json { ignoreUnknownKeys = true }` instance.

**Visibility.** The module compiles with `-Xexplicit-api=strict`: every top-level/public declaration needs an explicit visibility modifier (`public`/`internal`/`private`). New UI is `internal` unless it must cross packages.

**Commit discipline.** Conventional commits, no AI attribution, feature branch `feat/android-parity-sp1-ui-shell` only. One commit per task.

---

## File structure (new unless noted)

```
android/app/src/main/kotlin/io/dotnetnativeinterop/
  model/Models.kt                         # FeatureDescriptor, FeatureResult, RunStatus, TransportKind, EngineStats
  feature/FeatureCatalogService.kt        # interface
  feature/FfiFeatureService.kt            # NativeBridge.nativeFeaturesJson / nativeFeatureRun
  feature/SqliteFeatureService.kt         # NativeBridge.nativeSqliteFeatures / nativeSqliteRun
  feature/HttpFeatureService.kt           # nativeHttpStart -> OkHttp /features, /feature/run/{id}
  feature/FeaturesViewModel.kt            # shared by Dashboard + Features
  feature/ComparisonViewModel.kt          # run catalog over all transports + timing
  feature/LatencyViewModel.kt             # nativeEngineStats + run timing
  ui/AppShell.kt                          # ModalNavigationDrawer + tab state + content switch
  ui/components/TransportPicker.kt        # Material3 SegmentedButton over TransportKind
  ui/tabs/DashboardScreen.kt
  ui/tabs/FeaturesScreen.kt
  ui/tabs/FeatureDetailScreen.kt
  ui/tabs/CompareScreen.kt
  ui/tabs/LatencyScreen.kt
  ui/tabs/AboutScreen.kt
  ui/tabs/GatedTabScreen.kt               # placeholder for AI / Manuals / Lab
  MainActivity.kt                         # MODIFY: InferenceScreen -> AppShell
android/app/src/androidTest/kotlin/io/dotnetnativeinterop/
  Sp1ShellTest.kt                         # instrumented smoke test (emulator)
```

Untouched: `transport/**`, `data/**`, `ui/InferenceScreen.kt`, `ui/InferenceViewModel.kt`, `ui/Theme.kt`, `ui/PatternModel.kt`.

---

## Task 1: Models

**Files:**
- Create: `android/app/src/main/kotlin/io/dotnetnativeinterop/model/Models.kt`
- Test: `android/app/src/test/kotlin/io/dotnetnativeinterop/model/ModelsTest.kt`

- [ ] **Step 1: Write the failing unit test** (pure JVM, no device)

```kotlin
package io.dotnetnativeinterop.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun parsesDescriptorArray() {
        val src = """[{"id":"collection-expressions","title":"Collection expressions","version":"C# 12","code":"int[] a=[1];","expected":"a=[1]"}]"""
        val list = json.decodeFromString<List<FeatureDescriptor>>(src)
        assertEquals(1, list.size)
        assertEquals("collection-expressions", list[0].id)
        assertEquals("C# 12", list[0].version)
    }

    @Test fun parsesRunResult() {
        val r = json.decodeFromString<FeatureResult>("""{"id":"x","result":"ok","elapsedMs":0.5,"ok":true}""")
        assertEquals("x", r.id); assertTrue(r.ok); assertEquals(0.5, r.elapsedMs, 0.0001)
    }

    @Test fun parsesEngineStats() {
        val s = json.decodeFromString<EngineStats>("""{"gcGen0":1,"gcGen1":0,"gcGen2":0,"heapBytes":10,"committedBytes":20,"allocatedBytes":5,"gcPauseMs":0.1,"threadCount":4,"processorCount":8,"uptimeMs":12.3}""")
        assertEquals(8, s.processorCount)
    }
}
```

- [ ] **Step 2: Run it, verify it fails** — Run (Mac): sync the two files, then `$PRELUDE; $GR --no-daemon :app:testDebugUnitTest --tests "io.dotnetnativeinterop.model.ModelsTest"`. Expected: FAIL (unresolved `FeatureDescriptor`).

- [ ] **Step 3: Implement `Models.kt`**

```kotlin
package io.dotnetnativeinterop.model

import kotlinx.serialization.Serializable

/** Catalog entry (mirrors iOS FeatureDescriptor). Engine JSON is camelCase. */
@Serializable
public data class FeatureDescriptor(
    val id: String,
    val title: String,
    val version: String,
    val code: String,
    val expected: String,
)

/** One feature's execution result (mirrors iOS FeatureResult). */
@Serializable
public data class FeatureResult(
    val id: String,
    val result: String,
    val elapsedMs: Double,
    val ok: Boolean,
)

/** Live runtime telemetry (mirrors iOS EngineStats / dni_engine_stats). */
@Serializable
public data class EngineStats(
    val gcGen0: Int,
    val gcGen1: Int,
    val gcGen2: Int,
    val heapBytes: Long,
    val committedBytes: Long,
    val allocatedBytes: Long,
    val gcPauseMs: Double,
    val threadCount: Int,
    val processorCount: Int,
    val uptimeMs: Double,
)

/** Per-feature UI state. */
public enum class RunStatus { Idle, Running, Ok, Failed }

/** The three interop transports the catalog can be reached through. */
public enum class TransportKind(public val displayName: String) {
    Ffi("FFI"),
    Http("HTTP"),
    Sqlite("SQLCipher"),
}
```

- [ ] **Step 4: Run the test, verify it passes** — same command as Step 2. Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/io/dotnetnativeinterop/model/Models.kt \
        android/app/src/test/kotlin/io/dotnetnativeinterop/model/ModelsTest.kt
git commit -m "feat(android): SP1 catalog/telemetry models + JSON parsing"
```

---

## Task 2: FeatureCatalogService interface + FFI & SQLCipher impls

**Files:**
- Create: `feature/FeatureCatalogService.kt`, `feature/FfiFeatureService.kt`, `feature/SqliteFeatureService.kt`
- Verified by the instrumented test in Task 12 (these call the native lib → device-only).

- [ ] **Step 1: Implement the interface** (`feature/FeatureCatalogService.kt`)

```kotlin
package io.dotnetnativeinterop.feature

import io.dotnetnativeinterop.model.FeatureDescriptor
import io.dotnetnativeinterop.model.FeatureResult

/** Transport-agnostic catalog access (mirrors iOS FeatureService). Distinct from the streaming
 *  InferenceClient in transport/. Implementations are suspend + main-safe (do IO off the main thread). */
public interface FeatureCatalogService {
    public suspend fun descriptors(): List<FeatureDescriptor>
    public suspend fun run(id: String): FeatureResult
}
```

- [ ] **Step 2: Implement the FFI service** (`feature/FfiFeatureService.kt`)

```kotlin
package io.dotnetnativeinterop.feature

import io.dotnetnativeinterop.model.FeatureDescriptor
import io.dotnetnativeinterop.model.FeatureResult
import io.dotnetnativeinterop.transport.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

internal val catalogJson: Json = Json { ignoreUnknownKeys = true }

/** Catalog over the in-process C ABI (dni_features_json / dni_feature_run). */
public class FfiFeatureService : FeatureCatalogService {
    override suspend fun descriptors(): List<FeatureDescriptor> = withContext(Dispatchers.IO) {
        val raw = NativeBridge.nativeFeaturesJson()
            ?: error("nativeFeaturesJson returned null")
        catalogJson.decodeFromString(raw)
    }

    override suspend fun run(id: String): FeatureResult = withContext(Dispatchers.IO) {
        val raw = NativeBridge.nativeFeatureRun(id)
            ?: error("nativeFeatureRun($id) returned null")
        catalogJson.decodeFromString(raw)
    }
}
```

- [ ] **Step 3: Implement the SQLCipher service** (`feature/SqliteFeatureService.kt`)

```kotlin
package io.dotnetnativeinterop.feature

import io.dotnetnativeinterop.model.FeatureDescriptor
import io.dotnetnativeinterop.model.FeatureResult
import io.dotnetnativeinterop.transport.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Catalog over the encrypted SQLCipher store (dni_sqlite_features / dni_sqlite_run). */
public class SqliteFeatureService : FeatureCatalogService {
    override suspend fun descriptors(): List<FeatureDescriptor> = withContext(Dispatchers.IO) {
        val raw = NativeBridge.nativeSqliteFeatures()
            ?: error("nativeSqliteFeatures returned null")
        catalogJson.decodeFromString(raw)
    }

    override suspend fun run(id: String): FeatureResult = withContext(Dispatchers.IO) {
        val raw = NativeBridge.nativeSqliteRun(id)
            ?: error("nativeSqliteRun($id) returned null")
        catalogJson.decodeFromString(raw)
    }
}
```

- [ ] **Step 4: Verify it compiles** — sync the three files; Run (Mac): `$PRELUDE; $GR --no-daemon :app:assembleDebug`. Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/io/dotnetnativeinterop/feature/FeatureCatalogService.kt \
        android/app/src/main/kotlin/io/dotnetnativeinterop/feature/FfiFeatureService.kt \
        android/app/src/main/kotlin/io/dotnetnativeinterop/feature/SqliteFeatureService.kt
git commit -m "feat(android): FeatureCatalogService + FFI/SQLCipher catalog impls"
```

---

## Task 3: HTTP catalog service (loopback)

**Files:**
- Create: `feature/HttpFeatureService.kt`
- Verified by Task 12 (device-only).

Context: `NativeBridge.nativeHttpStart()` returns the loopback **port** (a positive int; ≤0 is failure). The engine's raw-HTTP host serves `GET /features` (descriptor array) and `GET /feature/run/{id}` (one result) — the same endpoints the iOS `HTTPFeatureService` uses. Start the server once, lazily.

- [ ] **Step 1: Implement** (`feature/HttpFeatureService.kt`)

```kotlin
package io.dotnetnativeinterop.feature

import io.dotnetnativeinterop.model.FeatureDescriptor
import io.dotnetnativeinterop.model.FeatureResult
import io.dotnetnativeinterop.transport.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Catalog over the in-process loopback HTTP server (dni_http_start -> GET /features, /feature/run/{id}). */
public class HttpFeatureService(
    private val client: OkHttpClient = OkHttpClient(),
) : FeatureCatalogService {

    private val startGate = Mutex()
    @Volatile private var port: Int = 0

    private suspend fun ensurePort(): Int {
        if (port > 0) return port
        startGate.withLock {
            if (port <= 0) {
                val p = NativeBridge.nativeHttpStart()
                require(p > 0) { "nativeHttpStart failed: $p" }
                port = p
            }
        }
        return port
    }

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val p = ensurePort()
        val req = Request.Builder().url("http://127.0.0.1:$p$path").build()
        client.newCall(req).execute().use { resp ->
            require(resp.isSuccessful) { "GET $path -> HTTP ${resp.code}" }
            resp.body?.string() ?: error("empty body for $path")
        }
    }

    override suspend fun descriptors(): List<FeatureDescriptor> =
        catalogJson.decodeFromString(get("/features"))

    override suspend fun run(id: String): FeatureResult =
        catalogJson.decodeFromString(get("/feature/run/$id"))
}
```

- [ ] **Step 2: Verify it compiles** — sync; Run (Mac): `$PRELUDE; $GR --no-daemon :app:assembleDebug`. Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/kotlin/io/dotnetnativeinterop/feature/HttpFeatureService.kt
git commit -m "feat(android): HTTP loopback catalog service"
```

---

## Task 4: FeaturesViewModel (shared by Dashboard + Features)

**Files:**
- Create: `feature/FeaturesViewModel.kt`

Holds the selected transport, the catalog, and per-feature status/results. Switching transport reloads the catalog. `runAll()` runs every feature on the selected transport. A factory maps `TransportKind` → service instance (services are cheap; recreate on switch).

- [ ] **Step 1: Implement** (`feature/FeaturesViewModel.kt`)

```kotlin
package io.dotnetnativeinterop.feature

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.dotnetnativeinterop.model.FeatureDescriptor
import io.dotnetnativeinterop.model.FeatureResult
import io.dotnetnativeinterop.model.RunStatus
import io.dotnetnativeinterop.model.TransportKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

public data class FeaturesUiState(
    val transport: TransportKind = TransportKind.Ffi,
    val descriptors: List<FeatureDescriptor> = emptyList(),
    val status: Map<String, RunStatus> = emptyMap(),
    val results: Map<String, FeatureResult> = emptyMap(),
    val loading: Boolean = false,
    val error: String? = null,
)

public class FeaturesViewModel(
    private val serviceFor: (TransportKind) -> FeatureCatalogService = ::defaultServiceFor,
) : ViewModel() {

    private val _state = MutableStateFlow(FeaturesUiState())
    public val state: StateFlow<FeaturesUiState> = _state.asStateFlow()

    init { loadCatalog() }

    public fun selectTransport(t: TransportKind) {
        if (t == _state.value.transport) return
        _state.update { it.copy(transport = t, descriptors = emptyList(), status = emptyMap(), results = emptyMap()) }
        loadCatalog()
    }

    public fun loadCatalog() {
        val svc = serviceFor(_state.value.transport)
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { svc.descriptors() }
                .onSuccess { d -> _state.update { it.copy(descriptors = d, loading = false) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }

    public fun run(id: String) {
        val svc = serviceFor(_state.value.transport)
        viewModelScope.launch {
            _state.update { it.copy(status = it.status + (id to RunStatus.Running)) }
            runCatching { svc.run(id) }
                .onSuccess { r ->
                    _state.update {
                        it.copy(
                            results = it.results + (id to r),
                            status = it.status + (id to if (r.ok) RunStatus.Ok else RunStatus.Failed),
                        )
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(status = it.status + (id to RunStatus.Failed), error = e.message) }
                }
        }
    }

    public fun runAll() {
        _state.value.descriptors.forEach { run(it.id) }
    }
}

internal fun defaultServiceFor(t: TransportKind): FeatureCatalogService = when (t) {
    TransportKind.Ffi -> FfiFeatureService()
    TransportKind.Http -> HttpFeatureService()
    TransportKind.Sqlite -> SqliteFeatureService()
}
```

- [ ] **Step 2: Verify it compiles** — sync; Run (Mac): `$PRELUDE; $GR --no-daemon :app:assembleDebug`. Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/kotlin/io/dotnetnativeinterop/feature/FeaturesViewModel.kt
git commit -m "feat(android): FeaturesViewModel (shared Dashboard/Features state)"
```

---

## Task 5: ComparisonViewModel

**Files:**
- Create: `feature/ComparisonViewModel.kt`

Runs the whole catalog over **all three** transports, recording client-side wall-clock per transport. Uses `System.nanoTime()` for timing (the engine's own elapsedMs is also available per result, but Compare shows transport round-trip cost, so measure on the client like iOS).

- [ ] **Step 1: Implement** (`feature/ComparisonViewModel.kt`)

```kotlin
package io.dotnetnativeinterop.feature

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.dotnetnativeinterop.model.TransportKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

public data class TransportTiming(
    val transport: TransportKind,
    val featureCount: Int,
    val passed: Int,
    val totalMs: Double,
)

public data class ComparisonUiState(
    val running: Boolean = false,
    val timings: List<TransportTiming> = emptyList(),
    val error: String? = null,
)

public class ComparisonViewModel(
    private val serviceFor: (TransportKind) -> FeatureCatalogService = ::defaultServiceFor,
) : ViewModel() {

    private val _state = MutableStateFlow(ComparisonUiState())
    public val state: StateFlow<ComparisonUiState> = _state.asStateFlow()

    public fun runAll() {
        viewModelScope.launch {
            _state.update { it.copy(running = true, timings = emptyList(), error = null) }
            val out = mutableListOf<TransportTiming>()
            runCatching {
                for (t in TransportKind.entries) {
                    val svc = serviceFor(t)
                    val ids = svc.descriptors().map { it.id }
                    var passed = 0
                    val start = System.nanoTime()
                    for (id in ids) { if (svc.run(id).ok) passed++ }
                    val ms = (System.nanoTime() - start) / 1_000_000.0
                    out.add(TransportTiming(t, ids.size, passed, ms))
                }
            }.onSuccess { _state.update { it.copy(running = false, timings = out) } }
             .onFailure { e -> _state.update { it.copy(running = false, timings = out, error = e.message) } }
        }
    }
}
```

- [ ] **Step 2: Verify it compiles** — sync; Run (Mac): `$PRELUDE; $GR --no-daemon :app:assembleDebug`. Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/kotlin/io/dotnetnativeinterop/feature/ComparisonViewModel.kt
git commit -m "feat(android): ComparisonViewModel (per-transport timing)"
```

---

## Task 6: LatencyViewModel

**Files:**
- Create: `feature/LatencyViewModel.kt`

Exposes a live `EngineStats` snapshot (`nativeEngineStats`) and a simple latency sample: run one feature N times over FFI, collecting per-run client ms for a distribution/jitter view.

- [ ] **Step 1: Implement** (`feature/LatencyViewModel.kt`)

```kotlin
package io.dotnetnativeinterop.feature

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.dotnetnativeinterop.model.EngineStats
import io.dotnetnativeinterop.transport.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

public data class LatencyUiState(
    val stats: EngineStats? = null,
    val samplesMs: List<Double> = emptyList(),
    val sampling: Boolean = false,
    val error: String? = null,
)

public class LatencyViewModel(
    private val service: FeatureCatalogService = FfiFeatureService(),
) : ViewModel() {

    private val _state = MutableStateFlow(LatencyUiState())
    public val state: StateFlow<LatencyUiState> = _state.asStateFlow()

    public fun refreshStats() {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val raw = NativeBridge.nativeEngineStats() ?: error("nativeEngineStats null")
                    catalogJson.decodeFromString<EngineStats>(raw)
                }
            }.onSuccess { s -> _state.update { it.copy(stats = s) } }
             .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    public fun sample(count: Int = 30) {
        viewModelScope.launch {
            _state.update { it.copy(sampling = true, samplesMs = emptyList(), error = null) }
            val ids = runCatching { service.descriptors().firstOrNull()?.id }.getOrNull()
            if (ids == null) { _state.update { it.copy(sampling = false, error = "no features") }; return@launch }
            val out = mutableListOf<Double>()
            runCatching {
                repeat(count) {
                    val start = System.nanoTime()
                    service.run(ids)
                    out.add((System.nanoTime() - start) / 1_000_000.0)
                }
            }.also { _state.update { it.copy(sampling = false, samplesMs = out, error = it.value.error) } }
        }
    }
}
```

- [ ] **Step 2: Verify it compiles** — sync; Run (Mac): `$PRELUDE; $GR --no-daemon :app:assembleDebug`. Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/kotlin/io/dotnetnativeinterop/feature/LatencyViewModel.kt
git commit -m "feat(android): LatencyViewModel (telemetry + run-time samples)"
```

---

## Task 7: TransportPicker + GatedTabScreen

**Files:**
- Create: `ui/components/TransportPicker.kt`, `ui/tabs/GatedTabScreen.kt`

- [ ] **Step 1: Implement the transport picker** (`ui/components/TransportPicker.kt`)

```kotlin
package io.dotnetnativeinterop.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.dotnetnativeinterop.model.TransportKind

@Composable
internal fun TransportPicker(
    selected: TransportKind,
    onSelect: (TransportKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        val all = TransportKind.entries
        all.forEachIndexed { i, t ->
            SegmentedButton(
                selected = t == selected,
                onClick = { onSelect(t) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = all.size),
            ) { Text(t.displayName) }
        }
    }
}
```

- [ ] **Step 2: Implement the gated placeholder** (`ui/tabs/GatedTabScreen.kt`)

```kotlin
package io.dotnetnativeinterop.ui.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Honest placeholder for tabs whose native dependency (gate) is not yet built on Android. */
@Composable
internal fun GatedTabScreen(
    title: String,
    summary: String,
    blockingGate: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(summary, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Text(
            "Blocked on: $blockingGate",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
    }
}
```

- [ ] **Step 3: Verify it compiles** — sync; Run (Mac): `$PRELUDE; $GR --no-daemon :app:assembleDebug`. Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/kotlin/io/dotnetnativeinterop/ui/components/TransportPicker.kt \
        android/app/src/main/kotlin/io/dotnetnativeinterop/ui/tabs/GatedTabScreen.kt
git commit -m "feat(android): TransportPicker + GatedTabScreen placeholder"
```

---

## Task 8: Functional screens — Dashboard, Features, FeatureDetail

**Files:**
- Create: `ui/tabs/DashboardScreen.kt`, `ui/tabs/FeaturesScreen.kt`, `ui/tabs/FeatureDetailScreen.kt`

Each screen takes a `FeaturesViewModel` and collects `state` with `collectAsStateWithLifecycle()`. Build spec (Material3, `internal @Composable`):

**DashboardScreen(vm, modifier):**
- `TransportPicker(state.transport, vm::selectTransport)` at top.
- A "Run all" `Button { vm.runAll() }`.
- A stats `Card`: total = `state.descriptors.size`; ran = `state.results.size`; passing = `state.results.values.count { it.ok }`; total elapsed = `state.results.values.sumOf { it.elapsedMs }` formatted `%.1f ms`.
- If `state.loading` show a `CircularProgressIndicator`; if `state.error != null` show an error `Text` in `colorScheme.error`.

**FeaturesScreen(vm, onOpenDetail: (String) -> Unit, modifier):**
- `TransportPicker` + a "failed only" `FilterChip` toggling a local `remember { mutableStateOf(false) }`.
- `LazyColumn` of descriptors, grouped by `version` (use `descriptors.groupBy { it.version }`, a `stickyHeader` per version). Each row: title + a status icon from `state.status[id]` (Idle→outline, Running→spinner, Ok→check/primary, Failed→error). Row `onClick = { onOpenDetail(id) }`. When "failed only" is on, keep ids whose status == `RunStatus.Failed`.

**FeatureDetailScreen(vm, id, onBack, modifier):**
- Look up `descriptor = state.descriptors.first { it.id == id }` and `result = state.results[id]`.
- Show: title, version chip; a "Code" `Card` (monospace `Text`, `fontFamily = FontFamily.Monospace`); an "Expected" `Card`; a "Run" `Button { vm.run(id) }`; when `result != null` a "Result" card with `result.result`, `ok` badge, and `"%.3f ms".format(result.elapsedMs)`.
- A back affordance calling `onBack()`.

- [ ] **Step 1: Implement the three screens** per the spec above (Material3 components; `collectAsStateWithLifecycle` from `androidx.lifecycle.compose`). Keep each file focused; no business logic in composables beyond reading state and calling `vm` methods.

- [ ] **Step 2: Verify it compiles** — sync; Run (Mac): `$PRELUDE; $GR --no-daemon :app:assembleDebug`. Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/kotlin/io/dotnetnativeinterop/ui/tabs/DashboardScreen.kt \
        android/app/src/main/kotlin/io/dotnetnativeinterop/ui/tabs/FeaturesScreen.kt \
        android/app/src/main/kotlin/io/dotnetnativeinterop/ui/tabs/FeatureDetailScreen.kt
git commit -m "feat(android): Dashboard, Features, FeatureDetail screens"
```

---

## Task 9: Functional screens — Compare, Latency, About

**Files:**
- Create: `ui/tabs/CompareScreen.kt`, `ui/tabs/LatencyScreen.kt`, `ui/tabs/AboutScreen.kt`

**CompareScreen(vm: ComparisonViewModel, modifier):**
- "Run comparison" `Button { vm.runAll() }`; `CircularProgressIndicator` when `state.running`.
- For each `TransportTiming` in `state.timings`: a row with `transport.displayName`, a horizontal bar whose width ∝ `totalMs` relative to `state.timings.maxOf { it.totalMs }` (a `Box` with `fillMaxWidth(fraction)` + `background`), and a label `"%.1f ms · %d/%d".format(totalMs, passed, featureCount)`.
- Error `Text` if `state.error != null`.

**LatencyScreen(vm: LatencyViewModel, modifier):**
- On first composition `LaunchedEffect(Unit) { vm.refreshStats() }`.
- A telemetry `Card` from `state.stats` (heap MB = `heapBytes/1048576`, GC gens, threads, processors, uptime s). A "Refresh" button → `vm.refreshStats()`.
- A "Sample latency" button → `vm.sample()`; when `state.samplesMs` non-empty show min/median/max + a simple sparkline (`Canvas` plotting the samples) — keep it minimal.

**AboutScreen(modifier):**
- Reuse the existing `ui/PatternModel.kt` loader (already parses `assets` `patterns.json`) to list the three transports' info (name, mechanism, summary, limitations) in `Card`s.
- A short "why NativeAOT / one engine, three transports" blurb (static `Text`).
- A live engine-stats line via a small `LaunchedEffect` calling `NativeBridge.nativeEngineStats()` (parse with `catalogJson`), or reuse `LatencyViewModel` — pick one; do not duplicate parsing logic.

- [ ] **Step 1: Implement the three screens** per spec. For About, read `ui/PatternModel.kt` first and reuse its existing patterns.json loading rather than re-implementing it.

- [ ] **Step 2: Verify it compiles** — sync; Run (Mac): `$PRELUDE; $GR --no-daemon :app:assembleDebug`. Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/kotlin/io/dotnetnativeinterop/ui/tabs/CompareScreen.kt \
        android/app/src/main/kotlin/io/dotnetnativeinterop/ui/tabs/LatencyScreen.kt \
        android/app/src/main/kotlin/io/dotnetnativeinterop/ui/tabs/AboutScreen.kt
git commit -m "feat(android): Compare, Latency, About screens"
```

---

## Task 10: AppShell (drawer + tab state) and MainActivity swap

**Files:**
- Create: `ui/AppShell.kt`
- Modify: `MainActivity.kt`

**AppShell** owns: a `Tab` enum (Dashboard, Features, Compare, Latency, About, Ai, Manuals, Lab, Stream); a `selectedTab` + optional `detailFeatureId` state; the three catalog ViewModels (via `viewModel()` from `androidx.lifecycle.viewmodel.compose`); a `ModalNavigationDrawer` whose drawer lists all tabs; a `Scaffold` with a `TopAppBar` (hamburger opens the drawer, title = current tab) and a content area that switches on `selectedTab`. The existing `InferenceScreen` (Stream) and `InferenceViewModel` are reused unchanged.

- [ ] **Step 1: Implement** (`ui/AppShell.kt`)

```kotlin
package io.dotnetnativeinterop.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import io.dotnetnativeinterop.feature.ComparisonViewModel
import io.dotnetnativeinterop.feature.FeaturesViewModel
import io.dotnetnativeinterop.feature.LatencyViewModel
import io.dotnetnativeinterop.ui.tabs.AboutScreen
import io.dotnetnativeinterop.ui.tabs.CompareScreen
import io.dotnetnativeinterop.ui.tabs.DashboardScreen
import io.dotnetnativeinterop.ui.tabs.FeatureDetailScreen
import io.dotnetnativeinterop.ui.tabs.FeaturesScreen
import io.dotnetnativeinterop.ui.tabs.GatedTabScreen
import io.dotnetnativeinterop.ui.tabs.LatencyScreen
import kotlinx.coroutines.launch

internal enum class Tab(val title: String) {
    Dashboard("Dashboard"), Features("Features"), Compare("Compare"), Latency("Latency"),
    About("About"), Ai("AI"), Manuals("Manuals"), Lab("Lab"), Stream("Stream"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppShell(
    inference: io.dotnetnativeinterop.ui.InferenceViewModel,
    modifier: Modifier = Modifier,
) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(Tab.Dashboard) }
    var detailId by remember { mutableStateOf<String?>(null) }

    val features: FeaturesViewModel = viewModel()
    val comparison: ComparisonViewModel = viewModel()
    val latency: LatencyViewModel = viewModel()

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Tab.entries.forEach { t ->
                    NavigationDrawerItem(
                        label = { Text(t.title) },
                        selected = t == tab,
                        onClick = { tab = t; detailId = null; scope.launch { drawer.close() } },
                    )
                }
            }
        },
    ) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text(if (tab == Tab.Features && detailId != null) "Feature" else tab.title) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Menu")
                        }
                    },
                )
            },
        ) { pad ->
            val content = Modifier.padding(pad).fillMaxSize()
            when (tab) {
                Tab.Dashboard -> DashboardScreen(features, content)
                Tab.Features -> {
                    val id = detailId
                    if (id == null) FeaturesScreen(features, onOpenDetail = { detailId = it }, modifier = content)
                    else FeatureDetailScreen(features, id, onBack = { detailId = null }, modifier = content)
                }
                Tab.Compare -> CompareScreen(comparison, content)
                Tab.Latency -> LatencyScreen(latency, content)
                Tab.About -> AboutScreen(content)
                Tab.Ai -> GatedTabScreen("AI", "On-device semantic search + Ask-the-Manuals (RAG). Apple Chat is iOS-only.", "ONNX-on-Android native gate", content)
                Tab.Manuals -> GatedTabScreen("Manuals", "Offline Edge Vector Search over the maintenance corpus.", "ONNX-on-Android native gate", content)
                Tab.Lab -> GatedTabScreen("Lab", "Fractal / raymarcher / SIMD compute benchmarks in the .NET engine.", "Lab compute C-ABI exports + JNI", content)
                Tab.Stream -> InferenceScreen(viewModel = inference, modifier = content)
            }
        }
    }
}
```

- [ ] **Step 2: Modify `MainActivity.kt`** — swap `InferenceScreen` for `AppShell`, passing the existing `InferenceViewModel`:

```kotlin
package io.dotnetnativeinterop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.dotnetnativeinterop.ui.AppShell
import io.dotnetnativeinterop.ui.DotnetNativeInteropTheme
import io.dotnetnativeinterop.ui.InferenceViewModel

public class MainActivity : ComponentActivity() {

    private val inference: InferenceViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DotnetNativeInteropTheme {
                AppShell(inference = inference)
            }
        }
    }
}
```

- [ ] **Step 3: Verify it compiles + installs** — sync `AppShell.kt` + `MainActivity.kt`; Run (Mac): `$PRELUDE; $GR --no-daemon :app:installDebug` (emulator up). Expected: `BUILD SUCCESSFUL`, APK installed.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/kotlin/io/dotnetnativeinterop/ui/AppShell.kt \
        android/app/src/main/kotlin/io/dotnetnativeinterop/MainActivity.kt
git commit -m "feat(android): AppShell drawer + tabs; MainActivity -> AppShell"
```

---

## Task 11: Instrumented emulator smoke test (the proof)

**Files:**
- Create: `android/app/src/androidTest/kotlin/io/dotnetnativeinterop/Sp1ShellTest.kt`

Proves the catalog loads and a feature runs over **each** transport against the real `libdni.so`, and that `AppShell` composes without crashing. Reuses the gate test's library-load pattern.

- [ ] **Step 1: Write the test** (`Sp1ShellTest.kt`)

```kotlin
package io.dotnetnativeinterop

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.dotnetnativeinterop.feature.FfiFeatureService
import io.dotnetnativeinterop.feature.HttpFeatureService
import io.dotnetnativeinterop.feature.SqliteFeatureService
import io.dotnetnativeinterop.transport.NativeBridge
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Sp1ShellTest {
    @get:Rule val compose = createComposeRule()

    companion object {
        @JvmStatic @BeforeClass fun load() {
            System.loadLibrary("dni"); System.loadLibrary("dni_jni")
            check(NativeBridge.nativeInitialize() == 0) { "init failed" }
        }
    }

    @Test fun catalogLoadsAndRunsOverEachTransport() = runBlocking {
        for (svc in listOf(FfiFeatureService(), SqliteFeatureService(), HttpFeatureService())) {
            val d = svc.descriptors()
            assertTrue("${svc::class.simpleName} catalog non-empty", d.isNotEmpty())
            val r = svc.run(d.first().id)
            assertTrue("${svc::class.simpleName} run ok", r.ok)
            android.util.Log.i("Sp1ShellTest", "PASS: ${svc::class.simpleName} -> ${d.size} features, ran ${r.id}")
        }
    }

    @Test fun shellRendersAllTabs() {
        compose.setContent {
            io.dotnetnativeinterop.ui.DotnetNativeInteropTheme {
                io.dotnetnativeinterop.ui.AppShell(inference = io.dotnetnativeinterop.ui.InferenceViewModel())
            }
        }
        compose.onNodeWithText("Dashboard").assertExists()
    }
}
```

> If `InferenceViewModel()` has constructor dependencies, read `ui/InferenceViewModel.kt` and construct it accordingly (or render only the drawer-less content for this assertion). Adjust the `shellRendersAllTabs` body to whatever makes `AppShell` constructible in a test.

- [ ] **Step 2: Run on the emulator** — sync the test; ensure the emulator is up; Run (Mac):
```
$PRELUDE; $GR --no-daemon :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.dotnetnativeinterop.Sp1ShellTest
```
Expected: `BUILD SUCCESSFUL`, 2 tests passed; logcat `PASS:` lines for FFI/SQLCipher/HTTP. Pull gate logcat with `adb logcat -d -s Sp1ShellTest:I` if needed.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/androidTest/kotlin/io/dotnetnativeinterop/Sp1ShellTest.kt
git commit -m "test(android): SP1 shell + catalog-over-all-transports smoke test"
```

---

## Task 12: Manual verification + PR

- [ ] **Step 1: Manual smoke on the emulator** — launch the app; open the drawer; visit each tab. Confirm: Dashboard run-all updates stats; Features lists grouped features and a row opens detail + runs; transport picker switches (FFI/HTTP/SQLCipher) and reloads; Compare shows three bars; Latency shows telemetry; About shows transport cards; AI/Manuals/Lab show their gate placeholders; Stream still streams tokens. Note anything broken and fix before the PR.

- [ ] **Step 2: Open the PR**

```bash
git push -u origin feat/android-parity-sp1-ui-shell
gh pr create --base main --title "Android SP1: UI parity shell (core trio functional, AI/Manuals/Lab gated)" --body "<summary + screenshots; note AI/Manuals/Lab are placeholders pending the ONNX-on-Android + Lab-exports gates>"
```

- [ ] **Step 3: Watch CI** — `ci-android` (build + assembleDebug) must stay green; the instrumented test runs locally on the emulator (CI is build-only, per the SP0 findings doc).

---

## Self-Review (completed against the spec)

- **Spec coverage:** shell/drawer (Task 10), 5 functional tabs (Tasks 8–9), FeatureCatalogService + 3 impls (Tasks 2–3), 3 ViewModels (Tasks 4–6), gated placeholders (Tasks 7, 10), models (Task 1), TransportPicker (Task 7), MainActivity swap (Task 10), instrumented proof (Task 11), additive (existing files untouched — verified by the file list). ✔ all covered.
- **Type consistency:** `FeatureCatalogService.descriptors()/run(id)` used identically across all impls and ViewModels; `defaultServiceFor` shared by Features/Comparison VMs; `catalogJson` defined once (FfiFeatureService.kt) and reused; `TransportKind.entries`/`displayName` consistent; `Tab` enum drives both drawer and content switch. ✔
- **Placeholder scan:** screen-heavy Tasks 8–9 give build specs (state bindings + component list) rather than every modifier — deliberate for verbose Compose; the contract layers (models/services/VMs/shell/test) are fully coded. The one explicit "adjust to constructor" note (Task 11) is conditional on reading an existing file, not a TODO in shipped code.
