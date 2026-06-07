# Android Manuals / Edge Vector Search (EVS) Tab (Design)

**Date:** 2026-06-07
**Program:** Android 1:1 parity. SP0 ✅, SP1 shell ✅, ONNX gate ✅, AI tab ✅ (all merged). This cycle
replaces the **Manuals** gated placeholder with a faithful Edge Vector Search port. Branch:
`feat/android-evs-tab` (off `main`). Lab remains the last gated tab after this.

## Goal

Port the iOS Edge Vector Search to Android: a Kotlin **app-layer** ONNX client (`onnxruntime-android`) that
embeds a query, cosine-ranks the prebuilt `edge-index.db`, and shows results with a 0.70 threshold and
metadata facet filters — **without touching the .NET engine** (the contrast that justifies EVS). Proven on
the arm64 emulator: the tokenizer matches the C# ids and the top hit matches the committed fixture.

## Background / current state

- EVS (iOS, merged PR #10) = a **.NET publisher** (`DotnetNativeInterop.EdgeIndexPublisher`: maintenance
  markdown + YAML frontmatter → chunk by `##` → all-MiniLM ONNX embed → SQLite BLOB index) + a **Swift
  app-layer client** (onnxruntime + Core ML, SQLite, cosine, 0.70 threshold, error-code/tools facets). It
  bypasses the engine entirely — inference runs in the UI layer.
- The prebuilt index `core/DotnetNativeInterop.EdgeIndexPublisher/edge-index.db` (committed, ~45 KB) has
  table `Chunks(ChunkId PK, DocumentId, SectionTitle, ContentText, Embedding BLOB, Metadata TEXT)`. The
  `Embedding` BLOB is raw little-endian `float32[384]` (1536 bytes). `Metadata` is JSON
  `{"error_codes":[…],"tools_required":[…]}`. The cross-runtime fixture
  `core/DotnetNativeInterop.EdgeIndexPublisher/edge-fixtures.json` has `query`, `ids` (expected token ids),
  `queryVector`, and `expectedTopChunkId`.
- The model/vocab (`model.onnx`, `vocab.txt`) are already bundled + extracted on Android by the ONNX gate
  (`AssetExtractor` → `filesDir/dni-assets`). The engine already ships `libonnxruntime.so` (1.20.1) in
  `jniLibs` for its own P/Invoke.
- Android currently: `AppShell.kt` routes `Tab.Manuals -> GatedTabScreen(...)`. The AI tab's `dni_search`
  over the "manuals" corpus exists, but EVS is a *separate, app-layer* implementation by design.

## Scope

**In scope:** Faithful Kotlin-ORT EVS port, full UX.
- `onnxruntime-android` 1.20.1 (Kotlin `ai.onnxruntime`), app-layer query embedding (no engine calls).
- A Kotlin `WordPieceTokenizer` port, **parity-tested** against `edge-fixtures.json` ids.
- A SQLite reader for `edge-index.db` (BLOB→FloatArray), cosine ranking, 0.70 threshold, top-20.
- Search UI with error-code + tools facet filters, mirroring iOS `EdgeSearchView`.
- Bundle `edge-index.db`; route `Tab.Manuals -> EdgeSearchScreen`. Instrumented emulator proof.

**Out of scope (follow-ons / non-goals):**
- The Lab tab (the last gated tab — its own cycle).
- INT8/quantized model (the FP32 `model.onnx` is reused, as on iOS).
- Regenerating `edge-index.db` (it's committed; the .NET publisher is unchanged).
- NNAPI for the EVS encoder (CPU EP is fine for a 384-d MiniLM over ~40 chunks; NNAPI is a later option).

## ORT coexistence (the one integration risk)

Add `com.microsoft.onnxruntime:onnxruntime-android:1.20.1` — **pinned to the engine's ORT version**. Its
AAR bundles `jni/arm64-v8a/{libonnxruntime.so, libonnxruntime4j_jni.so}`; the engine's build already places
`libonnxruntime.so` in `jniLibs`. The duplicate `libonnxruntime.so` is resolved with
`packaging { jniLibs { pickFirsts += "lib/arm64-v8a/libonnxruntime.so" } }` in `build.gradle.kts`. Because
both are the identical 1.20.1 binary, pickFirst is safe: the Kotlin `ai.onnxruntime` API gets its
`libonnxruntime4j_jni.so` + the picked `libonnxruntime.so`, and the engine's `dlopen("libonnxruntime.so")`
resolves the same file. No version skew, no symbol mismatch.

## Components (new `evs/` package, mirroring iOS EVS)

- **`evs/WordPieceTokenizer.kt`** — port of `core/.../Ai/WordPieceTokenizer.cs`: lower-case → NFD
  (`Normalizer.normalize(..., NFD)`) → drop combining marks → whitespace + punctuation split → greedy
  longest-match WordPiece (`##` continuation, `[UNK]` fallback) → wrap `[CLS]`/`[SEP]` → pad to maxLen 64;
  returns `(ids: LongArray, mask: LongArray)`. Vocab is `vocab.txt` line→id; specials looked up.
- **`evs/EvsEncoder.kt`** — `ai.onnxruntime.OrtEnvironment` + `OrtSession(modelPath)`; `embed(ids, mask)`
  builds `input_ids`/`attention_mask`/`token_type_ids` `OnnxTensor`s `[1,64]`, runs, mean-pools the output
  over the mask, L2-normalizes → `FloatArray(384)`. Output name queried from session metadata. Closeable.
- **`evs/EdgeIndex.kt`** — opens `edge-index.db` read-only (`SQLiteDatabase.openDatabase` / androidx SQLite),
  reads `Chunks` into `List<EdgeChunk>`: BLOB→`FloatArray` via `ByteBuffer.order(LITTLE_ENDIAN)`, `Metadata`
  JSON parsed with kotlinx.serialization.
- **`evs/EdgeSearchEngine.kt`** — loads the index + encoder once (lazy, off main); `search(query,
  minScore=0.70f, topK=20, errorCodes:Set, tools:Set)`: tokenize → embed → cosine (dot of normalized
  vectors) → threshold → facet-filter → sort desc → take 20.
- **`evs/EvsViewModel.kt`** — `StateFlow<EvsUiState>` (query, results, availableErrorCodes/availableTools,
  activeErrorCodes/activeTools, loading, error); `setQuery`, `search`, `toggleErrorCode`, `toggleTool`.
- **`model/EdgeModels.kt`** — `EdgeChunk(chunkId, documentId, sectionTitle, contentText, errorCodes,
  toolsRequired, embedding: FloatArray)`, `EdgeHit(chunk, score: Float)`.

## Screen

`ui/tabs/EdgeSearchScreen.kt` (Material3, `internal @Composable`, `collectAsStateWithLifecycle`): a query
`OutlinedTextField` + Search button; two horizontally-scrollable `FilterChip` rows (error codes, tools)
bound to the active facet sets; a results `LazyColumn` — each item: section title, a 3-line content snippet,
a similarity `LinearProgressIndicator` + percent, and error-code chips. A caption: "On-device ONNX over a
.NET-published index — no network, no engine call." `AppShell` routes `Tab.Manuals -> EdgeSearchScreen`
(Lab stays `GatedTabScreen`).

## Data flow

```
EdgeSearchScreen → EvsViewModel → EdgeSearchEngine ─┬─ WordPieceTokenizer (vocab.txt)
                                                    ├─ EvsEncoder (ai.onnxruntime, model.onnx)
                                                    └─ EdgeIndex (edge-index.db SQLite)
   query → ids/mask → 384-d vector → cosine vs chunk embeddings → ≥0.70, facet-filtered, top-20
```

## Error handling

- Missing asset (`edge-index.db`/`model.onnx`/`vocab.txt`) or DB-open failure → `EvsUiState.error`, the
  screen shows an error card; no crash.
- The ORT session is not thread-safe → the engine serializes searches (one in flight; a `loading` flag in
  the VM gates re-entry).
- Empty/whitespace query → no-op (no search).

## Correctness + proof

- **Tokenizer agreement (JVM unit test)** — load `edge-fixtures.json`, tokenize its `query`, assert the
  produced ids == the fixture `ids`. This locks the Kotlin tokenizer to the C# one (mirrors iOS's agreement
  test) — the single most important correctness check.
- **Instrumented emulator test** — after `AssetExtractor.ensure` (now incl. `edge-index.db`),
  `EdgeSearchEngine.search(fixtures.query)` returns hits, the top hit's `chunkId == fixtures.expectedTopChunkId`,
  and the top score ≥ 0.70. The `EdgeSearchScreen` composes. Proves Kotlin ORT + tokenizer + index + cosine
  end-to-end on device, matching the .NET-published index.

## Decisions honored

- **App-layer contrast** — EVS makes zero `dni_*` calls; it runs ONNX in Kotlin, distinct from the AI tab's
  engine `dni_search`. That separation is the feature's reason for existing.
- **Reuse, don't regenerate** — the committed `edge-index.db` + the shared `model.onnx`/`vocab.txt` are
  reused as-is (provably the same model the publisher used); the .NET publisher is untouched.
- **Additive** — new `evs/` package + one screen + the `Tab.Manuals` route + gradle dep/packaging + asset
  bundling. The engine, AI tab, catalog stack, and streaming clients are untouched.
