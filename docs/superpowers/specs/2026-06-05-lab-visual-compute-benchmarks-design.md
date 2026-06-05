# Lab — Visual Compute + Heavy Benchmarks (Phase 1) — Design

**Date:** 2026-06-05
**Status:** Approved — ready for implementation plan.
**Author:** pairing session

## Context

`DotnetNativeInterop` embeds one NativeAOT .NET 10 engine in a native iOS app, reached over three
interop transports (in-process **FFI**, loopback **raw-HTTP**, encrypted **SQLCipher**). The current
demo payload is a live C# 14 / .NET 10 feature showcase: 20 features in two tiers (language syntax +
runtime/BCL), each executed in-AOT and shown with timing. One visual feature already exists — a static
128×128 grayscale Mandelbrot computed pixel-by-pixel in C# and shipped to Swift as the compact string
`"WxH:base64"` over the normal feature path.

The user wants the POC expanded with far more "wow factor": pushing the limits of what NativeAOT .NET
can do on-device, with rich graphics and charts. This is decomposed into three phases (own spec each):

1. **(this spec) Lab — Visual Compute + Heavy Benchmarks.** Eye-candy + throughput showpieces.
2. Latency Lab + Engine Telemetry + deep About. (instrumentation)
3. Onboard AI — .NET-driven ONNX semantic search + Apple Foundation Models chat. (headline)

Phase order (user decision, 2026-06-05): **wow demos first**, AI last (riskiest native lift).

## Goals

- Add interactive, animated, GPU-free visual demos — every pixel computed in C# inside the NativeAOT
  library — and heavy compute benchmarks charted against a scalar/single-thread baseline.
- Surface a live "frames-per-second / ms-per-frame" readout on animated demos, so the eye-candy doubles
  as a transport-cost demonstration (the project's core thesis).
- Add **zero** new C ABI surface and **zero** new native libraries. The frozen `abi/dni.h` and the Swift
  bridging header stay untouched; only managed engine code and Swift UI change → exactly **one** Mac
  xcframework rebuild for the whole phase.

## Non-goals (this spec)

- Engine/GC/heap telemetry and the latency-lab upgrades → **Phase 2**.
- ONNX semantic search and Apple Foundation Models → **Phase 3**.
- Audio/mic FFT, Android. (FFT and a particle sim are in-phase **stretch** only — see below.)
- Changing the existing 20 features, the streaming path, Compare, or Latency tabs.

---

## Key design decision: command-in-id (no ABI change)

All three transports already funnel every per-feature run through one method,
`LanguageFeatureCatalog.Run(string id)` — verified in the FFI (`dni_feature_run`), raw-HTTP
(`GET /feature/run/{id}`, which already `Uri.UnescapeDataString`es the id), and SQLCipher
(`dni_sqlite_run`) exports. The result is just a string in the `FeatureRun{id,result,elapsedMs,ok}`
envelope.

So the new demos are driven by **encoding parameters into the id**, carried over the existing run path.
The id grammar uses only RFC-3986 *unreserved* characters (`A–Z a–z 0–9 - _ . ~`) so it needs **no
percent-encoding on any transport** and is safe as a C string and a SQLite key:

```
viz-mandelbrot~cx_-0.7436~cy_0.1318~zoom_220~iters_600~w_256~h_256
viz-raymarch~angle_1.57~w_256~h_256
bench-matmul~max_512
bench-parallel~size_512
```

- Fields separated by `~`; each field is `key_value`; the leading token is the demo name.
- `Run(id)`: if `id` contains `~`, route to the new `ShowcaseCommand` dispatcher; otherwise the existing
  catalog lookup is unchanged.
- Output reuses the existing `FeatureRun` envelope. For commands, `ok = produced a valid result without
  throwing`; `expected` is unused. The deterministic `expected == result` self-check of the 20 existing
  catalog features is **untouched**.

**Payoff:** because every transport routes through `Run`, the Lab gets a transport toggle for free — and
an animated demo's achievable FPS visibly collapses when frames are routed through the encrypted
SQLCipher DB instead of FFI. The visual wow demonstrates the transport-cost thesis directly. FFI is the
default for animated demos (SQLCipher writes one `feature_runs` row per call — fine, but slow by design).

---

## Engine changes (`core/DotnetNativeInterop.Engine/Showcase/`)

All AOT-safe: no reflection; JSON via a source-generated context like the existing `FeaturesJsonContext`.

### Command dispatch
- `ShowcaseCommand` — parses the `name~key_value~…` grammar into a name + a small typed params bag, with
  per-key defaults and clamping (e.g. `w`/`h` capped to a safe max, `iters` bounded). Dispatches to a
  renderer or benchmark and builds a `FeatureRun`. Unknown name → `ok=false` with an explanatory result.
- `LanguageFeatureCatalog.Run(id)` gains one branch: `id.Contains('~') → ShowcaseCommand.Run(id)`.

### Visual renderers (RGB; every pixel in C#)
Output format extends the existing visual contract to carry channel count: `"WxHxC:base64"` where `C`
is `1` (grayscale, existing) or `3` (RGB, new), row-major 8-bit.

- **Fractal Explorer** (hero) — extend `FractalRenderer` with a parametric, colorized Mandelbrot:
  `Render(double centerX, double centerY, double zoom, int maxIter, int size)` producing smooth
  escape-time-colored RGB. The existing deterministic grayscale `viz-fractal` catalog entry and its
  `Render()`/`RenderBase64()` stay intact (Compare/Latency depend on it).
- **Raymarcher** — `Raymarcher.Render(double cameraAngle, int w, int h)`: a signed-distance-field scene
  (sphere + ground plane, Lambert shading + soft shadow), camera orbiting by `cameraAngle` → RGB. The UI
  sweeps `angle` to rotate it.
- *(stretch)* **ParticleField** — a small N-body/gravity step rendered to RGB.

### Heavy benchmarks (structured series JSON in `result`)
A benchmark returns this shape (new `ShowcaseJsonContext`), placed in `FeatureRun.Result`:

```json
{ "kind": "benchmark", "title": "SIMD matrix multiply",
  "series": [ { "name": "scalar", "points": [ { "x": 64, "y": 1.2 } ] },
              { "name": "SIMD",   "points": [ { "x": 64, "y": 0.2 } ] } ],
  "summary": [ { "label": "peak GFLOPS", "value": "41.8" },
               { "label": "speedup",     "value": "6.8×" } ] }
```

C# records: `BenchmarkPayload(string Kind, string Title, IReadOnlyList<BenchmarkSeries> Series,
IReadOnlyList<SummaryStat> Summary)`, `BenchmarkSeries(string Name, IReadOnlyList<BenchmarkPoint>
Points)`, `BenchmarkPoint(double X, double Y)`, `SummaryStat(string Label, string Value)`.

- **SIMD matmul GFLOPS** — naive scalar vs `Vector<T>` / `TensorPrimitives` matrix multiply, swept over
  sizes (e.g. 64→512). Series = GFLOPS per size per method; summary = peak GFLOPS + speedup.
- **Parallel scaling** — render the Mandelbrot single-threaded vs `Parallel.For` at 1→`Environment.
  ProcessorCount` threads. Series = time + speedup vs thread count; summary = max speedup + core count.
- *(stretch)* prime sieve / sort-millions throughput.

---

## iOS changes (`ios/Shared/Lab/`; `Shared/` is folder-globbed, so files auto-include on `xcodegen generate`)

- **`LabView`** — a new **"Lab"** tab (SF Symbol `cpu`). `NavigationStack` + `List` with sections
  *Visual* and *Benchmarks*; each row navigates to its detail screen. `RootTabView` inserts the tab
  (6 tabs total: Dashboard · Features · Lab · Compare · Latency · About — fine on iPad).
- **`LabViewModel`** (`@MainActor ObservableObject`) — holds the existing `[TransportKind: FeatureService]`
  and the selected transport; `run(_ command: String) async -> FeatureResult?`. Reuses the existing
  `FeatureService` protocol (the command is just an id). Mirrors `FeaturesViewModel`'s service handling.
- **`VisualFeature`** (extend) — decode both `"WxH:base64"` (C=1 grayscale) and `"WxHxC:base64"` (C=1 or
  3) into a `CGImage` (`CGColorSpaceCreateDeviceGray` / `CGColorSpaceCreateDeviceRGB`).
- **`RenderCanvasView`** — drives frames via `TimelineView`, renders each frame on a background `Task`
  (Swift 6 strict-concurrency: transport work off the main actor, UI state on `@MainActor`), decodes the
  image, and shows a live readout: **FPS · ms/frame · W×H · transport**.
- **`FractalExplorerView`** — `RenderCanvasView` + `MagnificationGesture` (pinch-zoom) + `DragGesture`
  (pan) updating `centerX/centerY/zoom`, an iteration `Slider`, a "Dive" autoplay toggle, and a transport
  picker (FFI default).
- **`RaymarcherView`** — `RenderCanvasView` with autoplay rotation (sweep `angle`) + FPS readout.
- **`BenchmarkDetailView`** + **`BenchmarkChart`** — a generic, config-driven screen: run the bench
  command, decode `BenchmarkPayload`, draw Swift Charts (`LineMark`/`BarMark`, available on the iOS 17
  deployment target) with a legend per series, plus a summary-stat row. Used by both Matmul and Parallel.
- **`ShowcaseModels.swift`** — Codable mirrors of `BenchmarkPayload` / `BenchmarkSeries` /
  `BenchmarkPoint` / `SummaryStat` (camelCase, decoded with the existing `JSONDecode` helper).

No `project.yml` change required (folder glob); no new framework dependency (Swift Charts is a system
framework, already imported by `LatencyView`).

## Data flow

`LabView` → tap demo → detail view.
**Visual:** `TimelineView` tick → `LabViewModel.run("viz-…~params")` (background) → `FeatureResult.result`
= `"WxHxC:base64"` → `VisualFeature.image` → display + FPS/ms readout. Gestures/sliders mutate params →
next tick re-renders.
**Benchmark:** tap Run → `LabViewModel.run("bench-…~params")` (background) → decode `result` as
`BenchmarkPayload` → `BenchmarkChart` + summary stats.

## Error handling

- `ShowcaseCommand` clamps/validates params (size caps, iteration bounds) and never throws across the ABI;
  unknown demo or bad params → `ok=false` with a human-readable `result`, surfaced (not a blank).
- Swift decode failure (malformed `WxHxC:` payload or benchmark JSON) → `ContentUnavailableView` /
  visible error, mirroring the existing `FractalImageView` fallback and `FeaturesViewModel.errorMessage`.
- A failed/zero-frame render shows the last good frame + an error line rather than a black flash.

## Testing & verification

- **Engine (Windows, no device):** throwaway console in `%TEMP%` referencing
  `DotnetNativeInterop.Engine.csproj` — call `Run("viz-mandelbrot~w_64~h_64~iters_50")` and assert the
  result parses as `WxHxC:base64` with `W*H*C` decoded bytes; call `Run("bench-matmul~max_128")` and
  assert it decodes as a `BenchmarkPayload` with ≥2 series and a plausible `elapsedMs`. Delete after.
- **Managed build:** `dotnet build DotnetNativeInterop.slnx -c Release` → 0 errors/warnings.
- **iOS (Mac mini — engine changed, so the framework must be rebuilt):** build as `steve` over SSH,
  background the long publish per build prefs; `bash build/build-ios-framework.sh`, then build/sign/install
  the unified app to the iPad and confirm: Lab tab present; Fractal Explorer pinch-zoom/pan + "Dive"
  autoplay with a live FPS readout; Raymarcher rotating with FPS; Matmul and Parallel-scaling charts with
  summary stats; transport toggle visibly drops FPS on SQLCipher vs FFI.

## Risks

- **Sustained software-render FPS at 256² RGB over FFI.** Per-frame cost = render + base64 + Swift decode
  + `CGImage`. Mandelbrot 256²×600 iters in AOT plus ~196 KB base64 should land ~10–30 fps. `w`/`h` are
  live params, so the demo drops to 192²/160² on-device if sluggish. No ABI or native-lib risk.
- **Swift 6 strict concurrency** on the frame loop — renders on a background `Task`, UI state on
  `@MainActor` (the existing services already use `Task.detached`).

## Follow-on (named, not built here)

- **Phase 2:** Latency Lab (3-transport overlay + p50/p95/p99 + throughput; time-series/jitter;
  payload-size scaling), live engine telemetry (GC/heap/threads via one new `dni_engine_stats` export),
  deep About.
- **Phase 3:** .NET-driven ONNX embeddings → on-device semantic search; Apple Foundation Models chat
  (gated, eligible device confirmed).
