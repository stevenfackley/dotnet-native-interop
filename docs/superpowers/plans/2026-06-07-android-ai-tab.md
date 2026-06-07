# Android AI Tab Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the AI tab's gated placeholder with a real Semantic Search screen and an Ask-the-Manuals RAG screen (FFI), built on the merged ONNX gate.

**Architecture:** MVVM mirroring SP1. `AiScreen` hosts a segmented toggle over two sub-screens. `SearchService` wraps `nativeSearch`; `FfiRagService` bridges `nativeRagSessionStart` + `FfiTokenListener` into a `Flow<String>` (same channel pattern as the existing `FfiClient`, which stays untouched). Generation is the engine's extractive fallback (no GGUF).

**Tech Stack:** Kotlin/Jetpack Compose (Material3), lifecycle-viewmodel-compose, coroutines, kotlinx.serialization; arm64 emulator on the Mac mini. **No engine/.so rebuild** — pure Kotlin on existing exports.

**Spec:** `docs/superpowers/specs/2026-06-07-android-ai-tab-design.md`

---

## Conventions (read once)

**No native rebuild this cycle.** `libdni.so` already exports `nativeSearch`/`nativeRagSessionStart`/`dni_set_assets_dir`; the model is bundled+extracted by the merged ONNX gate. You only touch Kotlin.

**Mac overlay + build.** Edit on Windows, sync, build/test on the Mac:
```bash
# Sync (from repo root): tar -cf - <paths...> | ssh steve-mac-mini "cd /Users/steve/dni-rag-build && tar -xf -"
PRELUDE='export ANDROID_HOME=$HOME/Library/Android/sdk JAVA_HOME=$HOME/Library/Java/jdk; export PATH=$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH; GR=$HOME/toolchain/gradle-8.9/bin/gradle'
# Compile:           $PRELUDE; cd /Users/steve/dni-rag-build/android && $GR --no-daemon :app:assembleDebug
# Instrumented test: $PRELUDE; cd .../android && $GR --no-daemon :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.dotnetnativeinterop.AiTabTest
```
zsh gotcha: never start an `echo` arg with `=`. `-Xexplicit-api=strict`. Conventional commits, no AI attribution, branch `feat/android-ai-tab` only.

---

## File structure (all new unless noted)

```
android/app/src/main/kotlin/io/dotnetnativeinterop/
  model/AiModels.kt                 # SearchResult
  ai/SearchService.kt               # nativeSearch -> List<SearchResult>  (+ shared aiJson)
  ai/AiSearchViewModel.kt
  ai/RagService.kt                  # interface: answer(query): Flow<String>
  ai/FfiRagService.kt               # nativeRagSessionStart + FfiTokenListener -> Flow<String>
  ai/RagViewModel.kt
  ui/tabs/AiScreen.kt               # segmented toggle host
  ui/tabs/AiSearchScreen.kt
  ui/tabs/AskManualsScreen.kt
  ui/AppShell.kt                    # MODIFY: Tab.Ai -> AiScreen
android/app/src/androidTest/kotlin/io/dotnetnativeinterop/AiTabTest.kt   # NEW
```
Untouched: `transport/**` (incl. `FfiClient.kt`), `ui/InferenceScreen.kt`, the `feature/` catalog stack.

---

## Task 1: Search model + service + view model

**Files:** Create `model/AiModels.kt`, `ai/SearchService.kt`, `ai/AiSearchViewModel.kt`

- [ ] **Step 1: `model/AiModels.kt`**
```kotlin
package io.dotnetnativeinterop.model

import kotlinx.serialization.Serializable

/** One semantic-search hit (dni_search returns JSON [{text,score}]). */
@Serializable
public data class SearchResult(val text: String, val score: Double)
```

- [ ] **Step 2: `ai/SearchService.kt`**
```kotlin
package io.dotnetnativeinterop.ai

import io.dotnetnativeinterop.model.SearchResult
import io.dotnetnativeinterop.transport.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Shared lenient JSON for the AI payloads. */
internal val aiJson: Json = Json { ignoreUnknownKeys = true }

/** Semantic search over a named engine corpus ("features"|"facts"|"manuals") via dni_search (FFI). */
public class SearchService {
    public suspend fun search(query: String, corpus: String): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val raw = NativeBridge.nativeSearch(query, corpus) ?: error("nativeSearch returned null")
            if (raw.isBlank()) emptyList() else aiJson.decodeFromString(raw)
        }
}
```

- [ ] **Step 3: `ai/AiSearchViewModel.kt`**
```kotlin
package io.dotnetnativeinterop.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.dotnetnativeinterop.model.SearchResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

public data class AiSearchUiState(
    val corpus: String = "facts",
    val query: String = "",
    val results: List<SearchResult> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

public class AiSearchViewModel(
    private val service: SearchService = SearchService(),
) : ViewModel() {

    private val _state = MutableStateFlow(AiSearchUiState())
    public val state: StateFlow<AiSearchUiState> = _state.asStateFlow()

    public val corpora: List<String> = listOf("features", "facts", "manuals")

    public fun selectCorpus(c: String): Unit = _state.update { it.copy(corpus = c) }
    public fun setQuery(q: String): Unit = _state.update { it.copy(query = q) }

    public fun search() {
        val s = _state.value
        if (s.query.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { service.search(s.query, s.corpus) }
                .onSuccess { r -> _state.update { it.copy(results = r, loading = false) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }
}
```

- [ ] **Step 4: Verify** — sync the 3 files; `$PRELUDE; cd .../android && $GR --no-daemon :app:assembleDebug` → `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**
```bash
git add android/app/src/main/kotlin/io/dotnetnativeinterop/model/AiModels.kt android/app/src/main/kotlin/io/dotnetnativeinterop/ai/SearchService.kt android/app/src/main/kotlin/io/dotnetnativeinterop/ai/AiSearchViewModel.kt
git commit -m "feat(android): AI semantic-search model + service + view model"
```

---

## Task 2: RAG service (FFI streaming) + view model

**Files:** Create `ai/RagService.kt`, `ai/FfiRagService.kt`, `ai/RagViewModel.kt`

Context: `FfiRagService` mirrors `transport/FfiClient.kt` exactly — a 64-slot `Channel`, `trySend` from the `FfiTokenListener.onToken` (.NET thread), close on `isFinal`, emit on `Dispatchers.Default`, and cancel+free the session in `finally`. The only differences: it calls `nativeRagSessionStart` (not `nativeSessionStart`) and emits the raw `text` `String` (not a `Token`). Do NOT modify `FfiClient.kt`.

- [ ] **Step 1: `ai/RagService.kt`**
```kotlin
package io.dotnetnativeinterop.ai

import kotlinx.coroutines.flow.Flow

/** Streams a grounded answer fragment-by-fragment; each emission is appended to the running answer. */
public interface RagService {
    public fun answer(query: String): Flow<String>
}
```

- [ ] **Step 2: `ai/FfiRagService.kt`**
```kotlin
package io.dotnetnativeinterop.ai

import io.dotnetnativeinterop.transport.FfiTokenListener
import io.dotnetnativeinterop.transport.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * RAG answer stream over the in-process FFI (dni_rag_session_start + the FfiTokenListener callback).
 * Same channel-bridge + backpressure + cleanup pattern as transport/FfiClient, on the RAG entrypoint.
 */
public class FfiRagService : RagService {
    override fun answer(query: String): Flow<String> = flow {
        val channel = Channel<String>(capacity = 64)

        val listener = object : FfiTokenListener {
            override fun onToken(index: Int, text: String, isFinal: Boolean) {
                if (text.isNotEmpty()) {
                    val result = channel.trySend(text)
                    if (result.isFailure && !isFinal) {
                        channel.close(IllegalStateException("RAG token buffer overflow"))
                    }
                }
                if (isFinal) {
                    channel.close()
                }
            }
        }

        val sessionId = withContext(Dispatchers.Default) {
            NativeBridge.nativeRagSessionStart(query, 256, 0.8f, listener)
        }
        if (sessionId <= 0) {
            throw IllegalStateException("nativeRagSessionStart failed: status $sessionId")
        }

        try {
            for (fragment in channel) {
                emit(fragment)
            }
        } finally {
            withContext(Dispatchers.Default) {
                NativeBridge.nativeSessionCancel(sessionId)
                NativeBridge.nativeSessionFree(sessionId)
            }
        }
    }.flowOn(Dispatchers.Default)
}
```

- [ ] **Step 3: `ai/RagViewModel.kt`**
```kotlin
package io.dotnetnativeinterop.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.dotnetnativeinterop.model.SearchResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

public data class RagUiState(
    val query: String = "",
    val sources: List<SearchResult> = emptyList(),
    val answer: String = "",
    val firstTokenMs: Long? = null,
    val totalMs: Long? = null,
    val streaming: Boolean = false,
    val error: String? = null,
)

public class RagViewModel(
    private val search: SearchService = SearchService(),
    private val rag: RagService = FfiRagService(),
) : ViewModel() {

    private val _state = MutableStateFlow(RagUiState())
    public val state: StateFlow<RagUiState> = _state.asStateFlow()

    public fun setQuery(q: String): Unit = _state.update { it.copy(query = q) }

    public fun ask() {
        val q = _state.value.query
        if (q.isBlank()) return
        viewModelScope.launch {
            _state.update {
                it.copy(streaming = true, answer = "", sources = emptyList(),
                    firstTokenMs = null, totalMs = null, error = null)
            }

            // 1. Retrieval (sources) — the "manuals" corpus.
            runCatching { search.search(q, "manuals") }
                .onSuccess { s -> _state.update { it.copy(sources = s) } }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }

            // 2. Streaming grounded answer.
            val start = System.nanoTime()
            runCatching {
                rag.answer(q).collect { fragment ->
                    _state.update {
                        val first = it.firstTokenMs ?: ((System.nanoTime() - start) / 1_000_000)
                        it.copy(answer = it.answer + fragment, firstTokenMs = first)
                    }
                }
            }.also { res ->
                _state.update {
                    it.copy(
                        streaming = false,
                        totalMs = (System.nanoTime() - start) / 1_000_000,
                        error = res.exceptionOrNull()?.message ?: it.error,
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 4: Verify** — sync the 3 files; `assembleDebug` → `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**
```bash
git add android/app/src/main/kotlin/io/dotnetnativeinterop/ai/RagService.kt android/app/src/main/kotlin/io/dotnetnativeinterop/ai/FfiRagService.kt android/app/src/main/kotlin/io/dotnetnativeinterop/ai/RagViewModel.kt
git commit -m "feat(android): RAG service (FFI streaming) + RagViewModel"
```

---

## Task 3: AI screens

**Files:** Create `ui/tabs/AiSearchScreen.kt`, `ui/tabs/AskManualsScreen.kt`, `ui/tabs/AiScreen.kt`

All `internal @Composable`, read state via `val s by vm.state.collectAsStateWithLifecycle()`. Reuse Material3 (`SingleChoiceSegmentedButtonRow`/`SegmentedButton`, `OutlinedTextField`, `Card`, `LinearProgressIndicator`, `CircularProgressIndicator`, `LazyColumn`).

**AiSearchScreen(vm: AiSearchViewModel, modifier):**
- A corpus `SingleChoiceSegmentedButtonRow` over `vm.corpora` (selected = `s.corpus`, onClick `vm.selectCorpus(c)`).
- An `OutlinedTextField` bound to `s.query` (`onValueChange = vm::setQuery`) + a "Search" `Button { vm.search() }`.
- `if (s.loading) CircularProgressIndicator()`. `if (s.error != null)` error `Text`.
- A `LazyColumn` of `s.results`: each row a `Card` with the result `text`, a `LinearProgressIndicator(progress = { s.score.coerceIn(0.0,1.0).toFloat() })`, and a `"similarity %.3f".format(score)` label.

**AskManualsScreen(vm: RagViewModel, modifier):**
- An `OutlinedTextField` bound to `s.query` (`vm::setQuery`) + an "Ask" `Button { vm.ask() }`.
- A **Sources** `Card`: header "Sources" + each `s.sources` entry (text + `"%.3f".format(score)`). (Hide the card when `s.sources` is empty.)
- An **Answer** `Card`: header "Answer" + a small caption "grounded extraction (on-device generation is a future option)" + `Text(s.answer)`. Show a `CircularProgressIndicator` while `s.streaming`.
- A metrics `Row`: `s.firstTokenMs?.let { "first token ${it} ms" }` and `s.totalMs?.let { "total ${it} ms" }`.
- `if (s.error != null)` error `Text`.

**AiScreen(modifier):**
```kotlin
package io.dotnetnativeinterop.ui.tabs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.dotnetnativeinterop.ai.AiSearchViewModel
import io.dotnetnativeinterop.ai.RagViewModel

@Composable
internal fun AiScreen(modifier: Modifier = Modifier) {
    var tab by remember { mutableIntStateOf(0) }
    val labels = listOf("Semantic Search", "Ask the Manuals")
    Column(modifier = modifier) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            labels.forEachIndexed { i, label ->
                SegmentedButton(
                    selected = tab == i,
                    onClick = { tab = i },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = labels.size),
                ) { Text(label) }
            }
        }
        if (tab == 0) {
            val vm: AiSearchViewModel = viewModel()
            AiSearchScreen(vm, Modifier.fillMaxWidth())
        } else {
            val vm: RagViewModel = viewModel()
            AskManualsScreen(vm, Modifier.fillMaxWidth())
        }
    }
}
```

- [ ] **Step 1:** Implement the three screens per the specs above (AiScreen verbatim).
- [ ] **Step 2: Verify** — sync; `assembleDebug` → `BUILD SUCCESSFUL` (add `@OptIn(ExperimentalMaterial3Api::class)` if the compiler asks).
- [ ] **Step 3: Commit**
```bash
git add android/app/src/main/kotlin/io/dotnetnativeinterop/ui/tabs/AiSearchScreen.kt android/app/src/main/kotlin/io/dotnetnativeinterop/ui/tabs/AskManualsScreen.kt android/app/src/main/kotlin/io/dotnetnativeinterop/ui/tabs/AiScreen.kt
git commit -m "feat(android): AI Semantic Search + Ask-the-Manuals screens"
```

---

## Task 4: Wire the AI tab into the shell

**Files:** Modify `android/app/src/main/kotlin/io/dotnetnativeinterop/ui/AppShell.kt`

- [ ] **Step 1:** Add `import io.dotnetnativeinterop.ui.tabs.AiScreen` (with the other `ui.tabs` imports). Replace the line:
```kotlin
                Tab.Ai -> GatedTabScreen("AI", "On-device semantic search + Ask-the-Manuals (RAG). Apple Chat is iOS-only.", "ONNX-on-Android native gate", content)
```
with:
```kotlin
                Tab.Ai -> AiScreen(content)
```
(Leave `Tab.Manuals` and `Tab.Lab` on `GatedTabScreen`.)

- [ ] **Step 2: Verify** — sync; `assembleDebug` → `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**
```bash
git add android/app/src/main/kotlin/io/dotnetnativeinterop/ui/AppShell.kt
git commit -m "feat(android): route the AI tab to the real AiScreen"
```

---

## Task 5: Instrumented proof — AiTabTest

**Files:** Create `android/app/src/androidTest/kotlin/io/dotnetnativeinterop/AiTabTest.kt`

Proves on the emulator: semantic search returns ranked results, and a RAG answer streams ≥1 non-empty fragment (retrieval + extractive generation). Assets are extracted + dir set in `@BeforeClass` (as the app does).

- [ ] **Step 1: Write the test**
```kotlin
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
```

- [ ] **Step 2: Run on the emulator** — sync the test; ensure the emulator is up (start `dni_arm64` headless if `adb devices` shows none), then:
```
$PRELUDE; cd /Users/steve/dni-rag-build/android && adb logcat -c; $GR --no-daemon :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.dotnetnativeinterop.AiTabTest 2>&1 | tail -25; adb logcat -d -s AiTab:I | tail -6
```
Expected: `BUILD SUCCESSFUL`, 2 tests passed; logcat `PASS: search -> N results` and `PASS: rag -> M fragments`.

- [ ] **Step 3: Commit**
```bash
git add android/app/src/androidTest/kotlin/io/dotnetnativeinterop/AiTabTest.kt
git commit -m "test(android): AI tab — semantic search ranked + RAG answer streams on device"
```

---

## Task 6: Manual smoke + PR

- [ ] **Step 1: Manual** — install + launch; on the AI tab, toggle to Semantic Search (pick a corpus, run a query → ranked results), then Ask the Manuals (a query → sources + a streamed answer + metrics). Capture a screenshot.
- [ ] **Step 2: PR**
```bash
git push -u origin feat/android-ai-tab
gh pr create --base main --title "Android AI tab: Semantic Search + Ask-the-Manuals (FFI, extractive)" --body "<summary + screenshot; note extractive generation (no GGUF), FFI-only, Apple Chat dropped; built on the ONNX gate>"
```
- [ ] **Step 3: Watch `ci-android`** (build-only). The instrumented AI test runs on the local emulator.

---

## Self-Review (against the spec)

- **Spec coverage:** in-tab toggle (Task 3 AiScreen); Semantic Search model/service/VM/screen (Tasks 1, 3); Ask-the-Manuals RagService/FfiRagService/RagViewModel/screen with sources + streaming answer + TTFT/total (Tasks 2, 3); AppShell swap (Task 4); extractive generation (no GGUF — inherent, engine fallback); additive (only `Tab.Ai` route changes; `FfiClient`/`InferenceScreen`/catalog untouched — file list); proof (Task 5). ✔
- **Type consistency:** `SearchResult(text,score)` shared by `SearchService`, `AiSearchViewModel`, `RagViewModel.sources`; `RagService.answer(query): Flow<String>` implemented by `FfiRagService` and consumed by `RagViewModel`; `aiJson` declared once (SearchService) and reused; `AiScreen` constructs both VMs via `viewModel()` (all-default-param ctors → synthetic no-arg, like SP1). ✔
- **Placeholder scan:** screens are build-specs (state bindings + component lists) as in SP1; contract layers (model/services/VMs/AiScreen/test) fully coded. No TBDs.
