#!/usr/bin/env python
"""Measure the accuracy/size tradeoff of INT8-dynamic-quantized all-MiniLM-L6-v2 against the FP32
original. Build-time evidence harness for docs/int8-minilm-quant-findings.md -- not part of the app.

Usage (from this directory, after quantize.py has produced model.int8.onnx):

    .venv\\Scripts\\python.exe measure.py

Reads:
  - ../../core/DotnetNativeInterop.Engine/Ai/assets/model.onnx        (FP32)
  - ./model.int8.onnx                                                  (INT8, produced by quantize.py)
  - ../../core/DotnetNativeInterop.Engine/Ai/assets/vocab.txt          (shared tokenizer vocab)
  - ../../core/DotnetNativeInterop.Engine/Ai/assets/corpus.txt         (generic tech sentences, AI tab demo)
  - ../../core/DotnetNativeInterop.EdgeIndexPublisher/corpus/*.md      (EVS HVAC/IT maintenance docs)

Tokenization and pooling exactly mirror OnnxTextEncoder.cs / WordPieceTokenizer.cs (see wordpiece.py):
WordPiece uncased, max_len=64, [CLS]/[SEP]/[PAD], mean-pool over the attention mask, L2-normalize.
"""

import json
import re
import statistics
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort

from wordpiece import WordPieceTokenizer

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "core" / "DotnetNativeInterop.Engine" / "Ai" / "assets"
EVS_CORPUS_DIR = ROOT / "core" / "DotnetNativeInterop.EdgeIndexPublisher" / "corpus"
FP32_MODEL = ASSETS / "model.onnx"
INT8_MODEL = Path(__file__).resolve().parent / "model.int8.onnx"
VOCAB = ASSETS / "vocab.txt"
GENERIC_CORPUS = ASSETS / "corpus.txt"

MAX_LEN = 64


# ---------------------------------------------------------------------------
# EVS corpus loading (small port of Frontmatter.cs + MarkdownChunker.cs -- only
# the subset needed for these 5 fixed-shape docs).
# ---------------------------------------------------------------------------

def parse_frontmatter(md_text: str) -> tuple[str, str]:
    text = md_text.replace("\r\n", "\n").lstrip("\n")
    if not text.startswith("---\n"):
        return "", md_text
    end = text.index("\n---\n", 4)
    body = text[end + len("\n---\n"):].lstrip("\n")
    header = text[4:end]
    doc_id = ""
    for line in header.split("\n"):
        if line.startswith("document_id:"):
            doc_id = line.split(":", 1)[1].strip()
    return doc_id, body


def chunk_body(body: str) -> list[tuple[str, str]]:
    chunks: list[tuple[str, str]] = []
    title = None
    buf: list[str] = []
    for raw in body.replace("\r\n", "\n").split("\n"):
        if raw.startswith("## ") and not raw.startswith("### "):
            if title is not None:
                chunks.append((title, "\n".join(buf).strip()))
            title = raw[3:].strip()
            buf = []
        elif title is not None:
            buf.append(raw)
    if title is not None:
        chunks.append((title, "\n".join(buf).strip()))
    return chunks


def load_evs_chunks() -> list[dict]:
    """Returns list of {doc_id, section, text} in the same '{title}. {body}' shape Program.cs embeds."""
    rows = []
    for md_path in sorted(EVS_CORPUS_DIR.glob("*.md")):
        doc_id, body = parse_frontmatter(md_path.read_text(encoding="utf-8"))
        for section, content in chunk_body(body):
            rows.append({
                "doc_id": doc_id,
                "section": section,
                "text": f"{section}. {content}",
            })
    return rows


# ---------------------------------------------------------------------------
# ONNX encode, matching OnnxTextEncoder.cs
# ---------------------------------------------------------------------------

class Encoder:
    def __init__(self, model_path: Path, tokenizer: WordPieceTokenizer):
        self.session = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
        self.tokenizer = tokenizer
        self.output_name = self.session.get_outputs()[0].name

    def encode(self, text: str) -> np.ndarray:
        ids, mask = self.tokenizer.encode(text, MAX_LEN)
        ids_arr = np.array([ids], dtype=np.int64)
        mask_arr = np.array([mask], dtype=np.int64)
        type_arr = np.zeros_like(ids_arr)

        outputs = self.session.run(
            [self.output_name],
            {"input_ids": ids_arr, "attention_mask": mask_arr, "token_type_ids": type_arr},
        )
        hidden = outputs[0][0]  # [seq, 384]
        mask_f = mask_arr[0].astype(np.float32)
        count = mask_f.sum()
        pooled = (hidden * mask_f[:, None]).sum(axis=0)
        if count > 0:
            pooled = pooled / count
        norm = np.sqrt(np.dot(pooled, pooled))
        if norm > 0:
            pooled = pooled / norm
        return pooled.astype(np.float32)


def cosine(a: np.ndarray, b: np.ndarray) -> float:
    denom = np.linalg.norm(a) * np.linalg.norm(b)
    return float(np.dot(a, b) / denom) if denom > 0 else 0.0


def percentile(values: list[float], p: float) -> float:
    return float(np.percentile(np.array(values), p))


# ---------------------------------------------------------------------------
# Retrieval task: 5 paraphrased queries against the 15 EVS chunks (5 docs x 3 sections).
# Deliberately reworded vs. the corpus text so this is a real semantic-similarity test, not
# keyword overlap.
# ---------------------------------------------------------------------------

QUERIES = [
    {"query": "compressor won't start", "expected_doc": "hvac-001"},
    {"query": "ice building up on the evaporator coil", "expected_doc": "hvac-002"},
    {"query": "server won't power on, amber light on the PSU", "expected_doc": "it-001"},
    {"query": "server keeps shutting down because it's overheating", "expected_doc": "it-002"},
    {"query": "switch port won't come back up", "expected_doc": "it-003"},
]


def evaluate_retrieval(encoder: Encoder, chunks: list[dict]) -> dict:
    chunk_vecs = [encoder.encode(c["text"]) for c in chunks]
    reciprocal_ranks = []
    hits_at_1 = 0
    hits_at_5 = 0
    top1_chunks = []
    for q in QUERIES:
        qvec = encoder.encode(q["query"])
        scored = sorted(
            ((cosine(qvec, v), c) for v, c in zip(chunk_vecs, chunks)),
            key=lambda x: x[0],
            reverse=True,
        )
        ranked_docs = [c["doc_id"] for _, c in scored]
        top1_chunks.append(f"{scored[0][1]['doc_id']}#{scored[0][1]['section']}")
        rank = ranked_docs.index(q["expected_doc"]) + 1 if q["expected_doc"] in ranked_docs else None
        if rank == 1:
            hits_at_1 += 1
        if rank is not None and rank <= 5:
            hits_at_5 += 1
        reciprocal_ranks.append(1.0 / rank if rank else 0.0)

    n = len(QUERIES)
    return {
        "recall_at_1": hits_at_1 / n,
        "recall_at_5": hits_at_5 / n,
        "mrr": sum(reciprocal_ranks) / n,
        "top1_chunks": top1_chunks,
    }


def measure_latency(encoder: Encoder, texts: list[str], warmup: int = 5, iters: int = 3) -> float:
    for t in texts[:warmup]:
        encoder.encode(t)
    start = time.perf_counter()
    for _ in range(iters):
        for t in texts:
            encoder.encode(t)
    elapsed = time.perf_counter() - start
    return (elapsed / (iters * len(texts))) * 1000.0  # ms/encode


def main() -> None:
    vocab_lines = VOCAB.read_text(encoding="utf-8").splitlines()
    tokenizer = WordPieceTokenizer(vocab_lines)

    print(f"FP32 model: {FP32_MODEL} ({FP32_MODEL.stat().st_size:,} bytes)")
    print(f"INT8 model: {INT8_MODEL} ({INT8_MODEL.stat().st_size:,} bytes)")
    fp32_size = FP32_MODEL.stat().st_size
    int8_size = INT8_MODEL.stat().st_size
    print(f"Size ratio (FP32/INT8): {fp32_size / int8_size:.2f}x")
    print()

    fp32 = Encoder(FP32_MODEL, tokenizer)
    int8 = Encoder(INT8_MODEL, tokenizer)

    # --- Sample for fidelity: 30 generic tech sentences + 15 EVS chunks + 5 domain queries ---
    generic_texts = [ln for ln in GENERIC_CORPUS.read_text(encoding="utf-8").splitlines() if ln.strip()]
    evs_chunks = load_evs_chunks()
    evs_texts = [c["text"] for c in evs_chunks]
    query_texts = [q["query"] for q in QUERIES]
    sample_texts = generic_texts + evs_texts + query_texts
    print(f"Fidelity sample: {len(generic_texts)} generic + {len(evs_texts)} EVS chunks + "
          f"{len(query_texts)} queries = {len(sample_texts)} texts")

    cos_sims = []
    l2_drifts = []
    for text in sample_texts:
        v32 = fp32.encode(text)
        v8 = int8.encode(text)
        cos_sims.append(cosine(v32, v8))
        l2_drifts.append(float(np.linalg.norm(v32 - v8)))

    print()
    print("=== Embedding fidelity (FP32 vs INT8 cosine similarity) ===")
    print(f"mean:   {statistics.mean(cos_sims):.6f}")
    print(f"median: {statistics.median(cos_sims):.6f}")
    print(f"p95:    {percentile(cos_sims, 95):.6f}  (lower tail: min={min(cos_sims):.6f})")
    print(f"mean L2 drift: {statistics.mean(l2_drifts):.6f}")
    print()

    # --- Retrieval task ---
    print("=== Retrieval quality (5 paraphrased queries over 15 EVS chunks) ===")
    fp32_retrieval = evaluate_retrieval(fp32, evs_chunks)
    int8_retrieval = evaluate_retrieval(int8, evs_chunks)
    print(f"FP32: recall@1={fp32_retrieval['recall_at_1']:.2f} "
          f"recall@5={fp32_retrieval['recall_at_5']:.2f} mrr={fp32_retrieval['mrr']:.3f}")
    print(f"      top-1 chunks: {fp32_retrieval['top1_chunks']}")
    print(f"INT8: recall@1={int8_retrieval['recall_at_1']:.2f} "
          f"recall@5={int8_retrieval['recall_at_5']:.2f} mrr={int8_retrieval['mrr']:.3f}")
    print(f"      top-1 chunks: {int8_retrieval['top1_chunks']}")
    ranking_changed = fp32_retrieval["top1_chunks"] != int8_retrieval["top1_chunks"]
    print(f"Top-1 ranking changed by quantization: {ranking_changed}")
    print()

    # --- Latency (best-effort, single-threaded CPU, not a rigorous benchmark) ---
    print("=== Latency (mean ms/encode, CPU, 3 iters over the fidelity sample after 5-item warmup) ===")
    fp32_ms = measure_latency(fp32, sample_texts)
    int8_ms = measure_latency(int8, sample_texts)
    print(f"FP32: {fp32_ms:.3f} ms/encode")
    print(f"INT8: {int8_ms:.3f} ms/encode  ({fp32_ms / int8_ms:.2f}x vs FP32)")

    results = {
        "fp32_size_bytes": fp32_size,
        "int8_size_bytes": int8_size,
        "size_ratio": fp32_size / int8_size,
        "cosine_mean": statistics.mean(cos_sims),
        "cosine_median": statistics.median(cos_sims),
        "cosine_p95": percentile(cos_sims, 95),
        "cosine_min": min(cos_sims),
        "l2_drift_mean": statistics.mean(l2_drifts),
        "sample_size": len(sample_texts),
        "fp32_retrieval": fp32_retrieval,
        "int8_retrieval": int8_retrieval,
        "ranking_changed": ranking_changed,
        "fp32_latency_ms": fp32_ms,
        "int8_latency_ms": int8_ms,
    }
    out_path = Path(__file__).resolve().parent / "results.json"
    out_path.write_text(json.dumps(results, indent=2), encoding="utf-8")
    print()
    print(f"Wrote {out_path}")


if __name__ == "__main__":
    main()
