# Android parity — SP0: the native gate (design)

**Date:** 2026-06-06
**Status:** approved design, pre-plan
**Scope:** SP0 only. SP1–SP5 get their own brainstorm → spec → plan cycles.

## Program context (the north star)

Bring the Android app to **full 1:1 parity** with the unified iOS app, built as independent
sub-projects on a hard dependency chain:

```
SP0  native gate         (BLOCKER for all)
SP1  core transport trio UI   Dashboard · Features(+detail) · Compare · About · picker · telemetry strip
SP2  Ask the Manuals (RAG)     llama.cpp generator; no Apple column
SP3  EdgeSearch (EVS)          onnxruntime-android + NNAPI
SP4  Lab                       fractal · raymarcher · raster · benchmark
SP5  Latency                   distribution · jitter · payload-scaling · telemetry
```

**Platform-divergence decisions (program-level, already made):**
- **Apple Foundation Models** has no Android equivalent → the "engine vs Apple" RAG column is **dropped**
  on Android (Android compares engine-llama vs the managed extractive fallback, or single-column).
- **EVS** uses **onnxruntime-android + NNAPI** instead of the iOS ONNX/CoreML paths. SP3's problem.

Today, Android is frozen at commit `2e54afe` (#2) and is **broken**, not merely behind (see below).

## Why Android is broken today (findings, 2026-06-06)

1. **JNI shim is dead after the `→ dotnetnativeinterop` rename.** `android/app/src/main/cpp/jni_bridge.c`
   exports static `Java_io_dni_transport_NativeBridge_*` names, but the Kotlin package is now
   `io.dotnetnativeinterop.transport` — the names cannot resolve. (The caveats doc claims the repo uses
   `RegisterNatives`; this file does not — it uses static naming. Doc was aspirational.)
2. **JNI shim calls `dni_grpc_start/stop`, which no longer exist.** gRPC is `<Compile Remove>`'d from the
   native build; `dni.h` shows those exports commented out. A second reason the shim won't link.
3. **JNI shim covers only the old ABI subset.** Missing every function added since: `dni_features_json`,
   `dni_feature_run`, `dni_sqlite_features`, `dni_sqlite_run`, `dni_engine_stats`, `dni_search`,
   `dni_rag_session_start`, `dni_sqlite_rag`, `dni_string_free`.
4. **`.csproj` hand-links the three hostile static libs (SQLCipher / ONNX / llama) for `ios-*` only.**
   The `linux-bionic-arm64` block does **none** of it. llama.cpp and SQLCipher have **never** been
   linked for Android.
5. **`build/build-android-so.sh` is stale.** It references `e_sqlite3` (the csproj switched to
   `e_sqlcipher`) and builds no llama/onnx libs.
6. **No `android-arm64` target in `native/llama-shim/build-llama.sh`** (only `host` / `ios-arm64` /
   `iossimulator-arm64`).
7. **Build host = the Mac mini.** The Windows dev box has no C/C++ toolchain (per
   `docs/llama-nativeaot-ios-findings.md`); CI `ci-android` runs on macOS. Android native builds run as
   `steve` over `ssh steve-mac-mini`, same path as iOS.

## SP0 goal / definition of done

On an **arm64 Android emulator hosted on the Mac mini**: a full-stack `libdni.so` loads, the **current**
ABI round-trips over JNI, and llama RAG streams a real token — **or**, per the document-failures rule,
the failing layer is captured with its exact error plus a shipped fallback. **The gate is *answered*,
not necessarily all-green.**

Proof surface: **arm64 emulator on the Mac mini** (Apple-Silicon runs `arm64-v8a` system images
natively — closest no-extra-hardware match to a device; build + install + logcat over the existing ssh
path; CPU-only llama is fine).

## The staged probe ladder (decision: staged, not full-stack-in-one-shot)

The make-or-break unknown is whether NativeAOT for `linux-bionic-arm64` tolerates static-linking C++
(llama + ggml) and SQLCipher at all. Each rung is build → install → logcat `PASS:`, isolating its own
failure, mirroring the iOS host-probe philosophy.

- **S1 — bare bionic AOT.** `libdni.so` with **zero** native deps (engine + FFI features + raw-HTTP).
  Proves NativeAOT-bionic works at all. Round-trip: `dni_initialize` → `dni_features_json` →
  `dni_feature_run`.
- **S2 — +SQLCipher.** Add the bionic hand-link. Round-trip: `dni_sqlite_features` / `dni_sqlite_run`.
  **First unknown:** does `SQLitePCLRaw.bundle_e_sqlcipher` ship a `linux-bionic-arm64` static lib, or
  must SQLCipher be built for Android NDK? Resolve during S2.
- **S3 — +llama.cpp.** New `build-llama.sh android-arm64` target → static `.a`s; bionic hand-link
  `dni_llama` + NDK `libc++`. Round-trip: `dni_rag_session_start` streams ≥1 token.

## Components & work

### Native build
- **`native/llama-shim/build-llama.sh`** — add `android-arm64`: NDK CMake toolchain file,
  `ANDROID_ABI=arm64-v8a`, a pinned min API level, CPU-only GGML flags
  (`-DGGML_METAL=OFF -DGGML_BLAS=OFF -DGGML_ACCELERATE=OFF -DLLAMA_CURL=OFF`). Output:
  `build/android-arm64/lib/{libdni_llama,libllama,libggml,libggml-cpu,libggml-base}.a`. Pinned tag
  `b9542` (same as iOS).
- **`build/build-android-so.sh`** — rewrite. Flow: build llama android libs → `dotnet publish -r
  linux-bionic-arm64 -p:DisableUnsupportedError=true -p:UseAppHost=false` → copy `dni.so` (and any
  required runtime `.so` deps) into `android/app/src/main/jniLibs/arm64-v8a/`. Remove the stale
  `e_sqlite3` instructions.
- **`core/DotnetNativeInterop.NativeBridge/DotnetNativeInterop.NativeBridge.csproj`** — add
  `linux-bionic-arm64` ItemGroups mirroring the iOS blocks:
  - `DirectPInvoke Include="e_sqlcipher"` + `NativeLibrary` → the android SQLCipher static lib (path
    resolved in S2).
  - `DirectPInvoke Include="dni_llama"` + `NativeLibrary` per llama/ggml `.a` + `LinkerArg` for NDK
    `libc++`.
  - ONNX intentionally **excluded** here (SP3).

### JNI rewrite — RegisterNatives (decision)
- **`android/app/src/main/cpp/jni_bridge.c`** — `JNI_OnLoad` registers a `JNINativeMethod[]` table for
  `io/dotnetnativeinterop/transport/NativeBridge` (rename-proof; one row per function). Cover the full
  live ABI: `nativeInitialize`/`nativeShutdown`; FFI `sessionStart` (streaming) + shared
  `sessionCancel`/`sessionFree`; `httpStart`/`httpStop`; `brokerStart`/`brokerStop`; `featuresJson`;
  `featureRun`; `sqliteFeatures`; `sqliteRun`; `engineStats`; `search`; `ragSessionStart` (streaming);
  `sqliteRag`. **`ragSessionStart` shares `sessionCancel`/`sessionFree`** with the FFI session (per
  `dni.h`: RAG session ids cancel/free through the same exports) — one cancel + one free row in the
  table, not two. String-returning natives marshal `const char*` → `jstring` then call
  `dni_string_free`. **gRPC dropped** (no exports). Keep the existing `JNI_OnLoad` JavaVM cache + the
  per-thread `AttachCurrentThread` TLS-destructor pattern for the streaming callbacks.
- **`android/app/src/main/cpp/CMakeLists.txt`** — unchanged in shape (still builds `dni_jni`); verify
  the `-Werror` build stays clean with the larger table.
- **`android/app/.../transport/NativeBridge.kt`** — add `external` declarations for the new functions.
  Both `sessionStart` and `ragSessionStart` use the **same** `dni_token_cb` shape
  (`user_data, index, text, isFinal`), so the existing `FfiTokenListener` is reused for RAG — no new
  listener interface.

### Proof harness
- **Instrumented `androidTest`** that walks S1 → S3, asserting each round-trip and emitting `PASS:` lines
  to logcat (mirrors the iOS gate signal). Repeatable + runnable on the macOS CI emulator.
- The existing `InferenceScreen` / `InferenceViewModel` stay as the manual FFI-stream demo — **additive,
  not replaced.**

### CI
- `ci-android` currently builds the stale path. SP0 updates it to: build llama android libs → bionic
  publish → `:app:assembleDebug` → (stretch) run the instrumented gate test on an emulator. If the
  emulator step is too heavy for CI, SP0 lands the build + assemble and the gate test runs locally on
  the Mac mini.

## Failure policy (document-failures rule)

Any rung that won't link or run on bionic: **keep the artifact**, write
`docs/<sqlcipher|llama>-nativeaot-android-findings.md` with the exact error and the finding, and ship
the graceful fallback:
- RAG (S3 fail) → managed `ExtractiveLanguageModel` (the existing iOS fallback), no llama on Android.
- SQLCipher (S2 fail) → documented; the SQLCipher transport degrades / is marked unsupported on Android
  until resolved.

A documented failure + fallback is a **successful** SP0 outcome.

## Out of scope for SP0

- All Compose tabs (SP1+), ONNX/EVS (SP3), Lab (SP4), Latency (SP5).
- `GrpcUdsClient.kt` is now dead (gRPC is an excluded transport). **Flag for removal in SP1** — do not
  delete in SP0 (confirm-before-delete).
- Apple Foundation Models substitution (N/A on Android).

## Risks & unknowns

| Risk | Surfaced by | Mitigation |
|---|---|---|
| NativeAOT-bionic won't link static C++ (llama/ggml) | S3 | Staged ladder isolates it; document + extractive fallback |
| `bundle_e_sqlcipher` ships no bionic static lib | S2 | Build SQLCipher via NDK, or document + degrade transport |
| `XA1040` / experimental bionic toolchain quirks | S1 | Prove bare `.so` first; failures are toolchain, not app code |
| Required runtime `.so` deps missing in APK | S1 install | Enumerate publish output; copy all needed `.so` into jniLibs |
| Emulator ≠ device behavior for CPU llama | S3 | arm64 image (native, not translated); physical device is a later confirm if needed |
