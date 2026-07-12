# Foreman on-device grammar-constrained sampling — findings

**Status: DONE (2026-07-11, PR #69).** Foreman's on-device llama brain now enforces its GBNF grammar
as a *hard sampler constraint*, not a prompt hint. This was the last "device-gated" item from the
Foreman design (`docs/superpowers/specs/2026-07-06-foreman-ondevice-agent-design.md`, "Plan B").

## What changed

`GbnfGrammar.Build(tools)` already produced a grammar restricting a decision turn to a valid tool call
or a final answer. Previously that grammar was appended to the prompt as "informational only" — the
shim sampled unconstrained, and `ToolCallParser`'s "malformed JSON → answer, never throw" fallback did
all the safety work. Now the grammar is applied at the llama sampler, so a token that would break the
JSON is masked to `-inf` before it can ever be chosen.

The seam, end to end:

```
ForemanHost.BuildGrammarBrain
  → ChatOptions.AdditionalProperties[DniChatClient.GrammarPropertyKey = "dni.grammar"]
  → DniChatClient.BuildRequest → InferenceRequest.Grammar
  → LlamaLanguageModel.GenerateAsync → dni_llama_generate(..., grammar, ...)
  → llama_sampler_init_grammar(vocab, grammar, "root")  (prepended to the sampler chain)
```

Only the **decision turn** is constrained; the free-form answer turn passes no grammar (prose streams
unconstrained). Non-llama backends (Mock/Extractive/Rag) ignore `InferenceRequest.Grammar` — it is a
decode constraint, not a prompt. A malformed grammar makes `dni_llama_generate` return `-5`.

## Native gate (the decisive proof)

`native/llama-shim/grammar_gate.cpp` links the rebuilt shim against the pinned **b9542** static libs and
runs a real CPU decode of a real GGUF (the gate model on the Mac build host is `qwen2.5-0.5b-instruct`;
the shipped device model is Llama-3.2-1B, but the grammar sampler is model-agnostic). Greedy decode
(temp 0) makes it reproducible. Recorded result:

```
== dni_llama grammar-sampling gate ==
control (grammar=NULL): "Paris. It is the largest city in Europe and the second"
[PASS] control: unconstrained decode yields free text, not the forced shape
forced (synthetic grammar): "DNI-CHI-OK"
[PASS] synthetic: output is EXACTLY DNI-[A-Z]{3}-OK -> sampler is grammar-constrained
answer (shipped GBNF): "{"answer":"} Check and maintain your HVAC system regularly to ensure it operates efficiently and safely."}"
[PASS] shipped answer grammar: output is a well-formed {"answer":"..."} object
[PASS] malformed grammar returns a negative error (rc=-5), emits nothing
== 4/4 grammar-gate checks passed ==
```

The decisive line: prompted **"The capital of France is"**, the unconstrained control says *"Paris…"*,
but the **same model under a synthetic grammar** emits exactly `DNI-CHI-OK` — a shape it would never
produce for that prompt. Conformance can only come from the sampler. (The shipped-GBNF answer even
starts its content with a `}` character, which is legal *inside* a JSON string — the grammar still
forced a well-formed object around it.)

Build + run command is in the header of `grammar_gate.cpp`.

## Managed gate (runs with no GGUF, CI-buildable)

`spike/DniChatClientHarness` proves the seam without a model: a GBNF string in
`ChatOptions.AdditionalProperties["dni.grammar"]` threads to `InferenceRequest.Grammar` verbatim, stays
`null` when absent, and a non-string value is ignored (never crashes the decode). **19/19.**
`spike/ForemanHarness` **94/94** (the router-fallback path is unchanged).

## ABI + CI note

`dni_llama_generate` went from 6 to 7 args, so the mobile shims **must** be rebuilt in lockstep with the
P/Invoke (both CI jobs rebuild the shim from source). `ci-ios`'s `paths` filter was widened to `core/**`
+ `native/llama-shim/**` (matching `ci-android`) so this change actually triggers the iOS framework +
simulator build/link check — previously it would not have, a latent gap where any engine change skipped
iOS CI.

**iOS simulator caveat:** the sim has no bundled GGUF, so Foreman there runs the deterministic router
brain — the grammar path is exercised on a real device or via the native gate above, not on the sim.
The Foreman llama badge now reads honestly: `on-device LLM (Llama-3.2-1B, grammar-constrained)`.
