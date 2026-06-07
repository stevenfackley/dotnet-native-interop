# Android AI Tab UI (Design)

**Date:** 2026-06-07
**Program:** Android 1:1 parity. SP0 ✅, SP1 UI shell ✅, ONNX gate ✅ (PR #22, merged). This cycle
replaces the AI tab's gated placeholder with real screens. Branch: `feat/android-ai-tab` (off `main`).

## Goal

Turn the AI tab's `GatedTabScreen` placeholder into the real AI experience: a **Semantic Search** screen
(`dni_search` → ranked results) and an **Ask the Manuals** RAG screen (retrieve manual passages + stream a
grounded answer over FFI). Mirrors the iOS AI tab minus Apple Chat. Proven on the arm64 emulator.

## Background / current state

- The ONNX gate (merged) makes `dni_search` and RAG retrieval work on Android: the host extracts the
  model/vocab/corpus/manuals to `filesDir/dni-assets`, calls `dni_set_assets_dir`, and `SemanticSearch`
  builds three corpora ("features", "facts", "manuals"). `NativeBridge.nativeSearch(query, corpus)` and
  `nativeRagSessionStart(query, maxTokens, temp, listener)` are already bound; `FfiTokenListener` exists.
- The iOS AI tab (`ios/Shared/Ai/`) has three sub-screens: Semantic Search (`dni_search`), Ask the Manuals
  (RAG: retrieve "manuals" + stream answer), and Apple Chat (Apple Foundation Models — **dropped** on
  Android). Ask-the-Manuals on iOS also has an FFI/HTTP/SQLite transport picker; this cycle does FFI only.
- **RAG generation degrades gracefully without the GGUF.** `EngineHost.BuildRagModel` uses
  `LlamaLanguageModel` when `Llama-3.2-1B-Instruct-Q4_K_M.gguf` is in the assets dir, else the managed
  `ExtractiveLanguageModel` (grounded answer stitched from retrieved passages, streamed). The 0.77 GB GGUF
  is **not** bundled on Android, so Ask-the-Manuals uses the extractive fallback — fully functional today.
- Android currently: `AppShell.kt` routes `Tab.Ai -> GatedTabScreen(...)`. The SP1 catalog stack
  (`feature/` services + ViewModels, `ui/tabs/` screens) is the MVVM pattern to mirror. The existing
  `transport/` streaming clients + `InferenceScreen` are untouched by this cycle.

## Scope

**In scope:** Both AI sub-screens, FFI transport.
- Semantic Search: corpus picker (features/facts/manuals) + query → `dni_search` → ranked results.
- Ask the Manuals: query → retrieved sources (`dni_search` "manuals") + a streaming grounded answer
  (`dni_rag_session_start`, extractive generation) + first-token/total timing.
- Replace the AI placeholder in `AppShell`. MVVM mirroring SP1. Instrumented emulator proof.

**Out of scope (follow-ons):**
- Bundling the GGUF for neural llama generation (a documented future toggle; ~0.77 GB APK growth).
- HTTP/SSE and SQLite RAG transport variants (iOS has them for comparison; the Compare tab already covers
  transport timing for the catalog).
- Apple Chat (no Android equivalent — permanently dropped).
- The Manuals/EVS tab (a separate edge-search ONNX path; its own cycle).

## In-tab navigation

The AI tab is a single drawer destination. `AiScreen` shows a top Material3 segmented toggle —
**"Semantic Search" | "Ask the Manuals"** — and renders the selected sub-screen via a local
`remember` state. No nested NavHost (consistent with the shell's state-based navigation).

## Architecture — MVVM mirroring SP1

```
AiScreen (toggle)
 ├─ AiSearchScreen   → AiSearchViewModel → SearchService → NativeBridge.nativeSearch
 └─ AskManualsScreen → RagViewModel ──┬─→ SearchService.search(query,"manuals")  (sources)
                                      └─→ RagService(FfiRagService) → nativeRagSessionStart (answer stream)
```

- **`model/AiModels.kt`** — `@Serializable data class SearchResult(text: String, score: Double)` (the
  `dni_search` JSON shape).
- **`ai/SearchService.kt`** — `suspend fun search(query: String, corpus: String): List<SearchResult>` →
  `NativeBridge.nativeSearch(query, corpus)` then `Json.decodeFromString` (reuse a shared lenient `Json`).
  FFI-only (search has no transport variants).
- **`ai/RagService.kt`** — interface `fun answer(query: String): Flow<String>` (each emission is an answer
  fragment to append). **`ai/FfiRagService.kt`** — bridges `nativeRagSessionStart` + an `FfiTokenListener`
  into a `callbackFlow`/`Channel` (the same bridging pattern the existing `FfiClient` uses for
  `nativeSessionStart`; this is a separate class on the RAG entrypoint — `FfiClient` is untouched).
- **`ai/AiSearchViewModel.kt`** — `StateFlow<AiSearchUiState>` (corpus, query, results, loading, error),
  `selectCorpus`, `search`.
- **`ai/RagViewModel.kt`** — `StateFlow<RagUiState>` (query, sources, answer, firstTokenMs, totalMs,
  streaming, error); `ask()` runs retrieval then collects the answer stream, appending deltas.
- **`ui/tabs/AiScreen.kt`**, **`ui/tabs/AiSearchScreen.kt`**, **`ui/tabs/AskManualsScreen.kt`** —
  `internal @Composable`, `collectAsStateWithLifecycle`, reuse the SP1 `TransportPicker`/segmented patterns.

## Screens

**AiSearchScreen(vm: AiSearchViewModel):** a segmented corpus picker (features/facts/manuals), an
`OutlinedTextField` query + "Search" button, a loading indicator, and a results `LazyColumn` — each row
shows the result text and a `LinearProgressIndicator` of `score.coerceIn(0,1)` with a `"similarity 0.XXX"`
label. Error text on failure.

**AskManualsScreen(vm: RagViewModel):** a query field + "Ask" button; a **Sources** card listing the
retrieved passages (text + score); an **Answer** card showing the streamed text (with a small "grounded
extraction" caption so it's honest about non-neural generation); a metrics row (first-token ms, total ms).
A spinner while streaming. Error text on failure.

## Error handling

- `nativeSearch` returns null/blank on engine failure → `SearchService` surfaces an error state (no crash;
  empty results render an empty list).
- `nativeRagSessionStart` returns a session id ≤ 0 on failure → `FfiRagService` completes the flow with an
  error the `RagViewModel` shows as an error banner; partial answers already streamed remain visible.
- The RAG flow cancels/frees the native session on flow cancellation (mirror `FfiClient`'s cleanup), so
  navigating away mid-stream doesn't leak.

## Proof (emulator)

Instrumented test (`AiTabTest`, arm64 emulator): with the assets dir set + engine initialized (as the app
does), assert (a) `SearchService.search("encrypt my data", "facts")` returns a non-empty ranked list with
descending scores, and (b) a RAG answer streams — `RagService("…manuals query…").answer()` collects ≥1
non-empty fragment. Plus `AiScreen` composes (toggle + both sub-screens render). Emit `PASS:` logcat. CI
(`ci-android`) stays the build-only check.

## Decisions honored

- **Additive** — only the `Tab.Ai` route changes in `AppShell`; the existing streaming clients,
  `InferenceScreen`, the catalog stack, and the engine are untouched.
- **Extractive generation** — no GGUF bundle; Ask-the-Manuals is grounded extraction, labeled honestly,
  with neural llama as a future toggle.
- **Material3-idiomatic** — same information architecture as the iOS AI tab (search + ask), native look.
