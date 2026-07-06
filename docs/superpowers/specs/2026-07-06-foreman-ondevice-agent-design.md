# Foreman — On-Device Tool-Calling Agent — Design

**Date:** 2026-07-06
**Status:** Design approved (brainstorm). Not yet implemented.
**Author:** brainstorm session
**Depends on:** `feat/meai-ichatclient` (`DniChatClient : IChatClient`, the M.E.AI adapter) merged.
**Branches (proposed):** `feat/foreman-native` → `feat/foreman-android` / `feat/foreman-ios`.

## Context

The POC hosts a NativeAOT .NET engine (`dni`) inside native iOS/Android apps over four
interop transports, with on-device AI (ONNX semantic search, Edge Vector Search, llama.cpp
RAG) and — new in Wave B — in-process `ActivitySource` tracing surfaced as a per-request
waterfall. What it does **not** have is an agent: an on-device LLM that *acts*, driving real
native operations rather than only emitting prose.

**Foreman** is that agent. The name fits the domain (industrial-maintenance manuals — a
foreman directs the work and calls the right tool). Its job is deliberately **hybrid**:

1. A genuinely useful **grounded assistant** (RAG over the manuals + tools), proving .NET can
   host a real agent loop end-to-end on-device, no cloud.
2. An **interop showcase**: every tool call is an engine operation that already emits a trace
   span, so the agent's reasoning steps appear as real, timed native operations in the
   Wave B Trace waterfall. You *watch* the LLM cross the boundary to do work.

## The central risk, and the answer

A ~1B on-device model (Llama-3.2-1B, the shipped GGUF) is unreliable at raw tool-calling —
it emits malformed or hallucinated tool calls often enough to violate the repo's hard
"never looks broken" rule. Two design decisions neutralize this:

1. **GBNF grammar-constrained decoding.** On the llama path, tool-call turns are generated
   under a llama.cpp GBNF grammar built from the tool schemas, so the model **cannot** emit a
   structurally invalid call — the grammar restricts next-token selection to valid tool
   syntax. A wrong tool *choice* is still possible but is **surfaced honestly** in the trace,
   never hidden. (Requires adding grammar sampling to the llama.cpp shim — see Native work.)
2. **Hand-rolled loop, not `FunctionInvokingChatClient`.** M.E.AI's auto-invoker uses
   reflection-based JSON for tool args and was **not** proven AOT-safe by the MeaiGate spike
   (flagged by the adapter task). Because the grammar already yields a valid, parseable tool
   call, Foreman **hand-rolls** a small AOT-safe agent loop over `DniChatClient` — parsing the
   constrained output itself, dispatching to plain delegates, source-gen JSON throughout. This
   sidesteps the reflection gate entirely and gives full control of the trace spans.

## Architecture — engine-hosted, write-once

The agent loop lives in the **.NET engine** (parity-clean: both platforms reach it through the
existing transports; no per-platform orchestration). Layers:

- `DniChatClient : IChatClient` (already built) — plain streaming completion over `ILanguageModel`.
- `Ai/Agent/ForemanAgent` (new) — the hand-rolled loop: builds the GBNF grammar from the tool
  set, calls `DniChatClient` for each turn, parses the tool call, dispatches, feeds the result
  back, streams the final answer.
- `Ai/Agent/ForemanTools` (new) — three `ToolDefinition`s (name, JSON-schema args, delegate),
  each delegate calling an existing engine op that already emits its own span.

**Two interchangeable brains behind the same tool set + turn contract:**

| Condition | Brain | Honesty badge |
|---|---|---|
| GGUF present (iOS now; Android after neural-RAG bundling) | `LlamaLanguageModel` + GBNF grammar | `on-device LLM (Llama-3.2-1B, grammar-constrained)` |
| No GGUF (Android today, any fallback) | deterministic router (embedding-similarity query→tool) + `ExtractiveLanguageModel` prose | `scripted routing — no on-device LLM present` |

Same tools, same UI, same trace; only the brain differs and the badge says which. The router
path reuses shipped pieces (`SemanticSearch`, `ExtractiveLanguageModel`), needs no llama, and
is therefore **fully Windows-verifiable now**.

## Tools (the grammar's vocabulary)

| Tool | Args | Engine op (existing) | Span |
|---|---|---|---|
| `search_manuals` | `query: string` | `SemanticSearch` "manuals" + RAG retrieval | `agent.tool.search_manuals` |
| `run_feature` | `id: string` | `dni_feature_run` path | `agent.tool.run_feature` |
| `engine_stats` | *(none)* | `EngineTelemetry.Snapshot` | `agent.tool.engine_stats` |

Three only (YAGNI — `run_benchmark` explicitly dropped). Each delegate is a thin call into an
op that already exists and already traces.

## The agent turn (loop)

1. User query → `agent.turn` span opens; the query becomes the seed prompt.
2. Model turn (grammar-constrained on the llama path; router on the fallback path) decides:
   emit a **tool call** or a **final answer**.
3. Tool call → parse (guaranteed valid) → dispatch to the tool delegate → `agent.tool.<name>`
   span → result appended to the running context.
4. Loop to step 2 until a final answer or the **max-steps cap (5)**. Hitting the cap yields an
   honest "stopped at step cap" turn result — never a silent hang or loop.
5. Final grounded answer streams to the client over the selected transport (Foreman is just
   another streaming engine capability behind the existing seam).

## Boundary / Trace tie-in (the hybrid's second half)

- New span prefix `agent.` (`agent.turn`, `agent.tool.*`) added to the client-side trace
  coloring/filtering (both platforms). The tool-call steps show in the **Wave B Trace
  waterfall** as real, timed native operations correlated by the turn's request id.
- The Foreman view shows a per-turn **tool-call strip** (tool · args · result · µs) that
  expands into the full waterfall — the same "show the machinery" honesty as the Boundary
  screen.

## UX surface (parity, both platforms)

A new **top-level "Foreman" section** (its own nav entry, not folded into Search). Chat-style
turns + the inline tool-call strip + the honest backend badge. Empty/loading/error states per
the repo's no-silent-blank rule. iOS build Mac-gated as usual; Android buildable now.

## Exposure / ABI

Foreman is invoked as a streaming engine capability. Decision deferred to the plan, in order of
preference:
1. **Reuse the existing RAG/stream path** with an "agent" mode flag riding the command grammar
   → **zero ABI change** (preferred if it fits cleanly).
2. Otherwise **one** new batched export `dni_agent_session_start(prompt, cb, ud)` mirroring
   `dni_rag_session_start`. At most one export; the frozen surface is otherwise untouched.

## Verification scope

- **Windows-buildable/testable now (no device, no Mac):** the entire managed loop —
  `ForemanAgent` + the three tools + the deterministic-router fallback + the turn contract +
  the max-steps cap + the `agent.*` trace spans + the **GBNF grammar-string generation** — all
  against `MockLanguageModel` and the extractive router. Harness/tests assert: tool dispatch,
  the loop, the step cap, span emission, router tool-selection, and that the generated grammar
  matches the tool schemas.
- **Device-gated (needs the shim change + a GGUF):** real llama + GBNF sampling, and the
  on-device Foreman UX on a physical device / emulator.

## Native work required (flagged honestly)

- **llama.cpp shim:** add GBNF grammar sampling to the generate path (the current shim is a
  thin 3-function generate shim; grammar sampling configures the sampler with a parsed GBNF).
  Small but device-gated — gets its own spike gate + findings doc per repo DNA before the
  llama path is claimed working.
- At most one new ABI export (see Exposure).

## Gates required before/within implementation

1. **Grammar-constrained llama shim gate** (device): prove llama.cpp GBNF sampling produces
   valid constrained tool calls from Llama-3.2-1B. Findings doc either way; if it fails, the
   llama path is documented-deferred and Foreman ships router-only (still a complete, honest
   demo). *No managed gate is needed* — dropping `FunctionInvokingChatClient` removed the
   reflection-JSON AOT question.

## Non-goals

- Multi-turn conversation memory (separate rec — Foreman turns are single-shot for now).
- Tools beyond the three (`run_benchmark` dropped).
- Cloud anything; Metal/GPU offload; new model training/fine-tuning.
- Any change to the frozen production FFI exports beyond at most the one additive agent export.
- Using M.E.AI's `FunctionInvokingChatClient` / any reflection-based tool invocation.

## Risks / decisions

- **1B tool-calling reliability** — answered by GBNF (structure) + honest wrong-choice
  disclosure + the router fallback (never-broken floor). Decided.
- **Grammar sampling in the shim is device-gated** — the managed loop + grammar-string
  generation are proven on Windows first; the shim gate is the only device dependency for the
  llama path, and router-only is a complete fallback if it slips.
- **Parity token:** Foreman is a new top-level section — both platforms must add the same nav
  entry, section order, and `agent.` span-prefix color. iOS is Mac-gated (tracked with the
  broader iOS Plan B parity debt).
- **Android GGUF** — the llama path on Android depends on the neural-RAG GGUF-bundling rec
  (separate Wave C item); until then Android Foreman is router-only, disclosed. iOS already
  bundles the GGUF.
