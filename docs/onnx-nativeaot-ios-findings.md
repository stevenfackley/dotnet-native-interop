# Running ONNX Runtime under NativeAOT on iOS — what worked

**Date:** 2026-06-05
**Status:** Windows AOT gate **PASSED**. iOS ARM64 link — validated by the framework build (see the
[iOS link](#ios-arm64-link-the-real-target) section, completed during the Mac build).
**Why this doc exists:** the on-device AI feature embeds an [all-MiniLM-L6-v2][minilm] sentence model via
`Microsoft.ML.OnnxRuntime` **inside** the NativeAOT engine (`dni.dylib`). "Does ONNX Runtime survive
NativeAOT compilation + trimming, and link on iOS?" was an open question, so it was gated behind a
feasibility spike before any feature work. This records exactly what was tried and what worked — including
the non-obvious toolchain potholes — so the next person doesn't have to rediscover them.

## TL;DR

- ✅ `Microsoft.ML.OnnxRuntime` **1.20.1** AOT-compiles, **links**, and **runs inference** in a NativeAOT
  `win-x64` binary. ONNX is not the blocker.
- ✅ The package ships an **official iOS `onnxruntime.xcframework`** (and `.so`/`.dylib`/`.aar` for other
  RIDs) — confirmed present in the restored NuGet assets.
- ⚠️ Two potholes had nothing to do with ONNX and everything to do with the local toolchain / codebase:
  a broken Visual Studio toolset (see [Toolchain](#toolchain-potholes-not-onnx-problems)), and a C#
  **name collision** with the engine's own `InferenceSession` type.

## The spike

A throwaway NativeAOT console (outside the repo so repo-wide `Directory.Build.props` doesn't apply),
referencing only the ONNX package, loading the real model and running one inference.

`spike.csproj`:
```xml
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <OutputType>Exe</OutputType>
    <TargetFramework>net10.0</TargetFramework>
    <PublishAot>true</PublishAot>
    <InvariantGlobalization>true</InvariantGlobalization>
    <ImplicitUsings>enable</ImplicitUsings>
  </PropertyGroup>
  <ItemGroup>
    <PackageReference Include="Microsoft.ML.OnnxRuntime" Version="1.20.1" />
  </ItemGroup>
</Project>
```

`Program.cs` loads `model.onnx`, dumps the input/output metadata, and runs `[CLS] hello world [SEP]`
(ids `101, 7592, 2088, 102`).

**Publish + run output (PASS):**
```
in:  input_ids [-1,-1]
in:  attention_mask [-1,-1]
in:  token_type_ids [-1,-1]
out: last_hidden_state [-1,-1,384]
PASS: inference ran, output dims = [1,4,384]
```

This is the strongest cheap signal: ONNX Runtime's managed→native codegen completed (no unresolved ONNX
symbols, no trim breakage), the binary linked, and **inference actually ran** in the AOT image — so the
runtime survives trimming, not just compilation.

## Model I/O contract (used by the encoder)

| | name | type | shape |
|---|---|---|---|
| input | `input_ids` | int64 | `[batch, seq]` |
| input | `attention_mask` | int64 | `[batch, seq]` |
| input | `token_type_ids` | int64 | `[batch, seq]` |
| output | `last_hidden_state` | float32 | `[batch, seq, 384]` |

The encoder ([`OnnxTextEncoder.cs`](../core/DotnetNativeInterop.Engine/Ai/OnnxTextEncoder.cs)) tokenizes
(BERT WordPiece), runs the session, **mean-pools** the token embeddings over the attention mask, then
**L2-normalizes** to a 384-d unit vector so cosine similarity reduces to a dot product.

## Toolchain potholes (NOT ONNX problems)

These cost the most time and are the most reusable findings. On this Windows box, a bare
`dotnet publish -p:PublishAot=true` fails with **"Platform linker not found"** — but the cause is a broken
Visual Studio install, not NativeAOT or ONNX:

1. NativeAOT's `findvcvarsall.bat` probe can't see a **prerelease** VS instance (VS 2026 / v18 here).
2. `VsDevCmd.bat` resolves `LIB` to a **deleted** default toolset (`14.50.35717`) under a `BuildTools`
   instance that shadows `Community`; `-vcvars_ver=14.51` was ignored.
3. Toolsets were partially installed — only **`14.42.34433` (Community)** had **both** `link.exe` and
   `libcmt.lib`.

**Working recipe** — bypass VS auto-detection and hand the linker to ILC via the environment:
```powershell
$msvc = "C:\Program Files\Microsoft Visual Studio\18\Community\VC\Tools\MSVC\14.42.34433"
$sdk  = "C:\Program Files (x86)\Windows Kits\10"; $sdkv = "10.0.22621.0"
$env:PATH    = "$msvc\bin\Hostx64\x64;$env:PATH"
$env:LIB     = "$msvc\lib\x64;$sdk\Lib\$sdkv\um\x64;$sdk\Lib\$sdkv\ucrt\x64"
$env:INCLUDE = "$msvc\include;$sdk\Include\$sdkv\ucrt;$sdk\Include\$sdkv\shared;$sdk\Include\$sdkv\um"
dotnet publish <proj> -c Release -r win-x64 -p:PublishAot=true -p:IlcUseEnvironmentalTools=true
```
The key is **`-p:IlcUseEnvironmentalTools=true`**: ILC then skips `findvcvarsall.bat` and uses the
linker/`LIB` already in the environment. (This only affects the local Windows smoke test — the iOS link
uses the Xcode/clang toolchain on the Mac and is unaffected.)

### Name collision: two `InferenceSession`s

The engine already defines its **own** `DotnetNativeInterop.Engine.InferenceSession` (a Channel-based
streaming session). `OnnxTextEncoder` lives in the same namespace, so unqualified `InferenceSession` binds
to the engine's type, not ORT's — a same-namespace type beats a `using`-imported one, so it fails silently
with "no constructor that takes 1 arguments" rather than an ambiguity error. Fixed with a using alias:
```csharp
using OrtSession = Microsoft.ML.OnnxRuntime.InferenceSession;
```

## iOS ARM64 link (the real target)

The `win-x64` spike de-risks codegen/trim; the **actual** question is whether `onnxruntime` (ios-arm64)
links into `dni.dylib` during the NativeAOT iOS publish. That is validated by the framework build
(`build/build-ios-framework-device.sh`) on the Mac mini.

> **Pending the Mac framework build (plan Task 13).** This section will record the real linker outcome:
> whether the ONNX `ios-arm64` static lib resolved into `dni.dylib` out of the box, and any
> `DirectPInvoke` / `NativeLibrary` / linker flags required. If the link fails, the exact linker error
> goes here verbatim and the engine falls back to a pure-.NET encoder (a separate subsystem).

> **Asset note:** `model.onnx` (~90 MB) is stored via **Git LFS**. `git archive` ships only the LFS
> pointer, so the Mac build needs the real file — `git lfs pull` after clone, or `scp` it across — before
> the model can be bundled.

[minilm]: https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2
