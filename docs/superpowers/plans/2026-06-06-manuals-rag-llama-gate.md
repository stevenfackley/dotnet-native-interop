# Ask the Manuals — llama.cpp Generator Gate (Phase 5, Plan B) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the managed extractive RAG generator (Plan A, shipped) with a real on-device LLM — Llama-3.2-1B-Instruct (GGUF Q4_K_M) via a thin C shim over llama.cpp, statically linked into the `dni` NativeAOT image — behind the existing `ILanguageModel` seam, so every RAG transport and the "Ask the Manuals" UI stream real neural answers with no other change.

**Architecture:** A `RagLanguageModel(ILanguageModel inner, SemanticSearch search)` retrieves the manuals top-K, builds a grounded prompt (human-authored `RagPrompt.Build`), and delegates to a raw text generator. The raw generator is `LlamaLanguageModel`, which P/Invokes a tiny C shim (`dni_llama_load`/`dni_llama_generate`/`dni_llama_free`) that wraps llama.cpp's C API and emits detokenized pieces via a callback. `EngineHost`'s RAG orchestrator switches from `new ExtractiveLanguageModel()` to `new RagLanguageModel(new LlamaLanguageModel(...), SemanticSearch.Default)`. The shim + llama.cpp are static-linked exactly like ONNX Runtime / SQLCipher (native-link **gate #3**).

**Gate:** This is spike-gated (Windows AOT probe first, then iOS link). If the link is intractable, the llama attempt stays in the repo (excluded from build, like the gRPC reference), `docs/llama-nativeaot-ios-findings.md` records the exact failure, and `EngineHost` keeps Plan A's `ExtractiveLanguageModel` — the feature still ships.

**Tech Stack:** .NET 10 / C# 14 NativeAOT, `DirectPInvoke` + `NativeLibrary` static linking, llama.cpp (C API, pinned tag) + a C++ shim built with CMake, Git LFS (GGUF), Swift/SwiftUI (label only), xcodegen, Mac mini build host.

**Build/test note:** Milestone 1 (managed RAG composition) is fully Windows-testable with a stub generator (no llama) via the throwaway probe at `%TEMP%\dni-rag-probe`. Milestone 2 (the gate) needs a built llama.cpp + shim static lib + a small GGUF. Milestones 3–4 build on the Mac mini — **note the host is currently not LFS-ready** (see `docs/ios-build-deploy-runbook.md` and the build-host memory): the GGUF + existing LFS binaries must reach the Mac as real bytes, not `git archive` pointers.

---

## File Structure

**Engine (`core/DotnetNativeInterop.Engine/`)** — managed, Windows-testable
- Create `Ai/RagPrompt.cs` — grounded-prompt builder (the human-authored piece).
- Create `RagLanguageModel.cs` — `ILanguageModel`: retrieve → prompt → delegate → stream.
- Create `Llama/LlamaNative.cs` — `DirectPInvoke`/`DllImport` declarations for the shim.
- Create `Llama/LlamaLanguageModel.cs` — `ILanguageModel`: P/Invoke the shim; callback→`Channel<string>` bridge.
- Modify `DotnetNativeInterop.Engine.csproj` — copy the GGUF to output (under `Ai/assets/`).

**NativeBridge (`core/DotnetNativeInterop.NativeBridge/`)**
- Modify `EngineHost.cs` — swap the RAG generator to `RagLanguageModel(LlamaLanguageModel, Default)` (gate-pass) — single line.
- Modify `DotnetNativeInterop.NativeBridge.csproj` — `DirectPInvoke` + `NativeLibrary` for the shim/llama static libs per RID (mirrors ONNX/SQLCipher).

**Native shim (`native/llama-shim/`)**
- Create `dni_llama.h`, `dni_llama.cpp` — the tiny C API over llama.cpp.
- Create `CMakeLists.txt` — builds llama.cpp (pinned tag) + the shim into one static lib.
- Create `build-llama.sh` — builds the static libs per platform (host + ios-arm64 + iossimulator-arm64).

**iOS (`ios/`)**
- Modify `project.yml` — the GGUF rides the existing `Ai/assets` folder reference (confirm); ensure the shim static lib is linked (it's inside `dni.dylib`, so no xcframework change).
- Modify the Engine pane label in `ios/Shared/Ai/Rag/AskManualsView.swift` — "Engine (Llama 3.2 1B · {transport})".

**Build/LFS/docs**
- Modify `.gitattributes` — `*.gguf filter=lfs`.
- Modify `build/build-ios-framework.sh` + `build/build-ios-framework-device.sh` — build the shim libs before `dotnet publish`.
- Create `docs/llama-nativeaot-ios-findings.md` — the gate outcome (pass or fail).
- Modify `README.md` — note the real LLM generator once device-verified.

---

## Milestone 1 — Engine RAG composition (managed, no llama, Windows-testable)

> This milestone is mergeable on its own. It introduces the neural-RAG composition and is verified with a stub generator, so it de-risks everything except the native link.

### Task 1: `RagPrompt` — the grounded-prompt builder (human-authored)

**Files:**
- Create: `core/DotnetNativeInterop.Engine/Ai/RagPrompt.cs`
- Test: `%TEMP%\dni-rag-probe`

> **Human-input point:** the exact prompt wording (system instruction, how chunks are framed, the "answer only from the manuals" guardrail) materially shapes answer quality. The implementer pauses here and asks the human to author/approve `Build`. A sensible default is provided below to test against.

- [ ] **Step 1: Recreate the probe + write the failing test**

```powershell
$p = "$env:TEMP\dni-rag-probe"; New-Item -ItemType Directory -Force $p | Out-Null
dotnet new console -o $p --force | Out-Null
dotnet add $p reference "C:\Users\steve\projects\dotnet-ios-android-poc-native-frontend\core\DotnetNativeInterop.Engine\DotnetNativeInterop.Engine.csproj"
```
`%TEMP%\dni-rag-probe\Program.cs`:
```csharp
using DotnetNativeInterop.Engine;

var hits = new[]
{
    new SearchResult("Rooftop Unit — Replace the contactor.", 0.7),
    new SearchResult("Airflow — Replace the filter.", 0.5),
};
var prompt = RagPrompt.Build("why won't the compressor start?", hits);
Console.WriteLine(prompt);
if (!prompt.Contains("contactor", StringComparison.OrdinalIgnoreCase)) throw new("context missing");
if (!prompt.Contains("compressor", StringComparison.OrdinalIgnoreCase)) throw new("question missing");
if (!RagPrompt.Build("x", []).Contains("manual", StringComparison.OrdinalIgnoreCase)) throw new("empty-context guardrail missing");
Console.WriteLine("PASS: RagPrompt");
```

- [ ] **Step 2: Run to verify it FAILS** — `dotnet run --project $env:TEMP\dni-rag-probe -c Release` → compile error (RagPrompt missing).

- [ ] **Step 3: ASK the human to author/approve the prompt, then implement**

Default implementation — create `core/DotnetNativeInterop.Engine/Ai/RagPrompt.cs`:
```csharp
using System.Text;

namespace DotnetNativeInterop.Engine;

/// <summary>
/// Assembles the grounded RAG prompt fed to the on-device LLM: a system instruction, the retrieved
/// manual excerpts as context, then the user's question. The wording is a product/quality decision —
/// it controls how strongly the model is held to the sources — so it is intentionally small and
/// human-owned. (Used only by the neural <see cref="RagLanguageModel"/>; the extractive generator
/// composes its answer differently.)
/// </summary>
public static class RagPrompt
{
    /// <summary>Builds a Llama-3-style instruction prompt grounding <paramref name="question"/> in the
    /// retrieved <paramref name="context"/> passages.</summary>
    public static string Build(string question, SearchResult[] context)
    {
        var sb = new StringBuilder();
        sb.AppendLine("You are a maintenance assistant. Answer the question using ONLY the manual");
        sb.AppendLine("excerpts below. If they do not contain the answer, say you couldn't find it in");
        sb.AppendLine("the manuals. Be concise.");
        sb.AppendLine();
        sb.AppendLine("Manual excerpts:");
        if (context.Length == 0)
        {
            sb.AppendLine("(none found)");
        }
        else
        {
            foreach (var c in context)
            {
                sb.Append("- ").AppendLine(c.Text);
            }
        }

        sb.AppendLine();
        sb.Append("Question: ").AppendLine(question);
        sb.Append("Answer:");
        return sb.ToString();
    }
}
```

- [ ] **Step 4: Run to verify it PASSES** — `dotnet run --project $env:TEMP\dni-rag-probe -c Release` → prints the prompt + `PASS: RagPrompt`. Then `dotnet build DotnetNativeInterop.slnx -c Release` clean.

- [ ] **Step 5: Commit**
```bash
git add core/DotnetNativeInterop.Engine/Ai/RagPrompt.cs
git commit -m "feat: RagPrompt builds the grounded RAG prompt for the on-device LLM"
```

---

### Task 2: `RagLanguageModel` — retrieve → prompt → delegate → stream

**Files:**
- Create: `core/DotnetNativeInterop.Engine/RagLanguageModel.cs`
- Test: `%TEMP%\dni-rag-probe`

- [ ] **Step 1: Write the failing test (stub inner generator — no llama needed)**

`%TEMP%\dni-rag-probe\Program.cs`:
```csharp
using System.Runtime.CompilerServices;
using DotnetNativeInterop.Engine;

// Ensure assets beside the probe so SemanticSearch.Default works (copy from engine bin if missing).
var local = Path.Combine(AppContext.BaseDirectory, "Ai", "assets");
if (!File.Exists(Path.Combine(local, "vocab.txt")))
{
    var src = @"C:\Users\steve\projects\dotnet-ios-android-poc-native-frontend\core\DotnetNativeInterop.Engine\bin\Release\net10.0\Ai\assets";
    foreach (var f in Directory.EnumerateFiles(src, "*", SearchOption.AllDirectories))
    {
        var dest = Path.Combine(local, Path.GetRelativePath(src, f));
        Directory.CreateDirectory(Path.GetDirectoryName(dest)!);
        File.Copy(f, dest, true);
    }
}

// A stub generator that just echoes its prompt back, token by token — proves the composition
// (retrieve → prompt → delegate → stream) without any model.
var rag = new RagLanguageModel(new EchoModel(), topK: 2);
var sb = new System.Text.StringBuilder();
await foreach (var frag in rag.GenerateAsync(new InferenceRequest("compressor won't start")))
    sb.Append(frag);
Console.WriteLine(sb.ToString());
// The streamed output is the echoed grounded prompt: it must contain the retrieved manual text + the question.
if (!sb.ToString().Contains("Question: compressor won't start", StringComparison.OrdinalIgnoreCase))
    throw new("question not threaded into the prompt");
if (!sb.ToString().Contains("Manual excerpts", StringComparison.Ordinal))
    throw new("context not threaded into the prompt");
Console.WriteLine("PASS: RagLanguageModel");

sealed class EchoModel : ILanguageModel
{
    public async IAsyncEnumerable<string> GenerateAsync(
        InferenceRequest request, [EnumeratorCancellation] CancellationToken ct = default)
    {
        foreach (var word in request.Prompt.Split(' '))
        {
            ct.ThrowIfCancellationRequested();
            await Task.Yield();
            yield return word + " ";
        }
    }
}
```

- [ ] **Step 2: Run to verify it FAILS** — compile error (RagLanguageModel missing).

- [ ] **Step 3: Implement `RagLanguageModel`**

Create `core/DotnetNativeInterop.Engine/RagLanguageModel.cs`:
```csharp
using System.Runtime.CompilerServices;

namespace DotnetNativeInterop.Engine;

/// <summary>
/// Retrieval-augmented generation: retrieves the top manuals passages for the request, assembles a
/// grounded prompt via <see cref="RagPrompt"/>, and delegates token generation to an inner raw text
/// model (the llama.cpp-backed <see cref="LlamaLanguageModel"/> in production). The retrieval +
/// prompting live here; the inner model only completes text. Swapping the inner model is the only
/// difference between a mock run and a real on-device LLM.
/// </summary>
public sealed class RagLanguageModel(
    ILanguageModel inner,
    SemanticSearch? search = null,
    int topK = 3) : ILanguageModel
{
    /// <inheritdoc/>
    public IAsyncEnumerable<string> GenerateAsync(
        InferenceRequest request,
        CancellationToken cancellationToken = default)
    {
        var engine = search ?? SemanticSearch.Default;       // lazy: built on first use
        var hits = engine.Search(request.Prompt, "manuals", topK);
        var grounded = request with { Prompt = RagPrompt.Build(request.Prompt, hits) };
        return inner.GenerateAsync(grounded, cancellationToken);
    }
}
```

- [ ] **Step 4: Run to verify it PASSES** — prints the echoed grounded prompt + `PASS: RagLanguageModel`. Then `dotnet build DotnetNativeInterop.slnx -c Release` clean.

- [ ] **Step 5: Commit**
```bash
git add core/DotnetNativeInterop.Engine/RagLanguageModel.cs
git commit -m "feat: RagLanguageModel composes retrieval + grounded prompt over an inner generator"
```

---

## Milestone 2 — The llama.cpp gate (spike): shim, static lib, P/Invoke, Windows AOT probe

> **This is the make-or-break gate.** Prove llama.cpp + the shim load a GGUF and decode under NativeAOT on Windows FIRST (fastest signal). Only then attempt the iOS static link (Milestone 3). If a step is intractable, STOP, jump to Task 11 (findings doc, fail branch), and leave Plan A's extractive generator in place.

### Task 3: The C shim over llama.cpp

**Files:**
- Create: `native/llama-shim/dni_llama.h`
- Create: `native/llama-shim/dni_llama.cpp`

> The shim hides llama.cpp's (churn-prone) C API behind 3 stable C functions, mirroring how `EvsOrt.m` wraps the ONNX C API. **Step 1 pins a llama.cpp tag and CONFIRMS the exact API names against that tag's `llama.h`** (the API has changed names across versions — e.g. `llama_load_model_from_file` → `llama_model_load_from_file`, `llama_new_context_with_model` → `llama_init_from_model`). Adapt the shim to the names the pinned tag actually exports.

- [ ] **Step 1: Pin a tag and confirm the API**

Choose the latest stable llama.cpp release tag at implementation time (a `b####` tag). Record it (e.g. in a top comment of `build-llama.sh`). Shallow-clone it and read `include/llama.h`; confirm the exact symbols used below exist (or note the current equivalents): `llama_backend_init`, `llama_model_default_params`, `llama_model_load_from_file` (or `llama_load_model_from_file`), `llama_model_get_vocab`, `llama_context_default_params`, `llama_init_from_model` (or `llama_new_context_with_model`), `llama_tokenize`, `llama_batch_get_one`, `llama_decode`, `llama_sampler_chain_init`, `llama_sampler_chain_default_params`, `llama_sampler_chain_add`, `llama_sampler_init_top_k`, `llama_sampler_init_temp`, `llama_sampler_init_dist`, `llama_sampler_sample`, `llama_vocab_is_eog`, `llama_token_to_piece`, `llama_memory_clear`/`llama_kv_self_clear` (KV reset), `llama_sampler_free`, `llama_free`, `llama_model_free`, `llama_backend_free`. Use whatever names the pinned tag exports in the .cpp below.

- [ ] **Step 2: Create the header**

`native/llama-shim/dni_llama.h`:
```c
#ifndef DNI_LLAMA_H
#define DNI_LLAMA_H
#include <stddef.h>
#ifdef __cplusplus
extern "C" {
#endif

/* Emitted once per detokenized piece during generation. `text` is a NUL-terminated UTF-8
 * fragment valid only for the call; copy it. */
typedef void (*dni_llama_token_cb)(void* user_data, const char* text);

/* Loads a GGUF model + context (CPU). Returns an opaque handle, or NULL on failure. */
void* dni_llama_load(const char* gguf_path);

/* Single-shot generation: resets KV state, tokenizes `prompt`, decodes up to `max_tokens`
 * with temperature `temp`, invoking `cb` per piece. Returns 0 on success, negative on error. */
int dni_llama_generate(void* handle, const char* prompt, int max_tokens, float temp,
                       dni_llama_token_cb cb, void* user_data);

/* Frees the model + context. */
void dni_llama_free(void* handle);

#ifdef __cplusplus
}
#endif
#endif /* DNI_LLAMA_H */
```

- [ ] **Step 3: Create the implementation (adapt names to the pinned tag from Step 1)**

`native/llama-shim/dni_llama.cpp`:
```cpp
#include "dni_llama.h"
#include "llama.h"
#include <vector>
#include <string>

namespace {
struct DniLlama {
    llama_model*   model = nullptr;
    llama_context* ctx   = nullptr;
    const llama_vocab* vocab = nullptr;
};
} // namespace

extern "C" void* dni_llama_load(const char* gguf_path) {
    llama_backend_init();
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0; // CPU baseline; Metal is an optional later stretch.
    llama_model* model = llama_model_load_from_file(gguf_path, mp);
    if (!model) { llama_backend_free(); return nullptr; }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx   = 2048;
    cp.n_batch = 512;
    llama_context* ctx = llama_init_from_model(model, cp);
    if (!ctx) { llama_model_free(model); llama_backend_free(); return nullptr; }

    auto* h = new DniLlama{ model, ctx, llama_model_get_vocab(model) };
    return h;
}

extern "C" int dni_llama_generate(void* handle, const char* prompt, int max_tokens, float temp,
                                  dni_llama_token_cb cb, void* user_data) {
    auto* h = static_cast<DniLlama*>(handle);
    if (!h || !prompt || !cb) return -1;

    llama_memory_clear(llama_get_memory(h->ctx), true); // stateless: clear KV between calls

    const int n_prompt = -llama_tokenize(h->vocab, prompt, (int)std::char_traits<char>::length(prompt),
                                         nullptr, 0, true, true);
    std::vector<llama_token> toks(n_prompt);
    if (llama_tokenize(h->vocab, prompt, (int)std::char_traits<char>::length(prompt),
                       toks.data(), (int)toks.size(), true, true) < 0) return -2;

    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    llama_batch batch = llama_batch_get_one(toks.data(), (int)toks.size());
    int decoded = 0;
    char buf[512];
    int rc = 0;
    while (decoded < max_tokens) {
        if (llama_decode(h->ctx, batch) != 0) { rc = -3; break; }
        llama_token tok = llama_sampler_sample(smpl, h->ctx, -1);
        if (llama_vocab_is_eog(h->vocab, tok)) break;
        int n = llama_token_to_piece(h->vocab, tok, buf, (int)sizeof(buf) - 1, 0, true);
        if (n < 0) { rc = -4; break; }
        buf[n] = '\0';
        cb(user_data, buf);
        batch = llama_batch_get_one(&tok, 1);
        decoded++;
    }

    llama_sampler_free(smpl);
    return rc;
}

extern "C" void dni_llama_free(void* handle) {
    auto* h = static_cast<DniLlama*>(handle);
    if (!h) return;
    if (h->ctx)   llama_free(h->ctx);
    if (h->model) llama_model_free(h->model);
    llama_backend_free();
    delete h;
}
```
(If the pinned tag lacks `llama_get_memory`/`llama_memory_clear`, use `llama_kv_self_clear(h->ctx)` instead — confirm in Step 1.)

- [ ] **Step 4: Commit (no build yet — built by Task 4's script)**
```bash
git add native/llama-shim/dni_llama.h native/llama-shim/dni_llama.cpp
git commit -m "feat: C shim exposing a minimal load/generate/free API over llama.cpp"
```

---

### Task 4: CMake + build script for the static libs

**Files:**
- Create: `native/llama-shim/CMakeLists.txt`
- Create: `native/llama-shim/build-llama.sh`
- Modify: `.gitignore` (ignore the shim build dir)

- [ ] **Step 1: Create CMakeLists.txt**

`native/llama-shim/CMakeLists.txt`:
```cmake
cmake_minimum_required(VERSION 3.21)
project(dni_llama CXX C)
set(CMAKE_CXX_STANDARD 17)

# LLAMA_CPP_DIR is the path to a checked-out llama.cpp (pinned tag); built static, CPU-only.
set(LLAMA_BUILD_TESTS   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER  OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS   OFF CACHE BOOL "" FORCE)
add_subdirectory(${LLAMA_CPP_DIR} llama_build)

add_library(dni_llama STATIC dni_llama.cpp)
target_include_directories(dni_llama PRIVATE ${LLAMA_CPP_DIR}/include ${LLAMA_CPP_DIR}/ggml/include)
target_link_libraries(dni_llama PRIVATE llama ggml)
```

- [ ] **Step 2: Create the build script**

`native/llama-shim/build-llama.sh`:
```bash
#!/bin/bash
set -euo pipefail
# Builds dni_llama + llama.cpp as a static lib for one platform.
# Usage: build-llama.sh <host|ios-arm64|iossimulator-arm64>
# Pinned llama.cpp tag:
LLAMA_TAG="${LLAMA_TAG:-bXXXX}"   # <-- set to the tag confirmed in Task 3 Step 1
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$HERE/.llama-src"
PLATFORM="${1:-host}"
OUT="$HERE/build/$PLATFORM"

if [ ! -d "$SRC" ]; then
  git clone --depth 1 --branch "$LLAMA_TAG" https://github.com/ggml-org/llama.cpp "$SRC"
fi

CMAKE_ARGS=(-S "$HERE" -B "$OUT" -DLLAMA_CPP_DIR="$SRC" -DCMAKE_BUILD_TYPE=Release)
case "$PLATFORM" in
  ios-arm64)
    CMAKE_ARGS+=(-G Xcode -DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_ARCHITECTURES=arm64
                 -DCMAKE_OSX_DEPLOYMENT_TARGET=17.0 -DGGML_METAL=OFF) ;;
  iossimulator-arm64)
    CMAKE_ARGS+=(-G Xcode -DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_ARCHITECTURES=arm64
                 -DCMAKE_OSX_SYSROOT=iphonesimulator -DCMAKE_OSX_DEPLOYMENT_TARGET=17.0 -DGGML_METAL=OFF) ;;
  host) : ;;
esac
cmake "${CMAKE_ARGS[@]}"
cmake --build "$OUT" --config Release
# Collect the resulting .a files (libdni_llama.a, libllama.a, libggml*.a) into $OUT/lib for the .csproj.
mkdir -p "$OUT/lib"
find "$OUT" -name "*.a" -exec cp {} "$OUT/lib/" \;
echo "Built static libs in $OUT/lib"
ls -1 "$OUT/lib"
```
Set `LLAMA_TAG` to the tag confirmed in Task 3 Step 1.

- [ ] **Step 3: Ignore the build artifacts**

Add to `.gitignore`:
```
native/llama-shim/.llama-src/
native/llama-shim/build/
```

- [ ] **Step 4: Build for the host (Windows or WSL) and list the .a files**

On Windows the simplest host build is via the WSL/Bash toolchain or a Visual Studio CMake generator; produce `libdni_llama.a` + `libllama.a` + `libggml*.a` (or `.lib` on MSVC). Confirm they exist in `build/host/lib`. (If the host toolchain is problematic, the gate's real target is iOS — but the Windows AOT probe in Task 6 needs a host build, so get one working, even via WSL producing a `.a` for `win-x64` is not valid; instead build a Windows `.lib`/`.dll`. If a Windows native build is intractable, do the probe on macOS host instead and note it.)

- [ ] **Step 5: Commit the shim build system**
```bash
git add native/llama-shim/CMakeLists.txt native/llama-shim/build-llama.sh .gitignore
git commit -m "build: CMake + script to build the llama.cpp shim static lib per platform"
```

---

### Task 5: `LlamaNative` P/Invoke + `LlamaLanguageModel`

**Files:**
- Create: `core/DotnetNativeInterop.Engine/Llama/LlamaNative.cs`
- Create: `core/DotnetNativeInterop.Engine/Llama/LlamaLanguageModel.cs`

- [ ] **Step 1: Create the P/Invoke declarations**

`core/DotnetNativeInterop.Engine/Llama/LlamaNative.cs`:
```csharp
using System.Runtime.InteropServices;

namespace DotnetNativeInterop.Engine.Llama;

/// <summary>P/Invoke surface for the <c>dni_llama</c> C shim (static-linked into the NativeAOT image
/// on mobile; resolved from the native lib on the host probe). Names match <c>native/llama-shim/dni_llama.h</c>.</summary>
internal static unsafe partial class LlamaNative
{
    [LibraryImport("dni_llama")]
    internal static partial nint dni_llama_load([MarshalAs(UnmanagedType.LPUTF8Str)] string ggufPath);

    [LibraryImport("dni_llama")]
    internal static partial int dni_llama_generate(
        nint handle,
        [MarshalAs(UnmanagedType.LPUTF8Str)] string prompt,
        int maxTokens,
        float temp,
        delegate* unmanaged[Cdecl]<void*, byte*, void> callback,
        void* userData);

    [LibraryImport("dni_llama")]
    internal static partial void dni_llama_free(nint handle);
}
```

- [ ] **Step 2: Create `LlamaLanguageModel` (callback → Channel<string> bridge)**

`core/DotnetNativeInterop.Engine/Llama/LlamaLanguageModel.cs`:
```csharp
using System.Collections.Concurrent;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Threading.Channels;

namespace DotnetNativeInterop.Engine.Llama;

/// <summary>
/// A raw on-device text generator backed by llama.cpp via the <c>dni_llama</c> shim. Loads the GGUF
/// once; each <see cref="GenerateAsync"/> runs a single-shot decode on a background thread and bridges
/// the native per-piece callback into an async stream through a bounded channel. Implements
/// <see cref="ILanguageModel"/> so <see cref="RagLanguageModel"/> can wrap it unchanged.
/// </summary>
public sealed unsafe class LlamaLanguageModel : ILanguageModel, IDisposable
{
    private readonly nint _handle;
    private static readonly ConcurrentDictionary<nint, ChannelWriter<string>> Writers = new();
    private static long _next;

    public LlamaLanguageModel(string ggufPath)
    {
        _handle = LlamaNative.dni_llama_load(ggufPath);
        if (_handle == 0)
        {
            throw new InvalidOperationException($"llama.cpp failed to load GGUF at {ggufPath}");
        }
    }

    /// <inheritdoc/>
    public async IAsyncEnumerable<string> GenerateAsync(
        InferenceRequest request,
        [EnumeratorCancellation] CancellationToken cancellationToken = default)
    {
        var channel = Channel.CreateBounded<string>(new BoundedChannelOptions(256)
        {
            FullMode = BoundedChannelFullMode.Wait,
            SingleReader = true,
            SingleWriter = true,
        });

        var key = (nint)Interlocked.Increment(ref _next);
        Writers[key] = channel.Writer;

        var task = Task.Run(() =>
        {
            try
            {
                LlamaNative.dni_llama_generate(
                    _handle, request.Prompt, request.MaxTokens, request.Temperature,
                    &OnPiece, (void*)key);
            }
            finally
            {
                channel.Writer.TryComplete();
                Writers.TryRemove(key, out _);
            }
        }, cancellationToken);

        await foreach (var piece in channel.Reader.ReadAllAsync(cancellationToken).ConfigureAwait(false))
        {
            yield return piece;
        }

        await task.ConfigureAwait(false);
    }

    // Non-capturing unmanaged callback: recover the writer from the key passed as user_data.
    [UnmanagedCallersOnly(CallConvs = [typeof(System.Runtime.CompilerServices.CallConvCdecl)])]
    private static void OnPiece(void* userData, byte* text)
    {
        if (text == null)
        {
            return;
        }

        var key = (nint)userData;
        if (Writers.TryGetValue(key, out var writer))
        {
            var s = Marshal.PtrToStringUTF8((nint)text);
            if (!string.IsNullOrEmpty(s))
            {
                writer.TryWrite(s);
            }
        }
    }

    public void Dispose()
    {
        if (_handle != 0)
        {
            LlamaNative.dni_llama_free(_handle);
        }
    }
}
```

- [ ] **Step 3: Build the Engine (managed) to verify it compiles**

Run: `dotnet build core/DotnetNativeInterop.Engine/DotnetNativeInterop.Engine.csproj -c Release`
Expected: Build succeeded. (No native lib is loaded at build time; `LibraryImport`/`UnmanagedCallersOnly` just compile.)

- [ ] **Step 4: Commit**
```bash
git add core/DotnetNativeInterop.Engine/Llama/LlamaNative.cs core/DotnetNativeInterop.Engine/Llama/LlamaLanguageModel.cs
git commit -m "feat: LlamaLanguageModel P/Invokes the llama.cpp shim and bridges tokens to a channel"
```

---

### Task 6: GATE — Windows AOT probe (load a GGUF, decode under NativeAOT)

**Files:**
- Test: a throwaway AOT console at `%TEMP%\dni-llama-aot`

> This is the gate's first signal: does the shim + llama.cpp link into a `PublishAot=true` console and produce tokens? The local AOT toolchain is flaky (see the Windows-NativeAOT memory) — apply the `IlcUseEnvironmentalTools` workaround if needed. If the host native build (Task 4 Step 4) was impossible on Windows, run this probe on the macOS host instead.

- [ ] **Step 1: Get a small GGUF for the probe**

Download a tiny instruct GGUF for fast iteration (e.g. a 0.5–1B Q4) to `%TEMP%\dni-llama-aot\model.gguf`. (The shipped model is Llama-3.2-1B-Q4_K_M — Task 8 — but any small GGUF proves the pipeline.)

- [ ] **Step 2: Create the AOT probe referencing the Engine + the host static lib**

The probe must `DirectPInvoke`/`NativeLibrary` the host `libdni_llama` + `libllama`/`libggml` from `native/llama-shim/build/host/lib` (mirror the .csproj entries from Task 7). Probe `Program.cs`:
```csharp
using DotnetNativeInterop.Engine;
using DotnetNativeInterop.Engine.Llama;

using var model = new LlamaLanguageModel(@"model.gguf");
var sb = new System.Text.StringBuilder();
await foreach (var piece in model.GenerateAsync(new InferenceRequest("Say hello in one short sentence.", MaxTokens: 32, Temperature: 0.7f)))
{
    Console.Write(piece);
    sb.Append(piece);
}
Console.WriteLine();
if (sb.Length == 0) throw new Exception("no tokens generated");
Console.WriteLine("PASS: llama AOT decode");
```

- [ ] **Step 3: Publish AOT and run**

`dotnet publish %TEMP%\dni-llama-aot -c Release -r win-x64 -p:PublishAot=true` (add `-p:IlcUseEnvironmentalTools=true` + the hand-built toolset env per the Windows-NativeAOT memory if the link fails). Run the produced exe (with `model.gguf` beside it).
Expected (PASS): coherent tokens stream, then `PASS: llama AOT decode`.

- [ ] **Step 4: GATE DECISION**
  - **PASS** → proceed to Task 7 (iOS link). Record the working tag + flags for the findings doc.
  - **FAIL** (AOT link intractable after the documented workaround) → STOP. Go to Task 11 **fail branch**: keep this code (exclude `Llama/**` from the build), write `docs/llama-nativeaot-ios-findings.md` with the exact error + diagnosis, leave Plan A's `ExtractiveLanguageModel` as the generator. Do not attempt Milestone 3.

- [ ] **Step 5: Clean up the probe** (`Remove-Item -Recurse -Force $env:TEMP\dni-llama-aot`). No commit (throwaway).

---

## Milestone 3 — Wire in the real model (gate PASSED): static link, GGUF, device build

### Task 7: Static-link the shim + llama.cpp into the NativeAOT image

**Files:**
- Modify: `core/DotnetNativeInterop.NativeBridge/DotnetNativeInterop.NativeBridge.csproj`

> Mirror the existing ONNX Runtime / e_sqlcipher hand-link pattern: `DirectPInvoke` binds the `dni_llama` P/Invokes to statically-linked symbols, `NativeLibrary` pulls each `.a` into the image, and `-lc++` resolves llama.cpp's C++ stdlib (it's C++).

- [ ] **Step 1: Add the link items**

In `DotnetNativeInterop.NativeBridge.csproj`, after the ONNX `ItemGroup`s, add (adapt the exact `.a` filenames to what `build-llama.sh` produced — likely `libdni_llama.a`, `libllama.a`, `libggml.a`, `libggml-base.a`, `libggml-cpu.a`):
```xml
  <!-- llama.cpp on-device LLM (Plan B), hand-linked like ONNX/SQLCipher. Static C++ libs built by
       native/llama-shim/build-llama.sh. DirectPInvoke binds the "dni_llama" shim P/Invokes; the
       NativeLibrary entries pull the shim + llama + ggml archives into the NativeAOT image. -->
  <ItemGroup Condition="$(RuntimeIdentifier.StartsWith('ios'))">
    <DirectPInvoke Include="dni_llama" />
    <LinkerArg Include="-lc++" />
  </ItemGroup>
  <ItemGroup Condition="'$(RuntimeIdentifier)' == 'ios-arm64'">
    <NativeLibrary Include="$(MSBuildProjectDirectory)/../../native/llama-shim/build/ios-arm64/lib/libdni_llama.a" />
    <NativeLibrary Include="$(MSBuildProjectDirectory)/../../native/llama-shim/build/ios-arm64/lib/libllama.a" />
    <NativeLibrary Include="$(MSBuildProjectDirectory)/../../native/llama-shim/build/ios-arm64/lib/libggml.a" />
    <NativeLibrary Include="$(MSBuildProjectDirectory)/../../native/llama-shim/build/ios-arm64/lib/libggml-base.a" />
    <NativeLibrary Include="$(MSBuildProjectDirectory)/../../native/llama-shim/build/ios-arm64/lib/libggml-cpu.a" />
  </ItemGroup>
  <ItemGroup Condition="'$(RuntimeIdentifier)' == 'iossimulator-arm64'">
    <NativeLibrary Include="$(MSBuildProjectDirectory)/../../native/llama-shim/build/iossimulator-arm64/lib/libdni_llama.a" />
    <NativeLibrary Include="$(MSBuildProjectDirectory)/../../native/llama-shim/build/iossimulator-arm64/lib/libllama.a" />
    <NativeLibrary Include="$(MSBuildProjectDirectory)/../../native/llama-shim/build/iossimulator-arm64/lib/libggml.a" />
    <NativeLibrary Include="$(MSBuildProjectDirectory)/../../native/llama-shim/build/iossimulator-arm64/lib/libggml-base.a" />
    <NativeLibrary Include="$(MSBuildProjectDirectory)/../../native/llama-shim/build/iossimulator-arm64/lib/libggml-cpu.a" />
  </ItemGroup>
```
(Use the actual archive list from `ls native/llama-shim/build/ios-arm64/lib`. Apple Accelerate/Metal frameworks: CPU build needs none beyond `-lc++`; if the link reports missing `_cblas_*`/Accelerate symbols, add `<LinkerArg Include="-framework Accelerate" />`.)

- [ ] **Step 2: Commit**
```bash
git add core/DotnetNativeInterop.NativeBridge/DotnetNativeInterop.NativeBridge.csproj
git commit -m "build: static-link the llama.cpp shim into the NativeAOT iOS image"
```

---

### Task 8: Bundle the Llama-3.2-1B GGUF via LFS

**Files:**
- Create: `core/DotnetNativeInterop.Engine/Ai/assets/Llama-3.2-1B-Instruct-Q4_K_M.gguf` (LFS)
- Modify: `.gitattributes`, `core/DotnetNativeInterop.Engine/DotnetNativeInterop.Engine.csproj`

- [ ] **Step 1: Track GGUF in LFS**

Add to `.gitattributes`:
```
*.gguf filter=lfs diff=lfs merge=lfs -text
```
Run: `git lfs track "*.gguf"` (idempotent; confirms the attribute).

- [ ] **Step 2: Download the model into the assets folder**

Download `Llama-3.2-1B-Instruct-Q4_K_M.gguf` (~0.8 GB) from a reputable GGUF repo (e.g. `bartowski/Llama-3.2-1B-Instruct-GGUF`) into `core/DotnetNativeInterop.Engine/Ai/assets/`. Verify the SHA/size is sane (not an HTML error page).

- [ ] **Step 3: Copy it to build output + confirm it rides the iOS bundle**

In `DotnetNativeInterop.Engine.csproj`, alongside the other `Ai/assets` `<None Update>` items:
```xml
    <None Update="Ai/assets/Llama-3.2-1B-Instruct-Q4_K_M.gguf" CopyToOutputDirectory="PreserveNewest" />
```
The iOS app bundles `Ai/assets` as a folder reference (`ios/project.yml`), so the GGUF ships at `<App>.app/assets/`. Confirm `project.yml`'s `Ai/assets` folder entry is present (it is) — no project.yml change needed.

- [ ] **Step 4: Point `LlamaLanguageModel` at the bundled GGUF in `EngineHost` (next task wires it)**

(The path is resolved the same way as the other assets — see Task 9.)

- [ ] **Step 5: Commit (LFS)**
```bash
git add .gitattributes core/DotnetNativeInterop.Engine/DotnetNativeInterop.Engine.csproj core/DotnetNativeInterop.Engine/Ai/assets/Llama-3.2-1B-Instruct-Q4_K_M.gguf
git commit -m "feat: bundle Llama-3.2-1B-Instruct Q4_K_M GGUF via Git LFS"
```

---

### Task 9: Swap the RAG generator to the real model

**Files:**
- Modify: `core/DotnetNativeInterop.NativeBridge/EngineHost.cs`

- [ ] **Step 1: Resolve the GGUF path + construct the real generator**

In `EngineHost.cs`, replace the RAG orchestrator wiring line (Plan A's `_ragOrchestrator ??= new InferenceOrchestrator(new ExtractiveLanguageModel());`) with a guarded swap to the llama-backed generator, falling back to extractive if the model can't load (defensive — keeps the app alive on a bad bundle):
```csharp
            _ragOrchestrator ??= new InferenceOrchestrator(BuildRagModel());
```
And add a helper to `EngineHost`:
```csharp
    private static ILanguageModel BuildRagModel()
    {
        try
        {
            var dir = Ai.AssetsLocator.ResolveAssetsDir(); // see note
            var gguf = System.IO.Path.Combine(dir, "Llama-3.2-1B-Instruct-Q4_K_M.gguf");
            if (System.IO.File.Exists(gguf))
            {
                return new RagLanguageModel(new Llama.LlamaLanguageModel(gguf), DotnetNativeInterop.Engine.SemanticSearch.Default);
            }
        }
        catch (Exception)
        {
            // Fall through to the always-available managed generator.
        }

        return new ExtractiveLanguageModel();
    }
```
> **Note on `ResolveAssetsDir`:** it's currently `private` in `SemanticSearch`. Expose the probe via a small `internal static` helper the engine already has, OR make `SemanticSearch.ResolveAssetsDir` `public static`. Do the minimal change: add `public static string ResolveAssetsDir()` to `SemanticSearch` (rename the existing private method or add a public passthrough) and call `SemanticSearch.ResolveAssetsDir()` here. Pick ONE approach and keep it consistent. (Update this snippet to whatever you choose — e.g. `var dir = SemanticSearch.ResolveAssetsDir();`.)

- [ ] **Step 2: Build managed to confirm it compiles**

Run: `dotnet build DotnetNativeInterop.slnx -c Release`
Expected: Build succeeded. (On the host this links the managed IL only; the GGUF won't be present so `BuildRagModel` falls back to extractive at runtime — fine.)

- [ ] **Step 3: Commit**
```bash
git add core/DotnetNativeInterop.NativeBridge/EngineHost.cs core/DotnetNativeInterop.Engine/Ai/SemanticSearch.cs
git commit -m "feat: RAG orchestrator uses the llama.cpp model when the GGUF is present (extractive fallback)"
```

---

### Task 10: iOS build wiring + device verification

**Files:**
- Modify: `build/build-ios-framework.sh`, `build/build-ios-framework-device.sh`
- Modify: `ios/Shared/Ai/Rag/AskManualsView.swift` (label)

- [ ] **Step 1: Build the shim libs before publishing the framework**

In BOTH build scripts, before the `dotnet publish` step, add a call to build the shim for the matching RID(s):
```bash
bash "${PROJECT_DIR}/native/llama-shim/build-llama.sh" ios-arm64
# (build-ios-framework.sh also:) bash "${PROJECT_DIR}/native/llama-shim/build-llama.sh" iossimulator-arm64
```

- [ ] **Step 2: Update the Engine pane label**

In `AskManualsView.swift`, change the `engineHeader` base string from `"Engine (\(model.transport.displayName))"` to `"Engine · Llama 3.2 1B (\(model.transport.displayName))"`.

- [ ] **Step 3: Sync to the Mac WITH LFS bytes, rebuild, install**

> The Mac host has no git-lfs and a stale tree (see runbook + memory). Ensure the real `model.onnx`, `onnxruntime.xcframework`, and the new GGUF reach the Mac as bytes. Approaches: (a) install `git-lfs` on the Mac + `git clone` + `git lfs pull`; or (b) `tar` the working tree from the Windows checkout (which has the smudged LFS files) and extract into a fresh Mac dir. Use whichever the host allows. Then, in that synced dir: `build/build-ios-framework-device.sh` → `xcodegen generate` → signed `xcodebuild` (unlock keychain inline) → `xcrun devicectl device install`. Run backgrounded with `tee`.

- [ ] **Step 4: Device verification**

On the iPad, AI → Ask the Manuals: the Engine pane now streams a **neural** grounded answer (Llama 3.2 1B) over FFI/HTTP/SQLCipher; the header reads "Llama 3.2 1B". Compare against the Apple pane. Confirm first-token latency is reasonable (a few seconds to load on first query). Confirm no regressions.

- [ ] **Step 5: Commit the build wiring + label**
```bash
git add build/build-ios-framework.sh build/build-ios-framework-device.sh ios/Shared/Ai/Rag/AskManualsView.swift
git commit -m "build: build llama shim during iOS framework build; label engine pane as Llama 3.2 1B"
```

---

## Milestone 4 — Document the gate outcome

### Task 11: Findings doc (PASS or FAIL branch) + README

**Files:**
- Create: `docs/llama-nativeaot-ios-findings.md`
- Modify: `README.md`

- [ ] **Step 1 (PASS branch): write the success recipe**

Title: "Running llama.cpp inside a NativeAOT iOS image — what worked." Record: the exact pinned llama.cpp tag, the shim API, the CMake/static-lib build (CPU-only, Metal off), the `.csproj` `DirectPInvoke`/`NativeLibrary`/`-lc++` (+ any `-framework Accelerate`) entries, the GGUF (model, quant, size, LFS), measured first-token latency on device, and an explicit contrast with the ONNX (`onnx-nativeaot-ios-findings.md`) and Core ML (`onnx-coreml-edge-findings.md`) writeups — this is native-link **gate #3**.

- [ ] **Step 1 (FAIL branch): write the failure analysis instead**

Title: "Linking llama.cpp into NativeAOT iOS — what failed (and what a fix needs)." Record the exact error (AOT link / missing symbols / GGUF load), the pinned tag + flags tried, the diagnosis, and "what a fix would need." Then: exclude `Llama/**` from the build (in the Engine `.csproj`, like the gRPC `<Compile Remove>` reference), revert Task 9's swap so `EngineHost` keeps `new ExtractiveLanguageModel()`, and confirm the managed solution + Plan A's feature still build and ship. The contrast survives as "engine extractive RAG vs Apple neural RAG."

- [ ] **Step 2: Update README**

If PASS, change the Plan A status line to note the engine generator is now Llama-3.2-1B via llama.cpp (gate #3 passed). If FAIL, note llama.cpp stayed a documented experiment and the extractive generator ships.

- [ ] **Step 3: Commit**
```bash
git add docs/llama-nativeaot-ios-findings.md README.md
git commit -m "docs: llama.cpp NativeAOT-iOS gate findings + README status"
```

---

## Self-Review (author)

- **Spec coverage:** the spec's gate-pass branch (real llama.cpp generator over the transports) = Milestones 2–3; the gate-fail branch (keep attempt, findings doc, extractive ships) = Task 6 Step 4 + Task 11 fail branch; the user-authored prompt = Task 1; the `ILanguageModel` swap-in promise = Task 9 (one line). GGUF via LFS = Task 8. Findings doc contrasted with ONNX/Core ML = Task 11. ✓
- **Type consistency:** `LlamaLanguageModel : ILanguageModel` (raw text) wrapped by `RagLanguageModel(inner, search)`; `RagPrompt.Build(string, SearchResult[])` matches both the test and `RagLanguageModel`; the shim's 3 functions match `LlamaNative` and `dni_llama.h`; the `dni_llama_token_cb` signature `(void*, const char*)` matches the C# `delegate* unmanaged[Cdecl]<void*, byte*, void>` and `OnPiece`.
- **Placeholder scan:** the only intentionally-deferred specifics are the llama.cpp **tag** and the exact **`.a` filenames / API names**, each with an explicit confirm-against-the-pinned-tag step (Task 3 Step 1, Task 4 Step 4, Task 7 Step 1) — mirroring Plan A/EVS's "use the names the spike confirmed." No "TODO"/"handle later".
- **Risk isolation:** Milestone 1 is fully managed + Windows-testable and mergeable alone; the native gate (Milestone 2) can fail without breaking the shipped feature.

---

## Note

This plan depends on the Mac build host being LFS-ready (it currently is not — see `docs/ios-build-deploy-runbook.md` and the build-host memory). Milestones 1–2 (managed + the Windows/host AOT gate) need no Mac. Milestone 3 needs the Mac + the GGUF/LFS sync sorted.
