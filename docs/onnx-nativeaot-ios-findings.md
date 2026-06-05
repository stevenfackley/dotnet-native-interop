# Running ONNX Runtime under NativeAOT on iOS — what worked

**Date:** 2026-06-05
**Status:** Windows AOT gate **PASSED**, iOS ARM64 static link **PASSED**, and the signed app
**installs on a physical iPad**. See [iOS link](#ios-arm64-link--confirmed) for how.
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

## iOS ARM64 link — confirmed

The `win-x64` spike de-risks codegen/trim; the **real** question was whether `onnxruntime` links into
`dni.dylib` during the NativeAOT iOS publish. **It does — but not automatically.**

**ORT's iOS lib is a *static* archive.** Inside `runtimes/ios/native/onnxruntime.xcframework.zip`, each
slice's `onnxruntime.framework/onnxruntime` is an `ar` archive (a single `prelinked_objects.o`), not a
dynamic framework. NativeAOT's first `ios-arm64` publish linked **zero** ONNX: the package's auto-link
targets fire only for `-ios` TFMs, not NativeAOT RIDs. So `dni.dylib` came out 6.9 MB with no ONNX
symbols, and the `.xcframework.zip` was left unembedded — the app would have crashed at the first
`InferenceSession`.

**The fix mirrors this repo's existing SQLCipher hand-link** (`DotnetNativeInterop.NativeBridge.csproj`):
extract the xcframework, `DirectPInvoke` the `onnxruntime` P/Invokes, point `NativeLibrary` at the static
archive, and link `libc++` (ORT is C++):
```xml
<Target Name="ExtractOnnxRuntimeIos" Condition="$(RuntimeIdentifier.StartsWith('ios'))" BeforeTargets="IlcCompile">
  <Unzip SourceFiles="$(PkgMicrosoft_ML_OnnxRuntime)/runtimes/ios/native/onnxruntime.xcframework.zip"
         DestinationFolder="$(IntermediateOutputPath)ort-ios" SkipUnchangedFiles="true" />
</Target>
<ItemGroup Condition="'$(RuntimeIdentifier)' == 'ios-arm64'">
  <DirectPInvoke Include="onnxruntime" />
  <NativeLibrary Include="$(IntermediateOutputPath)ort-ios/onnxruntime.xcframework/ios-arm64/onnxruntime.framework/onnxruntime" />
  <LinkerArg Include="-lc++" />
</ItemGroup>
```
After this, `dni.dylib` grows **6.9 MB → 23 MB** (+17 MB of `__TEXT`) and the linker pulls ORT's
`prelinked_objects.o`. **No `-force_load` is needed**: ORT ships as one prelinked object, so referencing
the API entry point drags in the whole runtime — operator kernels included. The `ios-arm64` and
`iossimulator-arm64` slices link identically. A benign `ld` warning notes ORT was built for iOS 13 while
the dylib targets 12.2 — harmless, since the app's deployment target is iOS 17.

**End-to-end on a physical device.** The full app — signed, with ORT in the dylib and the 90 MB model
bundled — builds and **installs on a real iPad** (`xcrun devicectl device install`, exit 0). The model,
vocab, and corpus ride along as an Xcode folder reference at `<App>.app/assets/`, and the engine's
`ResolveAssetsDir` probes both the .NET-output (`Ai/assets/`) and bundle (`assets/`) layouts so it loads
the model in either context.

**Asset shipping note:** `model.onnx` (~90 MB) is stored via **Git LFS**. In practice `git archive`
smudges the pointer to the real bytes when the LFS object is in the local cache, so a piped
`git archive | tar` sync carries the real model to the build host; if it ever ships only the pointer,
`git lfs pull` (or `scp`) the file before bundling.

[minilm]: https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2
