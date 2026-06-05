# Running ONNX Runtime + Core ML on iOS for edge search — what worked

**Date:** 2026-06-05
**Outcome:** ✅ **Works.** ONNX Runtime with the Core ML execution provider links into the app and runs
all-MiniLM inference on the iOS Simulator, and the Swift + Core ML embedding agrees with the .NET/CPU
embedding to **float epsilon**. This is the feasibility gate (Task 1) for the Edge Vector Search (EVS)
module, and the contrast to Phase 3's in-engine NativeAOT path (`docs/onnx-nativeaot-ios-findings.md`).

This is the **second** on-device inference path in the app, intentionally different from Phase 3:

| | AI tab (Phase 3) | Manuals tab (EVS) |
|---|---|---|
| Who embeds | the .NET NativeAOT engine, in-process (ONNX statically linked into `dni.dylib`) | **Swift**, via ONNX Runtime + **Core ML / ANE** |
| Corpus | embedded at launch, in memory | **prebuilt SQLite index**, compiled offline by a .NET publisher |
| .NET's role | runtime engine (FFI) | **build-time data pipeline** only |

## How ONNX Runtime gets onto iOS (the recipe)

1. **Pod / version.** Vendor the **full** C/C++ build, `onnxruntime-c` — *not* `onnxruntime-mobile-c`
   (the mobile build strips execution providers). The latest published pod in the 1.20 line is
   **`1.20.0`**; `1.20.1` (the engine's NuGet `Microsoft.ML.OnnxRuntime` version) is **not** published as
   a pod. The patch difference is immaterial here — see the agreement result below.

   The project uses xcodegen with no CocoaPods integration, so CocoaPods is used only to *download* the
   artifact, then the xcframework is vendored directly:
   ```ruby
   # /tmp/ort-fetch/Podfile
   platform :ios, '17.0'
   install! 'cocoapods', :integrate_targets => false
   target 'OrtFetch' do
     pod 'onnxruntime-c', '1.20.0'
   end
   ```
   `pod install` drops `Pods/onnxruntime-c/onnxruntime.xcframework`.

2. **Trim to iOS, vendor via LFS.** The pod ships 3 slices (`ios-arm64`, `ios-arm64_x86_64-simulator`,
   `macos-arm64_x86_64`) at ~177 MB. Re-create an iOS-only xcframework (~104 MB) and commit it under
   `ios/Frameworks/onnxruntime.xcframework` via Git LFS (`*/onnxruntime filter=lfs`):
   ```bash
   xcodebuild -create-xcframework \
     -framework <pod>/ios-arm64/onnxruntime.framework \
     -framework <pod>/ios-arm64_x86_64-simulator/onnxruntime.framework \
     -output onnxruntime.xcframework
   ```
   The Core ML EP is present in this build — `nm` shows `_OrtSessionOptionsAppendExecutionProvider_CoreML`.

3. **Call it from Swift via a tiny Objective-C C-API wrapper.** Rather than add the separate
   `onnxruntime-objc` pod, `ios/Shared/EdgeSearch/EvsOrt.{h,m}` wraps the ONNX Runtime **C API**
   (`#import <onnxruntime/onnxruntime_c_api.h>`, `<onnxruntime/coreml_provider_factory.h>`), enables the
   Core ML EP with `OrtSessionOptionsAppendExecutionProvider_CoreML(opts, 0)`, runs the transformer, and
   mean-pools + L2-normalizes to a 384-d vector (the same math as the engine's C# `OnnxTextEncoder`).

## Three integration gotchas (each cost one gate iteration)

1. **Link libc++.** ONNX Runtime's iOS framework is a **static C++ library**. A pure Swift/Obj-C app does
   not link the C++ standard library, so the link fails with hundreds of undefined `std::__1::basic_string`
   / `basic_istream` / `basic_ostream` symbols. Fix: `OTHER_LDFLAGS = -lc++` on the app target.

2. **`NS_SWIFT_NAME` the wrapper.** The Obj-C selector `embedInputIds:attentionMask:length:out:error:`
   imports into Swift as `embedInputIds(_:attentionMask:length:out:)`, not `embed(inputIds:…)`. Add
   `NS_SWIFT_NAME(embed(inputIds:attentionMask:length:out:))` to get the intended call site.

3. **Use a build that exports `dni_search`.** Unrelated to ONNX, but worth noting for anyone reusing a
   prebuilt `dni.xcframework`: it must be a post–Phase-3 build (the one that added the `dni_search` FFI
   export), or the app link fails with `Undefined symbol: _dni_search`.

## Cross-runtime agreement — the surprising part

The publisher (.NET, `Microsoft.ML.OnnxRuntime` 1.20.1, CPU) and the client (Swift, ONNX Runtime 1.20.0,
Core ML EP) embed with the **same** `model.onnx` + `vocab.txt` and a tokenizer ported byte-for-byte. On the
Simulator (`EdgeSearchAgreementTests`):

- The Swift tokenizer's `input_ids` are **identical** to the C# tokenizer's.
- The query's top-ranked chunk **matches** the publisher's (`hvac-001#2` for "compressor won't start").
- The Swift/Core ML query vector vs the .NET/CPU vector: **max abs per-dimension delta = 1.19e-07** — i.e.
  float32 machine epsilon. The two runtimes produce numerically identical embeddings.

(On the Simulator the Core ML EP runs on CPU/GPU; on a physical device's Apple Neural Engine the delta may
be larger, but cosine ranking is dominated by far coarser differences, so search results are unaffected.
The 0.70 similarity threshold and metadata filters operate on these scores.)

## Reproduce

`ios/Tests/EdgeSearchSpikeTests.swift` (linkage + one Core ML inference) and
`ios/Tests/EdgeSearchAgreementTests.swift` (tokenizer parity, ranking, vector delta) both run under
`xcodebuild test -only-testing:DotnetNativeInteropTests` on an iPad Simulator. Both pass.
