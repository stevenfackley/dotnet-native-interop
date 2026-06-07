# Android Manuals / EVS Tab Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Manuals gated placeholder with a faithful Kotlin Edge Vector Search: app-layer `onnxruntime-android` embeds a query and cosine-ranks the committed `edge-index.db`, with metadata facets + a 0.70 threshold, proven against the C# fixture on the emulator.

**Architecture:** Pure app-layer ONNX (no `dni_*`). A ported Kotlin `WordPieceTokenizer` (id-parity-tested vs `edge-fixtures.json`) → `EvsEncoder` (`ai.onnxruntime` over `model.onnx`) → cosine vs `EdgeIndex` (raw SQLite read of `edge-index.db`) → Material3 search UI. MVVM mirroring the AI tab.

**Tech Stack:** Kotlin/Compose (Material3), `com.microsoft.onnxruntime:onnxruntime-android:1.20.1`, raw `SQLiteDatabase`, kotlinx.serialization; arm64 emulator on the Mac mini. **No `.so` rebuild** (pure Kotlin).

**Spec:** `docs/superpowers/specs/2026-06-07-android-evs-tab-design.md`

---

## Conventions (read once)

**No native rebuild.** EVS runs ORT from Kotlin via the `onnxruntime-android` AAR — independent of the engine's `libdni.so`/P/Invoke. The model/vocab are already bundled+extracted (ONNX gate). You only touch Kotlin + gradle.

**Mac overlay + build:**
```bash
# Sync (repo root): tar -cf - <paths...> | ssh steve-mac-mini "cd /Users/steve/dni-rag-build && tar -xf -"
PRELUDE='export ANDROID_HOME=$HOME/Library/Android/sdk JAVA_HOME=$HOME/Library/Java/jdk; export PATH=$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH; GR=$HOME/toolchain/gradle-8.9/bin/gradle'
# Unit test:   $PRELUDE; cd /Users/steve/dni-rag-build/android && $GR --no-daemon :app:testDebugUnitTest --tests "<fqcn>"
# Compile:     $PRELUDE; cd .../android && $GR --no-daemon :app:assembleDebug
# Device test: $PRELUDE; cd .../android && $GR --no-daemon :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.dotnetnativeinterop.EvsTabTest
```
zsh gotcha: never start an `echo` arg with `=`. `-Xexplicit-api=strict`. Conventional commits, no AI attribution, branch `feat/android-evs-tab` only.

---

## File structure

```
android/gradle/libs.versions.toml                                   # MODIFY: onnxruntime-android dep
android/app/build.gradle.kts                                        # MODIFY: dep + packaging pickFirsts + copyAiAssets(edge-index.db)
android/app/src/main/kotlin/io/dotnetnativeinterop/
  AssetExtractor.kt                                                 # MODIFY: add edge-index.db
  model/EdgeModels.kt                                               # NEW: EdgeChunk, EdgeHit, EdgeMetadata, evsJson
  evs/WordPieceTokenizer.kt                                         # NEW: Kotlin port of the C# tokenizer
  evs/EvsEncoder.kt                                                 # NEW: ai.onnxruntime embedder
  evs/EdgeIndex.kt                                                  # NEW: SQLite reader for edge-index.db
  evs/EdgeSearchEngine.kt                                           # NEW: tokenize→embed→cosine→filter
  evs/EvsViewModel.kt                                               # NEW
  ui/tabs/EdgeSearchScreen.kt                                       # NEW
  ui/AppShell.kt                                                    # MODIFY: Tab.Manuals -> EdgeSearchScreen
android/app/src/test/kotlin/io/dotnetnativeinterop/evs/WordPieceTokenizerTest.kt   # NEW (JVM agreement)
android/app/src/androidTest/kotlin/io/dotnetnativeinterop/EvsTabTest.kt            # NEW
```
Untouched: the engine, the AI tab (`ai/`), the catalog stack (`feature/`), `transport/`.

---

## Task 1: Dependency, packaging, asset bundling

**Files:** Modify `android/gradle/libs.versions.toml`, `android/app/build.gradle.kts`, `AssetExtractor.kt`

- [ ] **Step 1: `libs.versions.toml`** — under `[versions]` add `onnxruntimeAndroid = "1.20.1"`; under `[libraries]` add:
```toml
onnxruntime-android         = { group = "com.microsoft.onnxruntime", name = "onnxruntime-android", version.ref = "onnxruntimeAndroid" }
```

- [ ] **Step 2: `build.gradle.kts` — dependency** — in the `dependencies { }` block add:
```kotlin
    // Edge Vector Search — app-layer ONNX (Kotlin), pinned to the engine's ORT version (1.20.1)
    implementation(libs.onnxruntime.android)
```

- [ ] **Step 3: `build.gradle.kts` — packaging** — inside the `android { }` block (e.g. after the `androidResources { }` block) add:
```kotlin
    packaging {
        // The engine ships libonnxruntime.so in jniLibs AND the onnxruntime-android AAR bundles the same
        // 1.20.1 binary — pick one (identical) to avoid a duplicate-.so merge error.
        jniLibs.pickFirsts += "lib/arm64-v8a/libonnxruntime.so"
    }
```

- [ ] **Step 4: `build.gradle.kts` — bundle the index** — change the `copyAiAssets` task to also stage `edge-index.db`:
```kotlin
tasks.register<Copy>("copyAiAssets") {
    from("${rootDir.parentFile.absolutePath}/core/DotnetNativeInterop.Engine/Ai/assets") {
        include("model.onnx", "vocab.txt", "corpus.txt", "manuals/**")
    }
    from("${rootDir.parentFile.absolutePath}/core/DotnetNativeInterop.EdgeIndexPublisher") {
        include("edge-index.db")
    }
    into("${projectDir}/src/main/assets/dni-assets")
}
```

- [ ] **Step 5: `AssetExtractor.kt`** — add the index to `ROOT_FILES`:
```kotlin
    private val ROOT_FILES = listOf("model.onnx", "vocab.txt", "corpus.txt", "edge-index.db")
```

- [ ] **Step 6: Verify** — sync the 3 files; Run (Mac): `$PRELUDE; cd .../android && $GR --no-daemon :app:assembleDebug 2>&1 | tail -15; ls -la app/src/main/assets/dni-assets/edge-index.db`. Expected: `BUILD SUCCESSFUL`; `edge-index.db` staged (~45K); no duplicate-`.so` error.

- [ ] **Step 7: Commit**
```bash
git add android/gradle/libs.versions.toml android/app/build.gradle.kts android/app/src/main/kotlin/io/dotnetnativeinterop/AssetExtractor.kt
git commit -m "build(android): onnxruntime-android 1.20.1 + bundle edge-index.db (EVS)"
```

---

## Task 2: Models + WordPieceTokenizer (Kotlin port) + agreement test

**Files:** Create `model/EdgeModels.kt`, `evs/WordPieceTokenizer.kt`, `src/test/.../evs/WordPieceTokenizerTest.kt`

This is the correctness anchor: the Kotlin tokenizer MUST produce the same ids as the C# one. The test loads the committed fixture and asserts equality.

- [ ] **Step 1: `model/EdgeModels.kt`**
```kotlin
package io.dotnetnativeinterop.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Shared lenient JSON for EVS payloads (index Metadata + fixtures). */
internal val evsJson: Json = Json { ignoreUnknownKeys = true }

/** The Metadata JSON column in edge-index.db: {"error_codes":[…],"tools_required":[…]}. */
@Serializable
public data class EdgeMetadata(
    @SerialName("error_codes") val errorCodes: List<String> = emptyList(),
    @SerialName("tools_required") val toolsRequired: List<String> = emptyList(),
)

/** One indexed chunk from edge-index.db (embedding is the L2-normalized 384-d vector). */
public data class EdgeChunk(
    val chunkId: String,
    val documentId: String,
    val sectionTitle: String,
    val contentText: String,
    val errorCodes: List<String>,
    val toolsRequired: List<String>,
    val embedding: FloatArray,
)

/** A ranked search hit. */
public data class EdgeHit(val chunk: EdgeChunk, val score: Float)
```

- [ ] **Step 2: `evs/WordPieceTokenizer.kt`** — faithful port of `core/DotnetNativeInterop.Engine/Ai/WordPieceTokenizer.cs` (lower-case → NFD → drop nonspacing marks → split on whitespace, peel punctuation/symbols → greedy WordPiece → `[CLS]`/`[SEP]`/pad to maxLen):
```kotlin
package io.dotnetnativeinterop.evs

import java.text.Normalizer

/**
 * Minimal BERT WordPiece tokenizer (uncased) — a faithful Kotlin port of the engine's C#
 * WordPieceTokenizer so the embedding ids match the .NET-published index exactly.
 */
public class WordPieceTokenizer(vocabLines: List<String>) {

    private val vocab: Map<String, Long> = HashMap<String, Long>(vocabLines.size).apply {
        vocabLines.forEachIndexed { i, line -> put(line, i.toLong()) }
    }
    private val clsId = vocab.getValue("[CLS]")
    private val sepId = vocab.getValue("[SEP]")
    private val unkId = vocab.getValue("[UNK]")
    private val padId = vocab.getValue("[PAD]")

    /** Encodes text into padded ids + attention mask of length [maxLen]. */
    public fun encode(text: String, maxLen: Int = 64): Pair<LongArray, LongArray> {
        val pieces = ArrayList<Long>()
        pieces.add(clsId)
        outer@ for (word in basicTokenize(text)) {
            for (piece in wordPiece(word)) {
                if (pieces.size >= maxLen - 1) break@outer
                pieces.add(piece)
            }
        }
        pieces.add(sepId)

        val ids = LongArray(maxLen)
        val mask = LongArray(maxLen)
        for (i in 0 until maxLen) {
            if (i < pieces.size) { ids[i] = pieces[i]; mask[i] = 1 } else { ids[i] = padId; mask[i] = 0 }
        }
        return ids to mask
    }

    // Lower-case, NFD, drop accents, split on whitespace, peel punctuation/symbols into their own tokens.
    private fun basicTokenize(text: String): List<String> {
        val normalized = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        val words = ArrayList<String>()
        val sb = StringBuilder()
        fun flush() { if (sb.isNotEmpty()) { words.add(sb.toString()); sb.setLength(0) } }
        for (ch in normalized) {
            when {
                Character.getType(ch) == Character.NON_SPACING_MARK.toInt() -> {}      // drop accents
                ch.isWhitespace() -> flush()
                isPunctuationOrSymbol(ch) -> { flush(); words.add(ch.toString()) }
                else -> sb.append(ch)
            }
        }
        flush()
        return words
    }

    // Greedy longest-match WordPiece; unknown words -> [UNK].
    private fun wordPiece(word: String): List<Long> {
        var start = 0
        val out = ArrayList<Long>()
        while (start < word.length) {
            var end = word.length
            var matched = -1L
            while (start < end) {
                val sub = (if (start > 0) "##" else "") + word.substring(start, end)
                val id = vocab[sub]
                if (id != null) { matched = id; break }
                end--
            }
            if (matched < 0) return listOf(unkId)
            out.add(matched)
            start = end
        }
        return out
    }

    // Mirrors C# char.IsPunctuation || char.IsSymbol via Unicode general categories.
    private fun isPunctuationOrSymbol(ch: Char): Boolean = when (Character.getType(ch).toByte()) {
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
        Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION,
        Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL -> true
        else -> false
    }
}
```

- [ ] **Step 3: agreement test `src/test/kotlin/io/dotnetnativeinterop/evs/WordPieceTokenizerTest.kt`**
```kotlin
package io.dotnetnativeinterop.evs

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

@Serializable
private data class Fixture(
    val query: String,
    val ids: List<Long>,
    @SerialName("expectedTopChunkId") val expectedTopChunkId: String,
)

class WordPieceTokenizerTest {
    // Resolve the repo-root assets relative to the gradle module dir (android/app).
    private val root = File(System.getProperty("user.dir")).parentFile.parentFile
    private val vocab = File(root, "core/DotnetNativeInterop.Engine/Ai/assets/vocab.txt").readLines()
    private val fixture = Json { ignoreUnknownKeys = true }
        .decodeFromString<Fixture>(File(root, "core/DotnetNativeInterop.EdgeIndexPublisher/edge-fixtures.json").readText())

    @Test fun idsMatchCsharpFixture() {
        val (ids, _) = WordPieceTokenizer(vocab).encode(fixture.query, maxLen = 64)
        assertEquals(fixture.ids, ids.toList())
    }
}
```
> Note: `user.dir` for `testDebugUnitTest` is the gradle module dir; `parentFile.parentFile` from `android/app` reaches the repo root where `core/` lives. If the path doesn't resolve in the Mac build (verify in Step 4), adjust the relative depth and report it.

- [ ] **Step 4: Run the agreement test** — sync the 3 files; Run (Mac): `$PRELUDE; cd .../android && $GR --no-daemon :app:testDebugUnitTest --tests "io.dotnetnativeinterop.evs.WordPieceTokenizerTest" 2>&1 | tail -25`. Expected: PASS (ids == fixture, i.e. `[101,29329,2180,1005,1056,2707,102,0,…]`). **If it fails, the tokenizer port is wrong — fix it (do not change the assertion); the fixture is ground truth.**

- [ ] **Step 5: Commit**
```bash
git add android/app/src/main/kotlin/io/dotnetnativeinterop/model/EdgeModels.kt android/app/src/main/kotlin/io/dotnetnativeinterop/evs/WordPieceTokenizer.kt android/app/src/test/kotlin/io/dotnetnativeinterop/evs/WordPieceTokenizerTest.kt
git commit -m "feat(android): EVS models + WordPieceTokenizer port (id-parity tested vs C#)"
```

---

## Task 3: EvsEncoder (ai.onnxruntime) + EdgeIndex (SQLite reader)

**Files:** Create `evs/EvsEncoder.kt`, `evs/EdgeIndex.kt`. Verified by compile (Task 5) + the device test (Task 6).

- [ ] **Step 1: `evs/EvsEncoder.kt`**
```kotlin
package io.dotnetnativeinterop.evs

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.LongBuffer
import kotlin.math.sqrt

/** all-MiniLM sentence encoder via onnxruntime-android (app-layer; CPU EP). Mean-pool + L2-normalize. */
public class EvsEncoder(modelPath: String) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(modelPath, OrtSession.SessionOptions())
    private val outputName: String = session.outputNames.first()

    public fun embed(ids: LongArray, mask: LongArray): FloatArray {
        val shape = longArrayOf(1L, ids.size.toLong())
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape).use { idsT ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape).use { maskT ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size)), shape).use { typeT ->
                    val inputs = mapOf(
                        "input_ids" to idsT,
                        "attention_mask" to maskT,
                        "token_type_ids" to typeT,
                    )
                    session.run(inputs).use { results ->
                        @Suppress("UNCHECKED_CAST")
                        val hidden = (results[outputName].get().value as Array<Array<FloatArray>>)[0] // [len][384]
                        val dim = hidden[0].size
                        val pooled = FloatArray(dim)
                        var count = 0
                        for (t in ids.indices) {
                            if (mask[t] == 0L) continue
                            count++
                            for (d in 0 until dim) pooled[d] += hidden[t][d]
                        }
                        if (count > 0) for (d in 0 until dim) pooled[d] /= count
                        var norm = 0f
                        for (v in pooled) norm += v * v
                        norm = sqrt(norm)
                        if (norm > 0f) for (d in 0 until dim) pooled[d] /= norm
                        return pooled
                    }
                }
            }
        }
    }

    override fun close() {
        session.close()
    }
}
```
> Note on the output cast: all-MiniLM's token-embeddings output is a 3-D float tensor `[1, len, 384]`; `ai.onnxruntime` surfaces it as `Array<Array<FloatArray>>`. If the model's first output is the pooled/`sentence_embedding` `[1,384]` instead, `results[outputName]` is `Array<FloatArray>` — verify against the device test and adjust the pooling (the iOS `EvsOrt.m` mean-pools the token output, so expect 3-D). Report which shape you saw.

- [ ] **Step 2: `evs/EdgeIndex.kt`**
```kotlin
package io.dotnetnativeinterop.evs

import android.database.sqlite.SQLiteDatabase
import io.dotnetnativeinterop.model.EdgeChunk
import io.dotnetnativeinterop.model.EdgeMetadata
import io.dotnetnativeinterop.model.evsJson
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads the .NET-published edge-index.db (table Chunks; Embedding = little-endian float32[384] BLOB). */
public object EdgeIndex {
    public fun load(dbPath: String): List<EdgeChunk> {
        val chunks = ArrayList<EdgeChunk>()
        SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(
                "SELECT ChunkId, DocumentId, SectionTitle, ContentText, Embedding, Metadata FROM Chunks",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    val blob = c.getBlob(4)
                    val fb = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    val emb = FloatArray(fb.remaining()).also { fb.get(it) }
                    val meta = evsJson.decodeFromString<EdgeMetadata>(c.getString(5))
                    chunks.add(
                        EdgeChunk(
                            chunkId = c.getString(0),
                            documentId = c.getString(1),
                            sectionTitle = c.getString(2),
                            contentText = c.getString(3),
                            errorCodes = meta.errorCodes,
                            toolsRequired = meta.toolsRequired,
                            embedding = emb,
                        ),
                    )
                }
            }
        }
        return chunks
    }
}
```

- [ ] **Step 3: Commit** (compile verified in Task 5)
```bash
git add android/app/src/main/kotlin/io/dotnetnativeinterop/evs/EvsEncoder.kt android/app/src/main/kotlin/io/dotnetnativeinterop/evs/EdgeIndex.kt
git commit -m "feat(android): EVS ONNX encoder (onnxruntime-android) + SQLite index reader"
```

---

## Task 4: EdgeSearchEngine + EvsViewModel

**Files:** Create `evs/EdgeSearchEngine.kt`, `evs/EvsViewModel.kt`

- [ ] **Step 1: `evs/EdgeSearchEngine.kt`**
```kotlin
package io.dotnetnativeinterop.evs

import io.dotnetnativeinterop.model.EdgeChunk
import io.dotnetnativeinterop.model.EdgeHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Loads the encoder + index once from the extracted assets dir, then ranks the index by cosine similarity
 * to a query (vectors are L2-normalized, so cosine == dot). App-layer only — no engine calls.
 */
public class EdgeSearchEngine(private val assetsDir: File) {

    private val gate = Mutex()
    private var tokenizer: WordPieceTokenizer? = null
    private var encoder: EvsEncoder? = null
    private var chunks: List<EdgeChunk> = emptyList()

    private suspend fun ensureLoaded() {
        if (encoder != null) return
        gate.withLock {
            if (encoder == null) {
                tokenizer = WordPieceTokenizer(File(assetsDir, "vocab.txt").readLines())
                chunks = EdgeIndex.load(File(assetsDir, "edge-index.db").absolutePath)
                encoder = EvsEncoder(File(assetsDir, "model.onnx").absolutePath)
            }
        }
    }

    /** Distinct facet values across the index, for the filter chips. */
    public suspend fun facets(): Pair<List<String>, List<String>> {
        ensureLoaded()
        val codes = chunks.flatMap { it.errorCodes }.distinct().sorted()
        val tools = chunks.flatMap { it.toolsRequired }.distinct().sorted()
        return codes to tools
    }

    public suspend fun search(
        query: String,
        minScore: Float = 0.70f,
        topK: Int = 20,
        errorCodes: Set<String> = emptySet(),
        tools: Set<String> = emptySet(),
    ): List<EdgeHit> = withContext(Dispatchers.Default) {
        ensureLoaded()
        val enc = encoder ?: return@withContext emptyList()
        val (ids, mask) = tokenizer!!.encode(query)
        val q = gate.withLock { enc.embed(ids, mask) }   // ORT session is not thread-safe
        chunks.asSequence()
            .filter { c -> errorCodes.isEmpty() || c.errorCodes.any { it in errorCodes } }
            .filter { c -> tools.isEmpty() || c.toolsRequired.any { it in tools } }
            .map { c -> EdgeHit(c, cosine(q, c.embedding)) }
            .filter { it.score >= minScore }
            .sortedByDescending { it.score }
            .take(topK)
            .toList()
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        val n = minOf(a.size, b.size)
        for (i in 0 until n) dot += a[i] * b[i]
        return dot
    }
}
```

- [ ] **Step 2: `evs/EvsViewModel.kt`**
```kotlin
package io.dotnetnativeinterop.evs

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.dotnetnativeinterop.AssetExtractor
import io.dotnetnativeinterop.model.EdgeHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

public data class EvsUiState(
    val query: String = "",
    val results: List<EdgeHit> = emptyList(),
    val availableErrorCodes: List<String> = emptyList(),
    val availableTools: List<String> = emptyList(),
    val activeErrorCodes: Set<String> = emptySet(),
    val activeTools: Set<String> = emptySet(),
    val loading: Boolean = false,
    val error: String? = null,
)

public class EvsViewModel(app: Application) : AndroidViewModel(app) {

    private val engine = EdgeSearchEngine(AssetExtractor.ensure(app))
    private val _state = MutableStateFlow(EvsUiState())
    public val state: StateFlow<EvsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.Default) { engine.facets() } }
                .onSuccess { (codes, tools) ->
                    _state.update { it.copy(availableErrorCodes = codes, availableTools = tools) }
                }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    public fun setQuery(q: String): Unit = _state.update { it.copy(query = q) }
    public fun toggleErrorCode(c: String): Unit = _state.update {
        it.copy(activeErrorCodes = it.activeErrorCodes.toggle(c))
    }
    public fun toggleTool(t: String): Unit = _state.update { it.copy(activeTools = it.activeTools.toggle(t)) }

    public fun search() {
        val s = _state.value
        if (s.query.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { engine.search(s.query, errorCodes = s.activeErrorCodes, tools = s.activeTools) }
                .onSuccess { r -> _state.update { it.copy(results = r, loading = false) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }

    private fun Set<String>.toggle(v: String): Set<String> = if (v in this) this - v else this + v
}
```

- [ ] **Step 3: Verify it compiles** — sync the 2 files; `$PRELUDE; cd .../android && $GR --no-daemon :app:assembleDebug 2>&1 | tail -15`. Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**
```bash
git add android/app/src/main/kotlin/io/dotnetnativeinterop/evs/EdgeSearchEngine.kt android/app/src/main/kotlin/io/dotnetnativeinterop/evs/EvsViewModel.kt
git commit -m "feat(android): EVS search engine + view model (cosine, 0.70, facets)"
```

---

## Task 5: EdgeSearchScreen + AppShell route

**Files:** Create `ui/tabs/EdgeSearchScreen.kt`; modify `ui/AppShell.kt`

`internal @Composable`, `val s by vm.state.collectAsStateWithLifecycle()`.

**EdgeSearchScreen(vm: EvsViewModel, modifier):** (build spec)
- Scrollable `Column`, padded. `OutlinedTextField(value = s.query, onValueChange = vm::setQuery, label = { Text("Search manuals…") }, fillMaxWidth)` + `Button(onClick = { vm.search() }) { Text("Search") }`.
- If `s.availableErrorCodes` non-empty: a label "Error codes" + a horizontally-scrollable `Row` (or `FlowRow`) of `FilterChip(selected = code in s.activeErrorCodes, onClick = { vm.toggleErrorCode(code) }, label = { Text(code) })`. Same for `s.availableTools` ("Tools" → `vm.toggleTool`).
- `if (s.loading) CircularProgressIndicator()`. `if (s.error != null) Text(s.error!!, color = colorScheme.error)`.
- Results `LazyColumn` (fixed height, e.g. `heightIn(max=520.dp)`, to nest in the scroll) of `s.results`: each a `Card` with `Text(hit.chunk.sectionTitle, titleSmall)`, `Text(hit.chunk.contentText, bodySmall, maxLines=3, overflow=Ellipsis)`, a `LinearProgressIndicator(progress = { hit.score.coerceIn(0f,1f) })` + `Text("%.0f%%".format(hit.score*100))`, and a `Row` of error-code chips if `hit.chunk.errorCodes` non-empty.
- A caption `Text("On-device ONNX over a .NET-published index — no network, no engine call.", labelSmall, onSurfaceVariant)`.
- Add `@OptIn(ExperimentalLayoutApi::class)` if using `FlowRow`.

- [ ] **Step 1:** Implement `EdgeSearchScreen.kt` per the spec.
- [ ] **Step 2:** In `AppShell.kt`, add `import io.dotnetnativeinterop.ui.tabs.EdgeSearchScreen` and an `EvsViewModel` (it's an `AndroidViewModel`; `viewModel()` supplies the Application). Replace:
```kotlin
                Tab.Manuals -> GatedTabScreen("Manuals", "Offline Edge Vector Search over the maintenance corpus.", "ONNX-on-Android native gate", content)
```
with:
```kotlin
                Tab.Manuals -> {
                    val vm: io.dotnetnativeinterop.evs.EvsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
                    EdgeSearchScreen(vm, content)
                }
```
(Leave `Tab.Lab` on `GatedTabScreen`.)

- [ ] **Step 3: Verify** — sync both; `$PRELUDE; cd .../android && $GR --no-daemon :app:assembleDebug 2>&1 | tail -15`. Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**
```bash
git add android/app/src/main/kotlin/io/dotnetnativeinterop/ui/tabs/EdgeSearchScreen.kt android/app/src/main/kotlin/io/dotnetnativeinterop/ui/AppShell.kt
git commit -m "feat(android): EVS search screen; route the Manuals tab to it"
```

---

## Task 6: Instrumented proof — EvsTabTest

**Files:** Create `android/app/src/androidTest/kotlin/io/dotnetnativeinterop/EvsTabTest.kt`

Proves on the emulator that the Kotlin ORT + tokenizer + index + cosine pipeline reproduces the .NET-published ranking: the top hit for the fixture query is `hvac-001#2`.

- [ ] **Step 1: Write the test**
```kotlin
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
        assertTrue("top score >= 0.70", hits.first().score >= 0.70f)
        assertEquals("top hit == fixture expectedTopChunkId", "hvac-001#2", hits.first().chunk.chunkId)
        android.util.Log.i("EvsTab", "PASS: top=${hits.first().chunk.chunkId} score=${hits.first().score} hits=${hits.size}")
    }
}
```
(No `System.loadLibrary("dni")` — EVS is app-layer; the `onnxruntime-android` AAR loads its own native libs.)

- [ ] **Step 2: Run on the emulator** — sync; ensure the emulator is up; Run (Mac):
```
$PRELUDE; cd /Users/steve/dni-rag-build/android && adb logcat -c; $GR --no-daemon :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.dotnetnativeinterop.EvsTabTest 2>&1 | tail -25; adb logcat -d -s EvsTab:I | tail -4
```
Expected: `BUILD SUCCESSFUL`, 1 test passed; logcat `PASS: top=hvac-001#2 score=… hits=…`. If the top hit differs or scores are all < 0.70, the encoder output handling or BLOB decoding is wrong — diagnose (check the `EvsEncoder` output-shape note); do NOT weaken the assertion.

- [ ] **Step 3: Commit**
```bash
git add android/app/src/androidTest/kotlin/io/dotnetnativeinterop/EvsTabTest.kt
git commit -m "test(android): EVS — Kotlin ORT top hit matches the .NET-published fixture on device"
```

---

## Task 7: Manual smoke + PR

- [ ] **Step 1: Manual** — install + launch; open the Manuals tab; run "compressor won't start" → ranked results with scores; toggle an error-code/tools chip → results filter. Screenshot.
- [ ] **Step 2: PR**
```bash
git push -u origin feat/android-evs-tab
gh pr create --base main --title "Android Manuals/EVS tab: app-layer Kotlin ONNX over the published index" --body "<summary + screenshot; note onnxruntime-android 1.20.1 pickFirst, tokenizer id-parity test, top hit == fixture; zero engine calls>"
```
- [ ] **Step 3: Watch `ci-android`** (build-only; the EVS tests run on the local emulator).

---

## Self-Review (against the spec)

- **Spec coverage:** onnxruntime-android 1.20.1 + pickFirst (Task 1); bundle edge-index.db (Task 1); EdgeModels (Task 2); WordPieceTokenizer port + agreement test (Task 2); EvsEncoder (Task 3); EdgeIndex SQLite reader (Task 3); EdgeSearchEngine cosine/0.70/facets/top20 + EvsViewModel (Task 4); EdgeSearchScreen + Tab.Manuals route (Task 5); instrumented top-hit==fixture proof (Task 6); additive + app-layer-only (no `dni_*`). ✔
- **Type consistency:** `EdgeChunk`/`EdgeHit`/`EdgeMetadata`/`evsJson` shared (model/EdgeModels.kt); `WordPieceTokenizer.encode → Pair<LongArray,LongArray>` consumed by `EvsEncoder.embed` + the engine; `EdgeSearchEngine.search(...) : List<EdgeHit>` consumed by the VM + test; `EvsViewModel` is `AndroidViewModel` so `viewModel()` supplies the Application (matches the AppShell wiring). ✔
- **Placeholder scan:** screen is a build-spec (as in SP1/AI tab); contract layers (models/tokenizer/encoder/index/engine/VM/tests) fully coded. The two explicit "verify-and-adjust" notes (test `user.dir` depth; encoder output rank) are guarded checks on environment/runtime facts, not TODOs.
