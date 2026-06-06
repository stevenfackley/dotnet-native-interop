# Ask the Manuals — on-device RAG, engine vs Apple (Phase 5) — Design

**Date:** 2026-06-05
**Status:** Approved — ready for implementation plan.
**Author:** pairing session

## Context

`DotnetNativeInterop` embeds one NativeAOT .NET 10 engine in a native iOS app, reached over three interop
transports (FFI, raw-HTTP/SSE, SQLCipher). Phases 1–4 (merged) added the Lab, Latency Lab + telemetry, a
rich About, Phase 3's on-device AI (in-engine .NET ONNX semantic search + a Swift Apple Foundation Models
chat), and Phase 4's Edge Vector Search (Swift ONNX + Core ML retrieval over a prebuilt manuals index).

Every prior AI phase did **retrieval** (rank), never **generation**. The repo's entire thesis is encoded in
one deferred seam: `ILanguageModel.GenerateAsync`. The README, both shipped mock implementations
(`MockLanguageModel`, `FeatureShowcaseModel`), and the interface's own docstring all make the same promise
verbatim — *"Replace with a llama.cpp-backed `ILanguageModel` and nothing else changes."* The full streaming
path already exists and is exercised today by those mocks: `InferenceOrchestrator` → bounded channel →
`dni_session_start` / `dni_token_cb` (FFI) and the HTTP/SQLCipher hosts → the `@MainActor` UI hop.

**Phase 5 cashes in that promise** and frames it as the project's signature contrast:

- **Engine RAG (on-thesis):** a real on-device generative model (Llama-3.2-1B-Instruct, GGUF Q4_K_M) run
  **inside the NativeAOT engine** via hand-rolled P/Invoke of `llama.cpp`, grounded on a retrieved manuals
  corpus, streamed token-by-token across the **selected** transport.
- **Apple RAG (contrast):** the **same** retrieved context handed to Apple's on-device model
  (`FoundationModels`) in Swift.

Same query, same retrieved context, two generators, side by side — mirroring the project's earlier contrasts
(.NET-in-engine ONNX vs Swift Core ML in Phases 3/4).

## Decisions (user, 2026-06-05)

- **Both generators ship (the contrast).** Engine llama.cpp RAG *and* Apple Foundation Models RAG.
- **Engine model:** Llama-3.2-1B-Instruct **Q4_K_M** (~0.8 GB), vendored via Git LFS (the
  `onnxruntime.xcframework` LFS precedent).
- **Retrieval is single-sourced in the engine.** One retriever (reuse Phase 3a `SemanticSearch`, add a
  `manuals` corpus) feeds both generators identical context; Phase 4's Swift EVS path is left untouched.
- **Engine RAG streams over all three transports** (FFI / raw-HTTP-SSE / SQLCipher), honoring the existing
  transport picker — not FFI-only.
- **UI is additive into the existing AI tab** — no new tab; nothing existing is replaced.
- **Spike-gated with documented-failure fallback** (project DNA): if the llama.cpp NativeAOT-iOS link is
  intractable, the attempt stays in the repo with a findings doc and an extractive fallback generator ships.

## Non-goals (this spec)

- **Android.** Still the standing follow-on; not in this phase.
- **Multi-turn chat memory / conversation history.** Single-shot grounded Q&A only.
- **Fine-tuning or training.** The GGUF is used as published.
- **Replacing the existing showcase stream.** `dni_session_start` + `FeatureShowcaseModel` keep their exact
  current behavior; RAG gets its own additive export.
- **Re-using Phase 4's prebuilt SQLite EVS index inside the engine.** The engine re-embeds the manuals at
  launch with its own encoder (simpler, self-contained); the EVS index remains Phase 4's Swift-side asset.

---

## The gate: llama.cpp-under-NativeAOT feasibility spike (step 0)

`llama.cpp` (a C/C++ static library) statically linked into a NativeAOT iOS dylib is unproven here, so the
build is **gated** — the third such native-link gate after SQLCipher and ONNX.

1. **Windows AOT first (fastest signal):** a throwaway `PublishAot=true` console that P/Invokes a
   `llama.cpp` static lib, loads `Llama-3.2-1B-Instruct-Q4_K_M.gguf`, and runs one short decode. Confirms
   the P/Invoke surface is AOT-safe (no fatal reflection/trim) and the native lib links.
   `dotnet publish -r win-x64 -p:PublishAot=true`.
2. **Then iOS:** build `llama.cpp` as a static lib for `ios-arm64` (CPU baseline; Metal as an optional
   stretch — see Risks), static-link it into `dni.dylib` via `DirectPInvoke` + `NativeLibrary` (the
   SQLCipher/ONNX pattern), and build the framework.
3. **Gate decision:**
   - **Pass** (decode works under AOT, links on iOS) → ship `LlamaLanguageModel`. Write
     `docs/llama-nativeaot-ios-findings.md` — "how we linked llama.cpp into NativeAOT iOS" (the static-lib
     recipe, `-lc++`, the GGUF load path, the C-API P/Invoke surface, Metal notes), explicitly contrasted
     with the ONNX (`onnx-nativeaot-ios-findings.md`) and Core ML (`onnx-coreml-edge-findings.md`) writeups.
   - **Fail** (AOT-incompatible surface, or the iOS link is intractable) → **keep the attempt in the repo**
     under `core/DotnetNativeInterop.Engine/Ai/Llama/` (clearly experimental, excluded from the build like
     the gRPC reference), write `docs/llama-nativeaot-ios-findings.md` with the **exact** reproduction +
     error output + diagnosis + "what a fix would need," and ship the **extractive fallback generator**
     (below). The plan branches here and covers both sides.

Either way, real grounded RAG ships and the spike outcome is documented for the community.

---

## Architecture (additive; nothing replaced)

```
            Query: "compressor won't start"
                        │
       ┌────────────────┴─────────────────┐
       ▼                                   ▼
  ENGINE RAG (on-thesis)            APPLE RAG (contrast, Swift)
  .NET NativeAOT engine:           Swift:
   ManualsCorpus (embedded          identical retrieved chunks
     at launch, engine encoder)       (from the engine, deterministic)
   → SemanticSearch (Phase 3a)      → LanguageModelSession (Apple FM)
   → RagLanguageModel               → streamed grounded answer
       retrieve top-K
       → assemble grounded prompt
       → LlamaLanguageModel (P/Invoke llama.cpp, GGUF)
   → InferenceOrchestrator        (EXISTS, unchanged)
   → bounded channel              (EXISTS, unchanged)
   → FFI / raw-HTTP-SSE / SQLCipher  (selected transport)
   → streamed tokens → @MainActor
```

Retrieval lives **once**, in the engine. Generation is decoupled from it behind `ILanguageModel`, so the
gate can fail without sinking the feature. The existing streaming machinery is reused verbatim — the entire
point of the phase.

## Components

### Engine (`core/DotnetNativeInterop.Engine/Ai/`)

- **`ManualsCorpus`** — loads the manuals docs (the five existing markdown files currently under
  `core/DotnetNativeInterop.EdgeIndexPublisher/corpus/`, bundled into the app), splits each into passage
  chunks, and embeds them once at init with the **existing** encoder (`ITextEncoder` — the same
  `model.onnx` + `vocab.txt` already bundled and proven in Phases 3/4). Registered as a third corpus
  (`"manuals"`) alongside `features` / `facts` in `SemanticSearch`.
- **`LlamaLanguageModel : ILanguageModel`** — the real backend the README promised. Hand-rolled P/Invoke of
  the `llama.cpp` C API: load the GGUF + context once, tokenize the prompt, run the decode loop, and
  `yield return` decoded text fragments (honoring `MaxTokens` / `Temperature` from `InferenceRequest` and
  the `CancellationToken`). AOT-safe: blittable signatures, `DirectPInvoke`, no reflection.
- **`RagLanguageModel(ILanguageModel generator, SemanticSearch search) : ILanguageModel`** — the RAG
  composition: on `GenerateAsync(request)`, retrieve top-K manuals chunks for `request.Prompt`, assemble a
  grounded prompt (system instruction + retrieved context + question), delegate to `generator`, and stream
  its fragments. The **prompt-assembly function is the one place domain judgment shapes answer quality** —
  authored as a focused, well-named method during implementation.
- **`ExtractiveLanguageModel : ILanguageModel`** (fallback path only) — stitches the retrieved chunks into a
  templated grounded answer and streams it sentence-by-sentence. Real retrieval, no neural generation;
  honest "extractive RAG" labeling in the UI.

### NativeBridge — ABI (additive only)

- **New:** `dni_rag_session_start(query, max_tokens, temperature, callback, user_data)` — mirrors
  `dni_session_start` but constructs/uses `RagLanguageModel`. **Separate export so the existing
  `dni_session_start` showcase stream is byte-for-byte unchanged.** Reuses the existing `dni_token_cb`,
  `dni_session_cancel`, `dni_session_free`, and the orchestrator/channel session machinery verbatim.
- **Raw-HTTP:** add an SSE inference route carrying the query (e.g. `GET /rag?q=…` → `text/event-stream` of
  tokens), parallel to the existing feature routes — so the engine RAG streams over the HTTP transport too.
- **SQLCipher:** add a session-based RAG round-trip over the encrypted broker, parallel to the existing
  `dni_sqlite_*` catalog path — so the engine RAG streams over the SQLCipher transport too.
- **Reused as-is:** `dni_search(query, corpus)` gains `"manuals"` as a valid corpus id; the UI calls it to
  display the shared "Sources" and to feed the Apple FM side identical context.

> ABI changed → **the xcframework MUST be rebuilt** (and the llama.cpp link happens here anyway). Build as
> `steve`; see `docs/ios-build-deploy-runbook.md`.

### iOS (`ios/Shared/Ai/`)

- Extend the existing **AI tab** (additive) with an **"Ask the Manuals"** screen: one query field; a shared
  **Sources** list (top-K chunks from `dni_search(query,"manuals")`); two answer panes that stream
  concurrently — **Engine** (RAG over the selected transport, via `dni_rag_session_start` / the HTTP-SSE /
  SQLCipher route) and **Apple** (`LanguageModelSession` over the same chunks) — each with per-side
  first-token + total timing. The engine pane honors the transport picker; the Apple pane is gated on
  `SystemLanguageModel.default.availability` and degrades to a `ContentUnavailableView` on ineligible
  devices. New Swift: `RagService` (engine, FFI/HTTP/SQLCipher) + `AppleRagService` (Swift FM) + the view;
  existing AI-tab screens (semantic search, Apple chat) are untouched.

## Data flow

`query` → `dni_search(query,"manuals")` → top-K chunks (rendered as **Sources**). **Engine pane:**
`dni_rag_session_start(query)` (or the HTTP-SSE / SQLCipher route for those transports) → orchestrator →
`RagLanguageModel` retrieves the same chunks (deterministic for the same query/corpus/encoder), assembles
the grounded prompt, runs `LlamaLanguageModel` → tokens stream over the selected transport → `@MainActor`.
**Apple pane:** the displayed chunks + query → `LanguageModelSession` → streamed answer. Retrieval is
deterministic, so both panes are grounded on identical context without marshalling chunks across the
boundary twice.

## Error handling

- `dni_rag_session_start` (and the HTTP/SQLCipher routes) return a negative status (`DNI_INVALID_ARGUMENT`,
  `DNI_INTERNAL`) on bad args or GGUF/model-load failure → the service surfaces a clear "engine model
  unavailable" state, never a crash.
- Empty/oversized query clamped; empty retrieval → the prompt still answers "not found in the manuals"
  rather than hallucinating (enforced by the grounded prompt template).
- Cancellation (`dni_session_cancel`) stops the decode loop promptly via the `CancellationToken` already
  threaded through `GenerateAsync`.
- Apple side gated on availability; ineligible device → explanatory card, the Engine pane still works.
- Fallback build: `ExtractiveLanguageModel` cannot fail to "generate" given any retrieval, so the Engine
  pane always renders something honest.

## Testing & verification

- **Spike:** Windows `PublishAot` console loads the GGUF and produces non-empty coherent tokens (gate
  signal), then the iOS static-link.
- **Engine (Windows probe):** `ManualsCorpus` retrieves the expected doc for a known query
  ("compressor won't start" → the HVAC chunk) and the assembled prompt contains that chunk;
  `RagLanguageModel` over a stub generator streams the prompt-grounded fragments in order and terminates
  with the final marker; managed build `-c Release` clean.
- **iOS (Mac — ABI changed, framework rebuild mandatory):** AI tab shows "Ask the Manuals"; both panes
  stream sensibly over **each** transport (FFI/HTTP/SQLCipher) with correct shared Sources and timings;
  Apple pane responds or shows the graceful unavailable card; **no regressions** across
  Dashboard/Features/Lab/Compare/Latency/About and the existing AI screens.

## Risks

- **The spike is the risk.** Documented extractive fallback guarantees the feature ships; the failed
  llama.cpp attempt is preserved + documented either way (as with SQLCipher and ONNX).
- **App size:** +~0.8 GB GGUF (LFS) on top of the existing ONNX assets. Acceptable on a modern iPad;
  flagged. The model is bundled, not downloaded.
- **Metal vs CPU:** CPU-only `llama.cpp` is the safe floor and the gate baseline. Metal GPU offload on the
  device is an optional stretch (bundle the `.metallib`, link the Metal framework) — pursued only after the
  CPU path passes; not required for the phase to ship.
- **1B answer quality:** RAG grounding carries a small model a long way, but coherence still trails Apple
  FM. That gap is itself the honest content of the contrast.
- **Transport breadth:** wiring RAG streaming over HTTP-SSE and SQLCipher (not just FFI) is additional plan
  work; the existing showcase already streams over all three, so the machinery exists to mirror.

## Follow-on (named, not built here)

- Android parity (the standing follow-on) — same shared contract, Compose UI.
- Multi-turn chat memory; Metal GPU offload if left as a stretch; swapping in larger GGUFs behind the same
  `ILanguageModel`.
