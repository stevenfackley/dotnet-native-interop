# Onboard AI — .NET semantic search + Apple Foundation Models chat (Phase 3) — Design

**Date:** 2026-06-05
**Status:** Approved — ready for implementation plan.
**Author:** pairing session

## Context

`DotnetNativeInterop` embeds one NativeAOT .NET 10 engine in a native iOS app over three interop
transports. Phases 1–2 (merged) added the Lab (visual compute + benchmarks), the Latency Lab, live engine
telemetry, and a rich About. Phase 3 — the **headline** — adds **on-device AI**, in two parts:

- **3a — .NET-driven semantic search** (the on-thesis hero): a real sentence-embedding model run **inside
  the engine**, ranking a corpus by cosine similarity to a free-text query — proving the engine can do
  real ML in-process, not just language demos.
- **3b — Apple Foundation Models chat** (off-thesis contrast): Apple's on-device model driven from Swift,
  framed explicitly as "Apple's model, for comparison."

Both ship in this one phase (user decision, 2026-06-05), under a new additive **"AI" tab**.

## Decisions (user, 2026-06-05)

- **Embeddings: real ONNX Runtime, but spike-gated** — validate ONNX-under-NativeAOT before committing;
  fall back to a **pure-.NET all-MiniLM forward pass** if the spike fails.
- **Corpus: both** — the app's own feature catalog *and* a bundled fact set; user picks in the UI.
- **Scope: both demos in one phase.**
- **Failures are kept as proof** — if the ONNX spike fails, the attempt **stays in the repo** with a
  findings doc showing the exact error + diagnosis (community context), and the pure-.NET fallback ships.
  (See the failure-documentation principle; analogous native-link to the SQLCipher integration.)

## Non-goals (this spec)

- A generative LLM in .NET (that's the streaming `ILanguageModel` seam, left for later). Android.
- RAG/generation on top of search — Phase 3 is retrieval (rank), not generation, on the .NET side.

---

## The gate: ONNX feasibility spike (step 0)

ONNX-RT statically linked into a NativeAOT iOS dylib is unproven, so the build is **gated**:

1. **Windows AOT first (fastest signal):** a throwaway `PublishAot=true` console referencing
   `Microsoft.ML.OnnxRuntime`, loading `all-MiniLM-L6-v2.onnx` and running one inference. Confirms the
   managed wrapper is AOT-safe (no fatal reflection/trim) and the native lib links. `dotnet publish
   -r win-x64 -p:PublishAot=true`.
2. **Then iOS:** static-link the `onnxruntime` ios-arm64 lib into `dni.dylib` via `DirectPInvoke` +
   `NativeLibrary` (the SQLCipher pattern) and build the framework.
3. **Gate decision:**
   - **Pass** (inference works under AOT, links on iOS) → ship the **ONNX encoder**. The findings doc
     becomes the "how we linked ONNX into NativeAOT iOS" writeup.
   - **Fail** (AOT-incompatible wrapper, or the iOS link is intractable) → **keep the ONNX attempt in the
     repo** under `core/DotnetNativeInterop.Engine/Ai/Onnx/` (clearly marked experimental, excluded from
     the build like the gRPC reference), write `docs/onnx-nativeaot-ios-findings.md` with the **exact**
     reproduction + error output + diagnosis + "what a fix would need," and ship the **pure-.NET
     fallback encoder**. The plan branches here; the implementation plan covers both sides.

Either way, real semantic search ships, and the spike outcome is documented for the community.

---

## 3a — Semantic search architecture

Everything except "run the encoder" is identical across the ONNX and pure-.NET paths, behind one
interface so the gate only swaps one component.

### Engine (`core/DotnetNativeInterop.Engine/Ai/`)

- **`ITextEncoder`** — `float[] Encode(string text)` returning a 384-d L2-normalized embedding. Two impls:
  - `OnnxTextEncoder` (ONNX path) — runs `all-MiniLM-L6-v2.onnx` via `Microsoft.ML.OnnxRuntime`.
  - `ManagedTextEncoder` (fallback) — all-MiniLM forward pass in C# (`TensorPrimitives`/`Vector<T>`):
    token+position embeddings → 6 × (multi-head self-attention + FFN + 2 layernorms) → mean-pool over the
    attention mask → L2-normalize. Weights loaded from a bundled file.
- **`WordPieceTokenizer`** — AOT-safe, hand-rolled: load `vocab.txt`, lowercase + strip accents, basic
  whitespace/punctuation split, greedy longest-match WordPiece, wrap with `[CLS]`/`[SEP]`, emit
  `input_ids` + `attention_mask`. No external tokenizer dependency.
- **`SemanticSearch`** — holds the encoder + the two corpora (embeddings precomputed once at init);
  `Search(string query, string corpusId, int topK) → SearchResult[]` where
  `SearchResult(string Text, double Score)`. Ranking reuses the existing
  `TensorPrimitives.CosineSimilarity` (already in the runtime demos).
- **Corpora (both):**
  - `features` — the `LanguageFeatureCatalog` descriptors (`Title` + a short blurb), embedded at startup.
  - `facts` — a bundled `corpus.txt` (~30 one-line .NET/iOS facts), embedded at startup.
- **JSON:** source-gen `AiJsonContext` for `SearchResult[]` (camelCase), like the existing contexts.

### New C ABI export (the query carries arbitrary text → can't ride the `~` command grammar)

```c
/* Ranks `corpus` ("features" | "facts") by cosine similarity to `query`; returns
 * heap UTF-8 JSON [{text,score}] (top-K). Copy then release with dni_string_free. */
const char* dni_search(const char* query, const char* corpus);
```

The first **input-carrying** export. Declared in `abi/dni.h` (surfaced to Swift via the existing
bridging `#import`); a new `Ffi/Exports.Ai.cs` reads the two UTF-8 args (`NativeText.Read`), calls
`SemanticSearch.Search`, returns `NativeText.Allocate(json)`. One framework rebuild (ABI changed).
Model + vocab + `corpus.txt` are bundled into the app and located at a known path the engine reads
(passed in via an init export, or bundled beside the dylib — resolved in the plan).

### iOS (`ios/Shared/Ai/`)

- `SearchResult` Codable; `SemanticSearchService` (FFI: calls `dni_search` on a background `Task`,
  decodes JSON).
- `SemanticSearchView` — a corpus picker (Features / Facts), a query `TextField`, a "Search" action, and
  ranked results with similarity scores. Reuses the Lab/Latency view-model patterns.

## 3b — Apple Foundation Models chat (`ios/Shared/Ai/`)

- `AppleChatView` — uses Swift `FoundationModels`: check `SystemLanguageModel.default.availability`; if
  available, a `LanguageModelSession` drives a simple chat (prompt field → streamed response). If
  unavailable, a graceful `ContentUnavailableView` explaining why (device/iOS not eligible).
- Pure Swift, **no engine involvement** — the UI labels it "Apple's on-device model (for comparison)" so
  the contrast with the .NET-driven search is explicit. Gated so it never crashes on an ineligible device.

## UI — new "AI" tab (additive, 7th tab)

`RootTabView` gains an **"AI"** tab (SF Symbol `sparkles`) → a hub linking **Semantic search (.NET)** and
**Apple chat**. `UnifiedApp` builds the `SemanticSearchService`; the existing tabs are untouched.

## Data flow

Query → `SemanticSearchService` → `dni_search(query, corpus)` (FFI, background) → engine tokenizes,
encodes (ONNX or managed), cosine-ranks the chosen corpus → JSON top-K → decode → ranked list with scores.
Apple chat: prompt → `LanguageModelSession` (Swift) → streamed text.

## Error handling

- `dni_search` returns 0 on failure (bad corpus id, encoder error) → service throws → visible message.
- Tokenizer handles empty/oversized queries (clamp length); unknown tokens → `[UNK]`.
- Model/vocab/corpus load failure surfaces a clear "AI model unavailable" state, never a crash.
- Apple chat gated behind availability; ineligible → explanatory card.

## Testing & verification

- **Spike:** Windows `PublishAot` console runs one ONNX inference (gate signal), then the iOS link.
- **Engine (Windows probe):** tokenize a known sentence and assert the `input_ids` prefix; encode two
  sentences and assert `cosine(query, related) > cosine(query, unrelated)`; `Search("encrypt data",
  "features", 3)` returns the AES-GCM feature in the top results. Managed build `-c Release` clean.
- **iOS (Mac — ABI changed, framework rebuild mandatory):** AI tab present; semantic search ranks
  sensibly over both corpora with scores; Apple chat responds (or shows the graceful unavailable card);
  **no regressions** across Dashboard/Features/Lab/Compare/Latency/About.

## Risks

- **The spike is the risk.** Documented fallback (pure-.NET encoder) guarantees the feature ships; the
  failed ONNX attempt is preserved + documented either way.
- **App size:** all-MiniLM int8 ONNX ~23 MB (fp32 ~90 MB) + `vocab.txt`; the managed fallback still needs
  the weights file. Bundled.
- **Apple FM:** iOS 26 + eligible device; gated so it degrades gracefully.
- **Encoder correctness (fallback path):** a hand-written transformer must match the reference embeddings
  closely enough that ranking is sensible — verified by the cosine-ordering probe, not exact float match.

## Follow-on (named, not built here)

- A generative LLM in .NET via the existing streaming `ILanguageModel` seam. Android parity.
