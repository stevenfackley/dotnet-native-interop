# Binary-size cost ledger — what we can measure on Windows, and what still needs a Mac

**Date:** 2026-07-06
**Status:** ✅ **Windows-measurable numbers captured and committed** (`docs/size-ledger.json`, regenerated
by `tools/size-ledger/generate-manifest.ps1`). ⚠️ **The two numbers that matter most for "how big is the
app" — the current `ios-arm64` `dni.dylib` and the `linux-bionic-arm64` `libdni.so` — are NOT measured
here and are flagged pending a Mac/CI build.**

**Why this doc exists:** binary size is a recurring theme across this repo's gate docs — the ONNX gate
grew `dni.dylib` 6.9 MB → 23 MB, the INT8 quant gate shrank the sentence model 86.2 MiB → 21.8 MiB, and
Wave B (protobuf + BouncyCastle) added more still. Those numbers were scattered across five different
findings docs, each measured a different way, on a different day, before or after different features
landed. This gate collects them into one committed, versioned, reproducible ledger instead of leaving
them as prose scattered across the docs folder — **evidence + a generator, not a UI** (a later task
renders this).

## What we measured (win-x64, this box, today)

The `win-x64` `PublishAot=true` build is this repo's standard NativeAOT-size proxy (same methodology as
every other gate doc: no Mac/NDK toolchain on this Windows box, so `win-x64` is the accepted first-order
signal for "does it compile and how big is the managed code"). **For raw byte counts specifically, it is
a weaker proxy than usual** — see [the caveat](#the-methodology-gap-this-gate-exposes) below, which is
itself the most useful finding here.

**Exact command** (same toolchain workaround as every sibling gate — VS 2026/v18 prerelease
auto-detection can't resolve a usable MSVC toolset on this box):
```powershell
. spike/set-aot-env.ps1
dotnet publish core/DotnetNativeInterop.NativeBridge -r win-x64 -c Release `
  /p:PublishAot=true /p:IlcUseEnvironmentalTools=true
```
Publish was clean — **zero warnings, zero errors** — and produced:
```
core/DotnetNativeInterop.NativeBridge/bin/Release/net10.0/win-x64/publish/
  dni.dll                              8,324,608 B
  onnxruntime.dll                     11,569,696 B
  onnxruntime_providers_shared.dll        22,048 B
  e_sqlcipher.dll                      1,852,928 B
  dni.pdb / *.xml / *.lib             (dev-only, not shipped — excluded from totals)
```

Also measured directly off disk in this worktree (real Git LFS bytes, not pointers):
- `core/DotnetNativeInterop.Engine/Ai/assets/model.onnx` (FP32 sentence model) — `git lfs ls-files --size`
  and `Get-Item` agree: **90,405,214 bytes**.
- `ios/Frameworks/onnxruntime.xcframework/{ios-arm64,ios-arm64_x86_64-simulator}/onnxruntime.framework/onnxruntime`
  (the Swift-side EVS Core ML build of ORT — a separate copy from the NuGet package the .NET engine
  links) — **34,248,432 B** and **74,234,152 B** respectively.
- No `*.gguf` exists anywhere in this checkout (main or worktree) despite `.gitattributes` tracking the
  pattern — confirms `docs/llama-nativeaot-ios-findings.md`'s note that the Llama GGUF is fetched at
  build time, never committed.
- INT8 model (`tools/int8-quant/model.int8.onnx`) was **not** regenerated for this gate — it's a
  gitignored, build-time artifact (`tools/int8-quant/quantize.py`). Its exact size is pulled verbatim from
  `docs/int8-minilm-quant-findings.md` instead of re-run, and labeled `documented`, not `measured`.

The generator (`tools/size-ledger/generate-manifest.ps1`) does exactly the measuring above — LFS-pointer
detection, publish-output file sizes, GGUF search — and writes `docs/size-ledger.json`. Rows it can't
measure on this box (mobile `.dylib`/`.so`, the GGUF's real bytes) are hand-entered with a `documented` or
`pending` status and a note explaining why, rather than silently omitted.

## The numbers

| Component | Category | Platform | Bytes | Size | Status | Source |
|---|---|---|---:|---:|---|---|
| `dni.dll` (NativeAOT) | native-lib | win-x64 | 8,324,608 | 7.94 MiB | measured | measured-win-x64 |
| `onnxruntime.dll` (side-by-side) | framework | win-x64 | 11,569,696 | 11.03 MiB | measured | measured-win-x64 |
| `onnxruntime_providers_shared.dll` | framework | win-x64 | 22,048 | 0.02 MiB | measured | measured-win-x64 |
| `e_sqlcipher.dll` (side-by-side) | framework | win-x64 | 1,852,928 | 1.77 MiB | measured | measured-win-x64 |
| **win-x64 total runtime-needed native footprint** | *(sum, not a manifest row)* | win-x64 | **21,769,280** | **20.76 MiB** | measured | sum of the 4 rows above |
| `model.onnx` (FP32, all-MiniLM-L6-v2) | model | n/a | 90,405,214 | 86.22 MiB | measured | lfs |
| `model.int8.onnx` (INT8 dynamic quant) | model | n/a | 22,903,734 | 21.84 MiB | documented | int8-minilm-quant-findings.md |
| `onnxruntime.xcframework` — `ios-arm64` slice | framework | ios-arm64 | 34,248,432 | 32.66 MiB | measured | lfs |
| `onnxruntime.xcframework` — `ios-arm64_x86_64-simulator` slice | framework | ios-arm64-sim | 74,234,152 | 70.80 MiB | measured | lfs |
| Llama-3.2-1B-Instruct Q4_K_M GGUF | gguf | n/a | *null* (≈0.77 GB) | ≈0.77 GB | documented, **approx** | llama-nativeaot-ios-findings.md |
| `dni.dylib` — before ONNX static-link | native-lib | ios-arm64 | *null* (≈6.9 MB) | ≈6.9 MB | documented, **approx, STALE** | onnx-nativeaot-ios-findings.md |
| `dni.dylib` — with ONNX static-linked | native-lib | ios-arm64 | *null* (≈23 MB) | ≈23 MB | documented, **approx, STALE** | onnx-nativeaot-ios-findings.md |
| `libdni.so` — full native gate (NativeAOT + SQLCipher client + llama.cpp) | native-lib | linux-bionic-arm64 | *null* | — | **pending** | never measured anywhere |

(Verbatim from `docs/size-ledger.json`, generated 2026-07-06. `bytes: null` rows are intentional — the
source docs give only rounded MB figures with no exact byte count, and fabricating false precision would
misrepresent them as measured. See each row's `note` field in the JSON for the full caveat.)

## The methodology gap this gate exposes

Every other NativeAOT gate in this repo treats a `win-x64` `PublishAot=true` result as a faithful proxy
for `ios-arm64`/`linux-bionic-arm64` — same ILC, same trimming/reflection rules, so a compile/run PASS
transfers. **For binary size specifically, that transfer does not hold**, and this gate is what surfaces
it:

- **iOS statically links ONNX Runtime and SQLCipher into one `dni.dylib`** (`DirectPInvoke` +
  `NativeLibrary` against the vendored static archives — see
  `docs/onnx-nativeaot-ios-findings.md`/`nativeaot-android-gate-findings.md`). **Windows does not** — the
  same `Microsoft.ML.OnnxRuntime`/`SQLitePCLRaw.bundle_e_sqlcipher` packages resolve their `win-x64`
  native assets as ordinary **side-by-side DLLs** next to `dni.dll` (that's why `onnxruntime.dll` and
  `e_sqlcipher.dll` show up as separate files in the publish output above, not folded into `dni.dll`).
  So **`dni.dll`'s 7.94 MiB is *not* comparable to `dni.dylib`'s 23 MB** — it's missing the ~11–12 MiB that
  iOS bakes directly into the single shared library.
- **The win-x64 *total* runtime-needed footprint (dni.dll + its side-by-side native DLLs, 20.76 MiB) lands
  in the same ballpark as the historical iOS 23 MB single-file number** — which is a reasonable sanity
  check that the *content* is comparable even though the *packaging* (one file vs. four) differs. Read
  this as "roughly the right order of magnitude," not as a validated equivalence — the two numbers were
  captured a month apart, before/after several other features landed (next point).
- **`llama.cpp`/`ggml` are not linked at all on `win-x64`** — the `.csproj`'s `NativeLibrary`/`LinkerArg`
  entries for the llama shim are conditioned on `RuntimeIdentifier.StartsWith('ios')` or
  `== 'linux-bionic-arm64'` only. So this Windows measurement is **missing 100% of the on-device LLM
  static libraries** that both mobile RIDs actually ship. There is no cheap Windows proxy for this — it
  needs a real `ios-arm64` or `android-arm64` link, which needs CMake + the NDK/Xcode toolchain, neither
  of which exists on this box (confirmed: no `cmake`, no `clang` on this machine).
- **The one real historical mobile number (`dni.dylib` 6.9 MB → 23 MB) is now stale.** It was captured
  2026-06-05, the day *before* `llama.cpp` was added (2026-06-06) and a month *before* Google.Protobuf +
  BouncyCastle (Wave B, 2026-07-05) — both of which now also statically link into the same `dni.dylib` on
  `ios-arm64`. The current `ios-arm64` dylib has never been re-measured since. **Android's `libdni.so` has
  *never* been byte-measured at all** — `docs/nativeaot-android-gate-findings.md` proves the gate links and
  runs, but records no size.

**Net: this Windows proxy is good for "does it compile," bad for "how big is it."** Size-sensitive
decisions (e.g. "is INT8 worth wiring in to shrink the shipped app") need the real mobile-RID number, not
the win-x64 stand-in.

## Mobile-RID caveat (what's pending, and how to unblock it)

| Needed | Why it's pending here | How to get it |
|---|---|---|
| Current `dni.dylib` size, `ios-arm64`, with everything (`ONNX + SQLCipher + llama.cpp + BouncyCastle + Protobuf`) statically linked | No Mac, no Xcode/clang, on this Windows box | Run the existing `build/build-ios-framework-device.sh` on the Mac mini, `ls -l` the produced `dni.dylib` (or the `.xcframework` slice), commit the number back into `docs/size-ledger.json` as a `measured-ios-arm64` row |
| Current `libdni.so` size, `linux-bionic-arm64`, with `NativeAOT + SQLCipher client + llama.cpp` statically linked | No CMake, no NDK, on this Windows box (confirmed absent) | Run `build/build-android-so.sh` (Mac mini or a `macos-latest` CI runner, per `ci-android.yml`), `ls -l libdni.so`, add a `measured-android-arm64` row |
| Real GGUF byte count (Llama-3.2-1B-Instruct Q4_K_M) | Never committed (fetched at build time); not present on this box | Fetch it once on the build host per `docs/llama-nativeaot-ios-findings.md`, `Get-Item`/`ls -l` it, record the exact byte count |
| `libonnxruntime.so` / `libe_sqlcipher.so` sizes as shipped in Android `jniLibs/` (separate dynamic `.so`s, not statically linked like iOS) | Extracted from `.aar`s by `build-android-so.sh`, which needs the same Mac/NDK toolchain | Same Mac/CI run as `libdni.so` above; these are a different, additional set of files from `libdni.so` itself |

None of this needs new code or a new spike — it's the exact same build scripts this repo already has
(`build-ios-framework-device.sh`, `build-android-so.sh`), just run once with a `Get-Item`/`stat -f%z`
afterward and the result appended to the manifest.

## The manifest + generator

`docs/size-ledger.json` is the committed ledger. Schema (see the file's own `$schema`/`schemaNotes`
fields for the authoritative version):

```
{
  id, component, category ("native-lib" | "model" | "framework" | "gguf"),
  platform (RID or "n/a"), bytes (number | null), humanSize,
  status ("measured" | "documented" | "pending"), source, asOf, note
}
```

`tools/size-ledger/generate-manifest.ps1` regenerates it:
- Measures the `win-x64` publish output, `model.onnx`, and the `onnxruntime.xcframework` slices directly
  off disk **only when real bytes are present** (it detects and skips Git LFS pointer stubs rather than
  reporting a pointer's ~130-byte size as if it were the model).
- Searches the repo for any `*.gguf` (there normally isn't one).
- Falls back to hand-entered `documented`/`pending` rows — with an explanatory `note` — for anything that
  needs a Mac/NDK toolchain this box doesn't have.
- `-Publish` switch re-runs the `win-x64` AOT publish first (sourcing `spike/set-aot-env.ps1`); without it,
  the script measures whatever publish output already exists.

```powershell
pwsh tools/size-ledger/generate-manifest.ps1 -Publish
```

Published/generated artifacts (the `publish/` dir, any regenerated INT8 model, `*.mstat` ILC size
reports) are **not** committed — `bin/`/`obj/` are already gitignored repo-wide, and `tools/int8-quant/*.onnx`
was already gitignored by the prior gate. Only the manifest + generator + this doc are new committed
files.

## Verdict / implications

- **Windows can answer "does the managed code compile and roughly how big is it," not "how big is the
  shipped mobile binary."** The packaging difference (side-by-side DLLs on Windows vs. one statically-linked
  dylib/so on mobile) means `dni.dll`'s byte count alone understates the real cost; the *sum* of Windows'
  side-by-side native DLLs is a better (if still rough) stand-in.
- **The one real mobile data point that exists (iOS 6.9→23 MB) is a month stale** and predates three
  features that now also link into the same file (llama.cpp, BouncyCastle, Google.Protobuf). Treat it as
  a lower bound, not a current answer, for "how big is `dni.dylib` today."
- **Android's native binary size is a complete unknown.** The SP0 gate doc proves it *links and runs*, not
  how big it is. This is the single most actionable gap this ledger surfaces — one `build-android-so.sh`
  run + `ls -l` on the Mac mini would close it cheaply.
- **INT8 quantization (86.2 MiB → 21.8 MiB, already gated and passed) is the largest lever currently on the
  table** for shrinking whatever ships the sentence model — but it's the model asset, which is a much
  smaller and more tractable win than closing the "what does the actual dylib/so weigh" gap above.
- This ledger is a data gate, not a UI: nothing here changes app behavior, wires INT8 into a shipping path,
  or renders anything on-device. A later task can read `docs/size-ledger.json` to build a "binary size"
  screen once the pending Mac/CI rows are filled in.

## Reproduce

```powershell
# win-x64 measurements + manifest regeneration (this box, no Mac/NDK needed):
pwsh tools/size-ledger/generate-manifest.ps1 -Publish

# Manual equivalent of the publish step alone:
. spike/set-aot-env.ps1
dotnet publish core/DotnetNativeInterop.NativeBridge -r win-x64 -c Release `
  /p:PublishAot=true /p:IlcUseEnvironmentalTools=true

# Check LFS byte status for the model/framework assets:
git lfs ls-files --size
```

To fill in the pending mobile rows (Mac mini, per `docs/ios-build-deploy-runbook.md` /
`docs/nativeaot-android-gate-findings.md`):
```bash
./build/build-ios-framework-device.sh      # then: ls -l <path-to-dni.dylib or the built .xcframework>
./build/build-android-so.sh                # then: ls -l libdni.so, and the extracted jniLibs/*.so files
```
