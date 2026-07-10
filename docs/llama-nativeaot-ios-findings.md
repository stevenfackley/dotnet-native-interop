# Running llama.cpp inside a NativeAOT image — what worked

**Date:** 2026-06-06
**Outcome:** ✅ **Works.** A real GGUF model loads and decodes token-by-token from inside a .NET 10
**NativeAOT** image, via a thin C shim over llama.cpp statically linked with `DirectPInvoke` +
`NativeLibrary`. This is the on-device generation path behind the "Ask the Manuals" RAG feature
(Phase 5, Plan B) — native-link **gate #3**, after SQLCipher and ONNX Runtime.

This is the third "link a hostile C/C++ static lib into a NativeAOT image" integration in the repo,
and the pattern is now routine:

| | SQLCipher | ONNX Runtime | **llama.cpp (this doc)** |
|---|---|---|---|
| What | encrypted SQLite | all-MiniLM encoder | **on-device LLM generation** |
| Shim | none (C API) | Obj-C `EvsOrt` (Swift side) | **C `dni_llama` (3 funcs)** |
| Link | `DirectPInvoke` + `.a` | `DirectPInvoke` + `.a` + `-lc++` | **`DirectPInvoke` + `.a`s + `-lc++`** |

## The gate signal (host AOT probe)

Before touching iOS, the make-or-break unknown — *does llama.cpp link and run under NativeAOT at
all?* — was proven on the **host** with a `osx-arm64` `PublishAot=true` console:

```
Hello, it's a pleasure to meet you. How can I assist you today?
PASS: llama AOT decode
```

(Qwen2.5-0.5B-Instruct Q4 as a throwaway probe model; ~300 MiB CPU compute buffer.) Once that
passed, the iOS path was mechanical.

## The recipe

1. **Thin C shim** (`native/llama-shim/dni_llama.{h,cpp}`) exposing three functions —
   `dni_llama_load(path) → handle`, `dni_llama_generate(handle, prompt, max_tokens, temp, cb, user_data)`,
   `dni_llama_free(handle)` — wrapping llama.cpp's C API (load model+context, tokenize, sampler chain,
   decode loop, `llama_token_to_piece`, emit each piece via the callback). Hiding the (churn-prone)
   llama.cpp API behind three stable functions keeps the C# P/Invoke surface tiny — the same move as
   `EvsOrt.m` wrapping the ONNX C API.

2. **Pinned tag:** llama.cpp **`b9542`**. The shim's modern-API calls
   (`llama_model_load_from_file`, `llama_init_from_model`, `llama_get_memory` / `llama_memory_clear`,
   the `llama_sampler_chain_*` API, `llama_vocab_is_eog`) compiled against this tag with **zero
   changes** — the API has been stable since late 2024.

3. **Build static, CPU-only** (`native/llama-shim/build-llama.sh` + `CMakeLists.txt`):
   `-DGGML_METAL=OFF -DGGML_BLAS=OFF -DGGML_ACCELERATE=OFF -DLLAMA_CURL=OFF` → only
   `libdni_llama.a`, `libllama.a`, `libggml.a`, `libggml-cpu.a`, `libggml-base.a`. CPU-only avoids
   linking the Metal/Accelerate frameworks (and the metallib) into the image — a deliberate
   simplification; Metal offload is a possible later stretch.

4. **Hand-link into the NativeAOT image** (`DotnetNativeInterop.NativeBridge.csproj`, `ios-arm64`):
   `<DirectPInvoke Include="dni_llama" />`, a `<NativeLibrary>` per `.a`, and `<LinkerArg Include="-lc++" />`
   (llama.cpp is C++). Exactly the ONNX/SQLCipher pattern.

5. **`LlamaLanguageModel : ILanguageModel`** (`core/.../Llama/`) loads the GGUF once and bridges the
   native per-piece callback into an `IAsyncEnumerable<string>` through a bounded `Channel`
   (the `[UnmanagedCallersOnly]` callback recovers the channel writer from a key passed as `user_data`;
   the unsafe pointer work is isolated in a trampoline because async iterators can't contain unsafe code).

6. **The swap** (`EngineHost`): `_ragOrchestrator` uses `RagLanguageModel(new LlamaLanguageModel(gguf), …)`
   when the GGUF is bundled, else falls back to the managed `ExtractiveLanguageModel`. That one line is
   the entire integration seam the README promised — every transport and the UI stream real LLM tokens
   unchanged.

## The model

**Llama-3.2-1B-Instruct, Q4_K_M GGUF (~0.77 GB)**, bundled into the app under `Ai/assets/` (the same
folder reference that carries `model.onnx`). `*.gguf` is Git-LFS-tracked; because of its size the model
file itself is fetched at build time rather than committed (a fresh checkout without it degrades to the
extractive generator — the graceful fallback).

**Provenance is pinned** to the exact upstream file Android downloads at first run
(`huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF`, `Q4_K_M`, **807694464 bytes**) so both platforms
generate byte-identically. Fetch it onto the build host with **`build/fetch-ios-gguf.sh`** (idempotent,
size-verified) before the app build — the framework build does not bundle the model. That script's
`GGUF_URL` is kept identical to `android/app/build.gradle.kts`'s `GGUF_URL`; the engine resolves the model
by exact name (`EngineHost.BuildRagModel` → `Llama-3.2-1B-Instruct-Q4_K_M.gguf`), which is also the
upstream file's name, so no rename is needed.

## Caveats / notes

- **CPU only.** No Metal/ANE offload for generation (the EVS encoder still uses Core ML separately).
  A 1B Q4 model is responsive enough on a modern iPad; first-token latency includes a one-time model
  load on the first query.
- **Build host needs CMake.** Installed user-local on the Mac mini (no sudo), like the JDK — see
  `docs/ios-build-deploy-runbook.md`. The Windows dev box has no C/C++ toolchain, so the gate spike +
  iOS llama build run on the Mac.
- Contrast with the in-engine ONNX path (`onnx-nativeaot-ios-findings.md`) and the Swift Core ML path
  (`onnx-coreml-edge-findings.md`): all three now coexist in one app.

## Reproduce

`native/llama-shim/build-llama.sh host` (gate probe libs) / `… ios-arm64` (device libs); the
`build/build-ios-framework-device.sh` framework build static-links them via the `.csproj`.
