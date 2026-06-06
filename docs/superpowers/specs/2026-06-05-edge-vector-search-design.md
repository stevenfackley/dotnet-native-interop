# Edge Vector Search (EVS) — offline on-device doc search — Design

**Date:** 2026-06-05
**Status:** **Active** — approved for build. Supersedes the docketed draft of the same name (which predated
Phase 3 shipping); the "EVS as a fallback if in-engine ONNX fails" framing is retired now that Phase 3's
engine-embedded ONNX path is proven and merged (PR #9).
**Phase:** 4 (additive — adds a tab; touches no existing tab).

## Summary

EVS is a second, deliberately different on-device semantic-search path, shipped as a new **"Manuals"** tab
in the existing unified app. A **.NET 10 build-time publisher** compiles a folder of Markdown maintenance
docs into a vector-indexed SQLite database; an **iOS Swift edge client** runs the query embedding
**entirely on-device** via ONNX Runtime + the **Core ML Execution Provider** (Apple Neural Engine) and
cosine-ranks it against that prebuilt index. Fully offline at runtime.

**Domain:** commercial **industrial maintenance** — HVAC and enterprise IT hardware.
**Explicitly out of scope:** aerospace, defense, and S1000D. Do not use or reference them.

## The contrast (why this earns a place beside Phase 3)

One model, **two on-device inference paths in the same app**, visible side by side:

| | AI tab (Phase 3) | Manuals tab (EVS, this phase) |
|---|---|---|
| Who embeds the query | the **.NET NativeAOT engine**, in-process (ONNX statically linked into `dni.dylib`) | **Swift**, via ONNX Runtime + **Core ML / ANE** |
| Corpus | in-memory, embedded at launch | **prebuilt SQLite index**, compiled offline by .NET |
| .NET's role | runtime engine (FFI) | **build-time data pipeline** only — no `dni.dylib`, no FFI |
| Transport | the `dni_search` C ABI export | none — Swift reads SQLite directly |

EVS showcases .NET doing what it is great at (an offline build-time pipeline) while inference runs where it
is first-class on Apple silicon. The contrast is the point; it is not redundant with Phase 3.

## Decisions locked (from brainstorming)

1. **Placement:** a new tab in the unified app (additive), not a standalone Xcode target.
2. **Model:** **reuse the in-repo FP32 `model.onnx` + `vocab.txt`** already bundled by Phase 3 — for both
   the publisher and the Swift client. No new model asset; the two sides share a provably-identical model.
   INT8 quantization is a documented future enhancement, not in scope.
3. **Build strategy:** **spike-gated**, mirroring the Phase 3 pattern — prove the make-or-break unknowns
   on device before building the full pipeline.
4. **Tab label:** "Manuals"; screen title "Edge Vector Search".
5. **`onnxruntime.xcframework`:** vendored into `ios/Frameworks/` via **Git LFS**.

## How it fits the repo

- xcodegen project (`ios/project.yml`), iOS 17 deployment, Swift 6 strict concurrency. Frameworks are
  referenced from `ios/Frameworks/` (e.g. `dni.xcframework`); there is **no CocoaPods/SPM** — so ONNX for
  Swift arrives as a prebuilt **`onnxruntime.xcframework`** (full iOS build, includes the Core ML EP),
  referenced exactly like `dni.xcframework`.
- The Swift client reads the **same** `model.onnx` + `vocab.txt` that Phase 3 already bundles into
  `<App>.app/assets/` — no duplicate model in the bundle.

---

## Architecture & components

### A. `.NET 10` publisher — `core/DotnetNativeInterop.EdgeIndexPublisher/`

A new console project that **references `DotnetNativeInterop.Engine`** to reuse its proven, AOT-safe
`WordPieceTokenizer` and `OnnxTextEncoder` (FP32 all-MiniLM via `Microsoft.ML.OnnxRuntime`). The only new
code is the doc-pipeline glue:

1. **Read** `corpus/*.md` recursively.
2. **Parse YAML frontmatter** between leading `---` fences (a minimal hand-rolled reader; no heavy YAML
   dependency). Promote `document_id`, `title`, `error_codes` (list), `tools_required` (list).
3. **Chunk** the Markdown body by `##` headers — each `##` section → one chunk (section title + body).
4. **Embed** each chunk with the engine's `OnnxTextEncoder` → 384-d float32 (mean-pooled, L2-normalized).
5. **Write** SQLite via `Microsoft.Data.Sqlite` (schema below); embedding stored as a raw little-endian
   `float32[384]` BLOB; the remaining frontmatter serialized to a `Metadata` JSON column.

Output: `edge-index.db`. The publisher is run on the dev box (Windows, where .NET + the model live) when the
corpus changes; the resulting `edge-index.db` is **committed** as a bundled asset (keeps .NET off the Mac
build path). It is small (a handful of docs → tens of chunks).

**SQLite schema:**
```sql
CREATE TABLE Chunks (
  ChunkId       TEXT PRIMARY KEY,
  DocumentId    TEXT NOT NULL,
  SectionTitle  TEXT NOT NULL,
  ContentText   TEXT NOT NULL,
  Embedding     BLOB NOT NULL,   -- 384 × float32 = 1536 bytes, little-endian
  Metadata      TEXT NOT NULL    -- JSON: { "error_codes":[…], "tools_required":[…], … }
);
```

### B. Sample corpus — `core/DotnetNativeInterop.EdgeIndexPublisher/corpus/*.md`

~5 synthetic HVAC / enterprise-IT-hardware maintenance docs, each with `document_id`, `title`,
`error_codes`, `tools_required` frontmatter and several `##` sections. Demo content, authored for the POC.

### C. iOS edge client — `ios/Shared/EdgeSearch/`

- **`WordPieceTokenizer.swift`** — a Swift port of the engine's tokenizer ([CLS]/[SEP], `input_ids` +
  `attention_mask` + `token_type_ids`, greedy longest-match WordPiece, lower-case + accent strip). Must
  produce **identical** ids to the C# tokenizer (verified by the spike).
- **`EdgeSearchEngine.swift`** — loads ONNX Runtime from the xcframework, configures the **Core ML EP**,
  loads the bundled `model.onnx` + `vocab.txt`, opens `edge-index.db` via the **SQLite C API**, embeds the
  query, and cosine-ranks against the chunk BLOBs using **Accelerate/`vDSP`** (or SIMD). Runs off the main
  actor; careful C-pointer lifetime management for the BLOB reads.
- **`EdgeChunk.swift`** — result model: `ChunkId`, `documentId`, `sectionTitle`, `contentText`, `score`,
  decoded `errorCodes` / `toolsRequired` from `Metadata`.
- **`EdgeSearchViewModel.swift`** (`@MainActor`) — query text, active metadata filters, ranked results,
  busy/error state. Enforces the **0.70** minimum-similarity threshold.
- **`EdgeSearchView.swift`** — search bar; a metadata **filter** control built from the union of
  `error_codes` / `tools_required` across chunks (toggles narrow the searchable set); `ResultListView`
  showing each match's **SectionTitle** (highlighted), **ContentText**, and a **confidence %** from cosine.
- **`EdgeSearchHubView.swift`** — the tab root (`NavigationStack`, title "Edge Vector Search").

### D. App wiring (additive only)

- `ios/project.yml`: add the `Frameworks/onnxruntime.xcframework` dependency (embed + codesign, like
  `dni`); bundle `edge-index.db` as a resource; add the ORT C headers to the bridging header if needed.
- `ios/Shared/RootTabView.swift`: add the **"Manuals"** tab (`tabItem` icon e.g. `wrench.and.screwdriver`),
  bringing the app to **8 tabs**.
- `ios/Apps/Unified/UnifiedApp.swift`: construct the `EdgeSearchEngine` and pass it to `RootTabView`.
- `.gitattributes`: add an LFS rule for the vendored xcframework's large binaries (alongside the existing
  `*.onnx` rule).

---

## Data flow

- **Build time (Windows / .NET):** `corpus/*.md` → publisher (reuses engine tokenizer + encoder) →
  `edge-index.db` → committed + bundled.
- **Runtime (iOS, offline):** query → Swift tokenize → ORT + Core ML EP embed (384-d) → read chunk BLOBs
  from `edge-index.db` → vDSP cosine → drop matches < 0.70 → apply metadata filters → ranked list with
  section title, body, and confidence %.

---

## The spike gate (Task 1 — build nothing heavy until this passes)

A throwaway on-device test (on the Mac mini build host / a device) proving the two unknowns that decide
whether the full build is worth it:

1. **Linkage + EP:** the prebuilt `onnxruntime.xcframework` links into the app target and the **Core ML
   Execution Provider initializes** on device (full build, not the EP-stripped "mobile" package).
2. **Cross-runtime agreement:** for sample strings, the **Swift tokenizer's ids equal the C# tokenizer's**;
   and a Swift + Core-ML query vector **cosine-ranks a 2–3 row publisher-built `edge-index.db` correctly**
   (the obviously-related chunk first). Record the max absolute per-dimension delta between the
   Swift/Core-ML embedding and the .NET/CPU embedding of the same text — small drift is fine as long as
   ranking holds.

**Pass →** proceed to build the publisher, client, and tab.
**Fail →** write `docs/onnx-coreml-edge-findings.md` capturing the **exact** error + diagnosis (the attempt
stays in the repo as documented proof, per house practice), and the "Manuals" tab degrades to a clear
"Edge search engine unavailable" card (mirroring `AppleChatView`'s graceful-unavailable pattern). The
publisher + corpus + Swift scaffolding are kept but the tab is gated off.

Likely fail modes worth naming up front: the xcframework won't link or the EP is absent in the chosen
package; the Swift tokenizer can't match the C# one on a unicode/punctuation edge case (fixable, iterate);
or the cross-runtime vectors diverge enough to break ranking (a genuinely interesting portability finding —
document it).

---

## Error handling & concurrency

Swift 6 strict concurrency (`complete`): all ORT/SQLite work runs off the `MainActor` (`Task.detached`),
results marshalled back to the `@MainActor` view model — mirroring Phase 3's `SemanticSearchService`.
Graceful cards / empty states for: Core ML EP unavailable, `edge-index.db` missing or unreadable, and "no
matches ≥ 0.70". Empty/whitespace query is a no-op.

---

## Testing

- **Publisher probe:** a small harness verifying frontmatter parsing, `##` chunking, and a known cosine
  ranking over the sample corpus (the encoder itself is already trusted from Phase 3, so this exercises the
  new glue, not the model).
- **Spike gate:** as above — the make-or-break on-device check.
- **Screenshot test:** extend `ios/ScreenshotTests/ScreenshotTests.swift` to drive a query and capture the
  new tab (the suite auto-captures every screen).
- **On-device acceptance:** a query like "compressor won't start" surfaces the relevant HVAC section near
  the top; a metadata filter (e.g. an `error_code`) narrows results; weak matches are hidden by the 0.70
  threshold; and the other seven tabs (Dashboard, Features, Lab, Compare, Latency, AI, About) are
  unaffected.

---

## Naming (portfolio-grade — no `Poc.*`, no personal branding)

- .NET project: `DotnetNativeInterop.EdgeIndexPublisher`
- Swift module: `ios/Shared/EdgeSearch/`
- Bundled index: `edge-index.db`
- Tab label "Manuals"; screen title "Edge Vector Search".

---

## Out of scope / future

- **Android** parity (the engine already has a `build-android-so.sh`; EVS itself is iOS-only here).
- **INT8 quantization** of the model (the contrast holds with the shared FP32 model; INT8 is a size/edge
  enhancement that can come later, applied to both sides byte-identically).
- Aerospace / defense / S1000D — permanently excluded by domain choice.

## Self-review

- **Placeholder scan:** none — every section is concrete; the only deferred unknowns are explicitly behind
  the spike gate.
- **Consistency:** the SQLite schema, the BLOB layout (float32[384] LE), the 0.70 threshold, and the
  reused FP32 model are referenced identically across publisher, client, and data-flow sections. The Swift
  `EdgeChunk` fields map to the `Chunks` columns + `Metadata` JSON.
- **Scope:** one implementation plan's worth — a thin .NET publisher (reusing the engine), one Swift module,
  additive app wiring, gated by a single spike. No engine/ABI changes (EVS bypasses `dni.dylib`), so **no
  framework rebuild is required** — a notable simplification versus Phase 3.
- **Ambiguity:** model precision (FP32, reused), placement (tab), and asset strategy (committed `.db`,
  LFS-vendored xcframework) are all pinned.
