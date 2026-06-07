# Running the NativeAOT engine on Android — the SP0 native gate

**Date:** 2026-06-07
**Outcome:** ✅ **Works.** The complete .NET 10 **NativeAOT** engine — full C/JNI ABI, **SQLCipher**,
and **llama.cpp** on-device generation — links into one `linux-bionic-arm64` shared library
(`libdni.so`) and runs on a real **arm64 emulator**. Proven by the `NativeGateTest` instrumented suite:

```
PASS: dni_initialize == 0
PASS: dni_features_json -> 20 features
PASS: dni_feature_run(collection-expressions).ok == true
PASS: dni_llama_probe ok, tokens=8, sample= Paris. It is the largest city in
PASS: dni_sqlite_probe ok, cipher=4.5.2 community, roundtrip=sqlcipher-roundtrip
```

This is the Android counterpart to the iOS gates (`llama-nativeaot-ios-findings.md`,
`onnx-nativeaot-ios-findings.md`). The same engine and the same hostile C/C++ static libs link on both
platforms, but the **mechanics differ** in ways that are not obvious until they fail at `dlopen`:

| | iOS | **Android (this doc)** |
|---|---|---|
| Linker driver | Apple Clang → `ld64` | **NDK `aarch64-linux-android29-clang` → `ld.lld`** |
| Archive ordering | order-insensitive (ld64 rescans) | **single-pass → needs `--start-group`** |
| Shared-lib name | `dni.dylib` (in a framework) | **`libdni.so` (must stamp `DT_SONAME`)** |
| SQLCipher | static `e_sqlcipher.a` (`DirectPInvoke`) | **dynamic `libe_sqlcipher.so` in `jniLibs`** |
| C++ runtime (for llama) | system `libc++` via `-lc++` | **NDK `libc++_static.a` + `libc++abi.a`** |

## The staged probe ladder

The gate was brought up in three stages, each verified on-device before the next, so a failure pinpoints
exactly which native dependency broke:

- **S1 — FFI ABI:** the bare bionic image loads via JNI and round-trips its full C ABI.
- **S2 — SQLCipher:** an encrypted DB opens, keys, and round-trips a row.
- **S3 — llama.cpp:** a real GGUF model loads and generates tokens.

S2/S3 use **path-parameterized probes** (`dni_sqlite_probe(path)`, `dni_llama_probe(path)`) declared in a
**gate-only header** (`abi/dni_gate_probe.h`, kept out of the frozen `dni.h` app ABI and included only by
the JNI shim). They take a caller-supplied path so the test controls a writable location — deliberately
isolating *"does the native library link and run?"* from the app's real data plumbing (Android
asset extraction + a writable temp dir), which belongs to later parity work.

## S1 — the bionic image loads through JNI

1. **NDK clang as the NativeAOT linker.** `-p:CppCompilerAndLinker=<NDK>/aarch64-linux-android29-clang`.
   Apple Clang cannot target bionic and lacks a usable `-fuse-ld=lld`. The NDK prebuilt host dir is
   `darwin-x86_64` even on Apple Silicon (runs via Rosetta). `-p:DisableUnsupportedError=true` suppresses
   XA1040 (Android NativeAOT is experimental in .NET 10).

2. **Stamp `DT_SONAME=libdni.so`** — the single least-obvious fix. The published file is `dni.so`; we copy
   it to `libdni.so`. Without a soname, when the JNI shim (`libdni_jni.so`) links against `libdni.so` by
   path, `ld.lld` copies that **build-host absolute path** into the shim's `DT_NEEDED`. Android's loader
   searches only the APK's `lib/<abi>/` dir, so the absolute Mac path is unresolvable → `UnsatisfiedLinkError`
   at `dlopen`. Fix: a bionic-scoped `<LinkerArg Include="-Wl,-soname,libdni.so" />` in the `.csproj`, so the
   shim records `DT_NEEDED: [libdni.so]` and the loader finds it in the APK.

3. **The JNI shim imports + links `libdni.so`.** `CMakeLists.txt` declares it an `IMPORTED` target and links
   it, so `ld.lld` resolves the `dni_*` symbols under the NDK's default `-Wl,--no-undefined`. (Rebuilding the
   `.so` does **not** relink the shim — CMake tracks the import by path, not content — so force-clean
   `.cxx`/`intermediates/cxx` after swapping it.) `JNI_OnLoad` uses a `RegisterNatives` table, decoupling the
   C symbols from the Kotlin package name (rename-proof).

## S2 — SQLCipher as a dynamic `.so`

iOS *must* static-link `e_sqlcipher.a` (no app-facing dynamic libs); Android takes the idiomatic dynamic
path. SQLitePCLRaw ships the Android native **only inside the `.android` `.aar`** (`jni/arm64-v8a/libe_sqlcipher.so`)
— there is no loose `.so` and no `.a`. The build script extracts it into `jniLibs` alongside `libdni.so`; the
bionic image's ordinary P/Invoke (`DllImport "e_sqlcipher"`) resolves it from the APK lib dir at runtime. **No
`.csproj` hand-link is needed** — the iOS-only static-link conditions already exclude bionic. The decisive
probe signal is `PRAGMA cipher_version` (non-empty ⇒ `e_sqlcipher`, not plain `e_sqlite3`).

## S3 — llama.cpp + the C++ runtime trap

`build-llama.sh android-arm64` cross-compiles llama/ggml + the `dni_llama` shim to static libs via the NDK
toolchain file (`-DGGML_NATIVE=OFF` to drop the host `-march=native`, `-DGGML_OPENMP=OFF`), pinned to the same
tag as iOS (**b9542**) so the shim C-API + C# `LlamaLanguageModel` stay in lockstep. The `.csproj` hand-links
the archives for `linux-bionic-arm64`, mirroring iOS — but two things bite that don't on iOS:

- **No app-facing system libc++.** The .NET NativeAOT bionic runtime links only the *sliver* of libc++ it uses
  itself, so **~137** of llama/ggml's libc++ symbols (`typeinfo for std::length_error`, `__gxx_personality_v0`,
  …) are left undefined. They **slip past the link** because NativeAOT's `.so` link does not pass
  `-Wl,--no-undefined`, then fail at `dlopen` ("cannot locate symbol `_ZTISt12length_error`"). Fix: link the
  NDK static C++ runtime — `-l:libc++_static.a -l:libc++abi.a`. (The base `_Unwind_*` unwinder is already
  provided by the .NET runtime, so no `libunwind`.) These resolved the undefined count from 137 → 0 with no
  duplicate-symbol clashes (the .NET-provided and llama-needed symbol sets are disjoint).

- **`ld.lld` is single-pass.** Unlike Apple's `ld64`, it won't rescan earlier archives, and llama/ggml/libc++
  reference each other cyclically. The fix is to wrap the whole native stack in one
  `-Wl,--start-group … -Wl,--end-group`, passing the archives as ordered `LinkerArg`s so llama precedes libc++
  on the link line regardless of MSBuild's `NativeLibrary`-vs-`LinkerArg` ordering.

The probe drives the *real* `LlamaLanguageModel` (C# → `DirectPInvoke` → static llama), proving the full
managed→native chain, not just that the symbols resolve.

## Running the on-device gate

- **arm64 emulator** on the Mac mini (near-native on Apple Silicon). Instrumented test via
  `:app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.dotnetnativeinterop.NativeGateTest`.
  The signal is `PASS:` lines on logcat tag `NativeGate`, mirroring the iOS gate.
- **SELinux concession (emulator-only).** An app (`untrusted_app`) cannot read the pushed GGUF at
  `/data/local/tmp` (`shell_data_file` denial), so the gate run does `adb root; adb shell setenforce 0`
  (permissive) before `adb push …/model.gguf /data/local/tmp/gate.gguf`. This is a throwaway-emulator test
  shortcut; the shipping app will load the model from its own asset/files dir (later parity work), needing no
  such concession.

## CI

`ci-android.yml` is **build-only**: it builds the complete gate `.so` (NativeAOT bionic + llama + SQLCipher)
and assembles the debug APK on a `macos-latest` runner (which ships cmake and the NDK darwin prebuilt). The
instrumented gate is **not** run in CI — an arm64 emulator is impractical on x86_64/macOS runners — so the
authoritative on-device proof is the local emulator run above.

## Reproduce

```bash
# On the build host (cmake + NDK 27 + ANDROID_NDK_HOME set):
./build/build-android-so.sh            # builds llama (b9542) + libdni.so + extracts libe_sqlcipher.so
cd android && ./gradlew :app:assembleDebug
# On-device gate (arm64 emulator running):
adb root && adb shell setenforce 0 && adb push <model>.gguf /data/local/tmp/gate.gguf
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.dotnetnativeinterop.NativeGateTest
```
