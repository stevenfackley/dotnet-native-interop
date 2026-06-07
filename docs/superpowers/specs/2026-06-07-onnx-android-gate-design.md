# ONNX-on-Android Gate (Design)

**Date:** 2026-06-07
**Program:** Android 1:1 parity. SP0 native gate ✅, SP1 UI shell ✅. This is the **ONNX-on-Android gate** —
unblocks the AI tab (engine semantic search + RAG retrieval). Branch: `feat/onnx-android-gate`.

## Goal

Make ONNX Runtime (ORT) work inside the .NET 10 NativeAOT `linux-bionic-arm64` image on Android so the
engine's `dni_search` (semantic search) and RAG *retrieval* (`OnnxTextEncoder`/`SemanticSearch`) run
on-device, proven by the real `dni_search` returning ranked results on the arm64 emulator. Uses the NNAPI
execution provider with CPU fallback.

## Background / current state

- ORT is wired **iOS-only**: the `.csproj` extracts `onnxruntime.xcframework` and hand-links it under
  `RuntimeIdentifier.StartsWith('ios')` (`DirectPInvoke onnxruntime`, `NativeLibrary`, `-lc++`). There is
  **no Android/bionic ORT wiring**, and `build-android-so.sh` ships only `libdni.so` + `libe_sqlcipher.so`.
- The engine's `OnnxTextEncoder` (`core/.../Engine/Ai/OnnxTextEncoder.cs`) opens an
  `Microsoft.ML.OnnxRuntime.InferenceSession(modelPath)`. `SemanticSearch` (`Engine/Ai/SemanticSearch.cs`)
  resolves the assets dir via `ResolveAssetsDir()`, which probes `AppContext.BaseDirectory/{Ai/assets,assets,.}`
  for `vocab.txt`. `dni_search` (`NativeBridge/Ffi/Exports.Ai.cs`) lazily builds `SemanticSearch.Default` on
  first call. Engine init does **not** touch ORT (lazy).
- Model assets live at `core/DotnetNativeInterop.Engine/Ai/assets/`: `model.onnx` (86 MB, Git-LFS),
  `vocab.txt` (227 KB), `corpus.txt` (2 KB), `manuals/` (5 markdown files). iOS bundles them via an Xcode
  folder reference into `<App>.app/assets/`. **Android bundles none of them** (only `patterns.json`).
- `Microsoft.ML.OnnxRuntime` 1.20.1 ships `runtimes/android/native/onnxruntime.aar` containing
  `jni/arm64-v8a/libonnxruntime.so` (17.5 MB) — confirmed present in the nuget cache.

**Why the data plumbing matters:** ORT opens the model by filesystem path; Android APK assets are not
directly file-readable, and on a NativeAOT `.so` `AppContext.BaseDirectory` points at the lib dir, not the
model. So the model must be delivered to the device, extracted to a readable dir, and the engine told where.

## Scope

**In scope (one cycle):**
- Link `libonnxruntime.so` into the Android app (extract from the `.aar` into `jniLibs`).
- Bundle the model assets in the APK and extract them to a readable dir on first run.
- A new `dni_set_assets_dir(path)` ABI export so the engine uses the extracted dir.
- NNAPI execution provider **with CPU fallback**, gated to Android (iOS stays CPU).
- Prove the **real `dni_search`** returns ranked results on the arm64 emulator (instrumented test).

**Out of scope (explicit follow-ons):**
- The **AI tab UI** — replacing the `GatedTabScreen` placeholder with a Semantic Search screen, and the
  Ask-the-Manuals RAG streaming UI. (Retrieval works after this gate; the UI is a separate cycle.)
- The **Manuals/EVS tab** — Edge Vector Search is a *separate* ONNX path (iOS uses a Swift `EvsOrt.m` /
  onnxruntime-objc over a prebuilt index, not the engine). Android EVS is its own cycle.
- NNAPI hardware acceleration verification — the emulator has no real NNAPI accelerator
  (`nnapi-reference` is CPU-backed), so true acceleration is only demonstrable on a physical device.

## A. Native link

`build-android-so.sh`: after the `libe_sqlcipher.so` step, extract `jni/arm64-v8a/libonnxruntime.so` from
`~/.nuget/packages/microsoft.ml.onnxruntime/<ver>/runtimes/android/native/onnxruntime.aar` into
`android/app/src/main/jniLibs/arm64-v8a/`. (Only `libonnxruntime.so`; **not** `libonnxruntime4j_jni.so` —
that's the Java binding, unused; we P/Invoke the C API from .NET.) Add a restore-only PackageReference for
the bionic RID so the `.aar` is in the cache on a clean CI checkout:
```xml
<ItemGroup Condition="$(RuntimeIdentifier.StartsWith('linux-bionic'))">
  <PackageReference Include="Microsoft.ML.OnnxRuntime" Version="1.20.1"
                    GeneratePathProperty="true" ExcludeAssets="all" PrivateAssets="all" />
</ItemGroup>
```
No `DirectPInvoke`/`-force_load` — dynamic `.so`, normal `DllImport("onnxruntime")` resolves at runtime;
libc++ is already statically linked from the llama gate (`libc++_static.a` + `libc++abi.a`).

## B. NNAPI execution provider (Android-only, CPU fallback)

`OnnxTextEncoder` builds the `InferenceSession` with `SessionOptions` that append the NNAPI EP **with the
CPU EP left enabled** (graph partitioning → unsupported ops and the emulator's no-accelerator case fall
back to CPU). This must be **Android-only**: the iOS ORT framework has no NNAPI symbol, so appending it
there would fail. Gating: prefer `OperatingSystem.IsAndroid()`; **verify in implementation** whether that
returns true on a `linux-bionic-arm64` NativeAOT image — if it is unreliable, fall back to a host-set flag
(set in the same `dni_set_assets_dir` call, which only the Android host invokes). NNAPI is reached via the
managed `SessionOptions` NNAPI API if available for the `net10.0` TFM, else a direct
`DllImport("onnxruntime")` to `OrtSessionOptionsAppendExecutionProvider_Nnapi`. iOS behavior is unchanged
(CPU). Default flags; do not disable the CPU fallback.

## C. Assets-dir plumbing

New export **`dni_set_assets_dir(const char* path)`** — declared in `abi/dni.h`, implemented in
`NativeBridge/Ffi/Exports.Ai.cs`, stores a static (e.g. `SemanticSearch.AssetsDirOverride`) that
`ResolveAssetsDir()` prefers over its `AppContext.BaseDirectory` probing when set and valid. Returns 0 on
success, negative on invalid path. JNI-bound as `NativeBridge.nativeSetAssetsDir(path: String): Int`.

Kotlin host (`DotnetNativeInteropApp`): a small `AssetExtractor` copies `model.onnx`, `vocab.txt`,
`corpus.txt`, and `manuals/**` from the APK assets to `filesDir/dni-assets/` — **idempotent** (skip a file
whose destination exists with the same length). Then call `NativeBridge.nativeSetAssetsDir(dir)` **before**
`NativeBridge.nativeInitialize()`. Extraction runs off the main thread (86 MB copy on first launch).

## D. Model bundling

A Gradle `Copy` task stages `model.onnx`, `vocab.txt`, `corpus.txt`, and `manuals/` from
`core/DotnetNativeInterop.Engine/Ai/assets/` into `android/app/src/main/assets/dni-assets/` at build time
(same pattern as the existing `patterns.json` copy), wired as a `preBuild` dependency. The 86 MB model is
Git-LFS; CI must checkout LFS (the `ci-ios` fix already established `lfs: true`; apply the same to the
`ci-android` checkout). Result: a large debug APK — acceptable for the gate; Play Asset Delivery is a later
optimization.

## E. Proof (emulator)

Instrumented test (`OnnxGateTest`, arm64 emulator): load the native libs, extract the assets to a test dir
(or reuse the app's extraction), `nativeSetAssetsDir(dir)`, `nativeInitialize()`, then call
`dni_search("How do I reset the device?", "facts")` (via `NativeBridge.nativeSearch`) and assert the result
is non-empty ranked `[{text,score}]` JSON with descending scores. Emit a `PASS:` logcat line including
whether the NNAPI EP registered (log from the encoder). This exercises the real path: ORT loads the 86 MB
model from the extracted dir, tokenizes via `vocab.txt`, runs the embedding, ranks the corpus.

## Error handling

- `dni_set_assets_dir` with a missing/unreadable path → negative return; the host logs and the engine falls
  back to its existing probing (no crash).
- NNAPI append failure (unexpected) → caught; the session is created CPU-only (fallback), logged.
- `dni_search` already returns `0`/empty JSON on engine failure (existing contract) — the UI/test treats
  empty as "no results / not ready", never a crash.

## Decisions honored / risks

- **Additive** — asset extraction + `nativeSetAssetsDir` slot into the existing init; `OnnxTextEncoder`'s
  CPU path (iOS) is unchanged; no existing file's behavior is replaced.
- **NNAPI on the emulator ≈ CPU** — documented; the gate proves the EP loads + `dni_search` works, not
  hardware acceleration (needs a physical device).
- **`OperatingSystem.IsAndroid()` on bionic NativeAOT** — unverified; implementation must confirm or use
  the host-flag fallback.
- **86 MB APK** — accepted for the gate.
