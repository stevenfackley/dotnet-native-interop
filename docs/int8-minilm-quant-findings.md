# INT8 dynamic quantization of all-MiniLM-L6-v2 — size/accuracy tradeoff

**Date:** 2026-07-06
**Status:** ✅ **PASSED** — INT8 dynamic quantization is safe to ship. ~4x smaller, and it did not flip a
single retrieval decision on the EVS corpus.
**Why this doc exists:** `model.onnx` ([all-MiniLM-L6-v2][minilm], FP32) is ~86 MB and ships via Git LFS **twice** in
this repo's footprint — embedded in the NativeAOT engine's `dni.dylib` (Phase 3 AI tab,
[`docs/onnx-nativeaot-ios-findings.md`](onnx-nativeaot-ios-findings.md)) and as the build-time embedding
model for the EVS index publisher ([`docs/onnx-coreml-edge-findings.md`](onnx-coreml-edge-findings.md)).
Before spending effort wiring a quantized model into either path, this gate quantifies whether INT8 dynamic
quantization holds embedding quality well enough to be worth it. **This is evidence + docs only — no app
code changed.**

## TL;DR

- **Size:** 90,405,214 → 22,903,734 bytes — **3.95x smaller** (≈86.2 MiB → ≈21.8 MiB).
- **Fidelity:** mean cosine similarity (FP32 vs INT8 embeddings) **0.9630**, median 0.9651, p95 0.9747, worst
  case in-sample 0.9397 — across 50 varied sentences (30 generic tech + 15 EVS chunks + 5 domain queries).
- **Retrieval — the number that matters:** recall@1 = recall@5 = 1.00, MRR = 1.000 for **both** FP32 and
  INT8 on a 5-query/15-chunk EVS ranking task, and the **top-1 chunk-id sequence is byte-identical** between
  the two models. Quantization did not reorder a single result.
- **Latency** (CPU, single-threaded, informal): 6.458 ms/encode FP32 → 4.896 ms/encode INT8 (1.32x).
- **Verdict: ship INT8** for EVS. Wiring it in is a follow-up task, not done here.

## The quantization

Dynamic quantization needs no calibration dataset — weights are converted ahead of time (build-time,
Python), activations are quantized on the fly per-inference (runtime, inside ONNX Runtime's existing C++
kernels). That's why this is a good fit for a small offline gate: no representative-input calibration
harness required, unlike *static* quantization.

```powershell
uv venv --python 3.12 .venv
uv pip install --python .venv onnxruntime==1.27.0 onnx==1.22.0 numpy==2.5.1
.venv\Scripts\python.exe quantize.py <path-to>\model.onnx model.int8.onnx
```

`tools/int8-quant/quantize.py` is a ~30-line wrapper around
`onnxruntime.quantization.quantize_dynamic(..., weight_type=QuantType.QInt8)`. Output:

```
quantizing ..\..\core\DotnetNativeInterop.Engine\Ai\assets\model.onnx (90,405,214 bytes) -> .\model.int8.onnx
done: 22,903,734 bytes (3.95x smaller)
```

A one-time warning fires: `Please consider to run pre-processing before quantization` (ORT suggests running
symbolic shape inference first, mainly a static-quantization recommendation). Quantization completed and
produced a working model without it — treat pre-processing as a possible future refinement, not a blocker.

**Graph diff** (op-type histogram via `onnx.load` + counting `node.op_type`):

| op | FP32 | INT8 |
|---|---|---|
| `MatMul` | 48 | 12 |
| `MatMulInteger` | 0 | 36 |
| `DynamicQuantizeLinear` | 0 | 24 |
| `DequantizeLinear` | 0 | 3 |
| `Cast` | 15 | 51 |
| `Mul` | 38 | 110 |
| **total nodes** | **780** | **915** |

36 of 48 `MatMul`s (the transformer's dense/attention projections — the bulk of the parameter weight) became
`MatMulInteger`; the rest stayed FP32 (small or bias-adjacent ops ORT judged not worth quantizing). Node
count grows ~17% from inserted quant/dequant scaffolding, but that's graph complexity, not weight size — it's
already reflected in the 22.9 MB output.

## Measurement harness

`tools/int8-quant/wordpiece.py` is a byte-for-byte Python port of
[`WordPieceTokenizer.cs`](../core/DotnetNativeInterop.Engine/Ai/WordPieceTokenizer.cs) (uncased BERT
WordPiece: lower-case, NFD-normalize, strip accents, punctuation peeled into its own token, greedy
longest-match subwords, `[CLS]`/`[SEP]`/`[PAD]`, `max_len=64`) — kept in lockstep with the .NET tokenizer so
the harness measures the quantization tradeoff, not an accidental tokenizer discrepancy.
`tools/int8-quant/measure.py` mirrors [`OnnxTextEncoder.cs`](../core/DotnetNativeInterop.Engine/Ai/OnnxTextEncoder.cs)'s
encode path exactly: run the session (`input_ids`/`attention_mask`/`token_type_ids` int64 →
`last_hidden_state` float32), mean-pool over the attention mask, L2-normalize.

**Sample** (50 texts, no cherry-picking):
- 30 generic tech sentences from `core/DotnetNativeInterop.Engine/Ai/assets/corpus.txt` (the AI-tab semantic
  search demo corpus — .NET/Swift/ONNX vocabulary)
- 15 chunks from the real EVS corpus (`core/DotnetNativeInterop.EdgeIndexPublisher/corpus/*.md` — 5 HVAC/IT
  maintenance docs × 3 sections each, chunked with the same rule as `MarkdownChunker.cs`)
- 5 hand-written queries paraphrased against those docs (not copy-pasted from the corpus text), used for the
  retrieval task below

### Embedding fidelity (FP32 vs INT8 cosine similarity, n=50)

| metric | value |
|---|---|
| mean | 0.9630 |
| median | 0.9651 |
| p95 | 0.9747 |
| min (worst in sample) | 0.9397 |
| mean L2 drift | 0.2697 |

(Both embeddings are L2-normalized unit vectors, so cosine 0.96 ↔ L2 drift ≈0.28 is the expected geometric
relationship for that angle — not two independent signals disagreeing.)

### Retrieval quality

5 queries, each written to match one of 5 documents (3 chunks/doc = 15 candidates), ranked by cosine
similarity within same-precision embeddings — query and index both FP32, or both INT8 — mirroring how EVS
actually deploys this (one model embeds both the corpus and the query):

| query | expected doc | FP32 top-1 chunk | INT8 top-1 chunk |
|---|---|---|---|
| "compressor won't start" | hvac-001 | hvac-001#Fix | hvac-001#Fix |
| "ice building up on the evaporator coil" | hvac-002 | hvac-002#Symptom | hvac-002#Symptom |
| "server won't power on, amber light on the PSU" | it-001 | it-001#Symptom | it-001#Symptom |
| "server keeps shutting down because it's overheating" | it-002 | it-002#Symptom | it-002#Symptom |
| "switch port won't come back up" | it-003 | it-003#Symptom | it-003#Symptom |

FP32: recall@1 = 1.00, recall@5 = 1.00, MRR = 1.000
INT8: recall@1 = 1.00, recall@5 = 1.00, MRR = 1.000

**Top-1 ranking changed by quantization: No** — the ranked chunk-id sequence is identical between models.
With only 5 queries this is a small-sample result (see Limits below), but it directly answers the question
that matters: on this corpus, quantization never flips a ranking decision.

### Latency (informal)

Single-threaded CPU, 3 iterations over the 50-text sample after a 5-item warmup (`time.perf_counter` — not a
rigorous benchmark: no thread pinning, no isolation from other processes on this box):

| | FP32 | INT8 |
|---|---|---|
| ms/encode | 6.458 | 4.896 |
| speedup | 1x | 1.32x |

A modest win, not the headline result — MiniLM-L6 at `max_len=64` is already fast on CPU, so INT8's main
lever here is **size**, not **latency**. (Static/QOperator quantization with real calibration data might do
better on latency; not attempted here — out of scope for this gate.)

## Verdict

**Ship INT8** for EVS: build-time index publishing (embed the corpus once, offline) and on-device query
encoding at search time. On this corpus:

- Fidelity loss is small and consistent (mean cosine 0.963), never large enough to flip a top-1 retrieval
  decision in 5/5 queries.
- 3.95x smaller model (86.2 MB → 21.8 MB) meaningfully shrinks whatever ships it — directly addresses the
  "~90 MB model shipped via LFS" pressure both ONNX findings docs flag.
- No retrieval regression, no ranking reorder, in the only measurement that maps to a real user-facing
  outcome (does search return the right document).

**Caveat:** this is a 5-query, 15-chunk gate on synthetic domain data — a coarse instrument, not a
statistically powered eval. It's strong enough to justify wiring INT8 into EVS next, but re-run this harness
(or an app-level agreement test in the style of `EdgeSearchAgreementTests`) once the real corpus is bigger,
before fully trusting the number in production.

## Implications / next steps (NOT done here — follow-up work)

- Wire `model.int8.onnx` into the EVS publisher (`DotnetNativeInterop.EdgeIndexPublisher`) as the embedding
  model; rebuild `edge-index.db` with INT8-derived vectors.
- Decide separately whether `OnnxTextEncoder.cs` (in-engine, Phase 3 AI tab) also switches to INT8, or stays
  FP32 — this gate measured only the CPU execution-provider path shared by both, and never re-ran the iOS
  Core ML/ANE cross-runtime agreement check from `docs/onnx-coreml-edge-findings.md`. Core ML behavior on
  INT8 weights (vs. the FP32 "agrees to float epsilon" result already proven) is **unverified**.
- **Mobile-RID caveat:** this gate never touched NativeAOT publish or platform linking. If INT8 gets wired
  into the in-engine path, it rides the exact same `ios-arm64` / `android-arm64` static-link recipe already
  documented in `docs/onnx-nativeaot-ios-findings.md` (`DirectPInvoke` + `NativeLibrary` against the vendored
  `onnxruntime.xcframework`) — only the model **asset** changes, not the link wiring. Don't assume the
  RID-specific plumbing needs touching too.
- INT8 artifact location: `tools/int8-quant/model.int8.onnx` — **gitignored**, not committed (see
  `.gitignore`: `tools/int8-quant/*.onnx`). Regenerate with the command below whenever `model.onnx` changes.

## Reproduce

```powershell
cd tools/int8-quant
uv venv --python 3.12 .venv
uv pip install --python .venv -r requirements.txt
.venv\Scripts\python.exe quantize.py ../../core/DotnetNativeInterop.Engine/Ai/assets/model.onnx model.int8.onnx
.venv\Scripts\python.exe measure.py
```

Exact versions: Python 3.12.12, onnxruntime 1.27.0, onnx 1.22.0, numpy 2.5.1, uv 0.10.6. Full metrics also
land in `tools/int8-quant/results.json` (gitignored, regenerated by `measure.py`).

[minilm]: https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2
