# Android Lab Tab — Visual Compute + Heavy Benchmarks — Design

**Date:** 2026-06-07
**Status:** Approved — ready for implementation plan.
**Cycle:** Android 1:1 parity program — the last gated tab.

## Context

The Android app is a faithful 1:1 port of the iOS app: one NativeAOT .NET 10 engine (`libdni.so`)
reached over three interop transports (in-process **FFI**, loopback **raw-HTTP**, encrypted
**SQLCipher**), driven from a Compose UI. Dashboard, Features, Compare, Latency, About, AI, and Manuals
(EVS) tabs are live. **Lab** is the last gated placeholder.

The iOS Lab tab (`ios/Shared/Lab/`, spec `2026-06-05-lab-visual-compute-benchmarks-design.md`) is
GPU-free visual compute + heavy-compute benchmarks: a colorized Mandelbrot explorer, a CPU raymarcher,
and two benchmark charts — every pixel and every FLOP computed in C# inside the engine.

### Key finding: no native work required

iOS drives all four Lab demos through the **existing** per-feature run path by encoding parameters into
the feature id (the **command-in-id** grammar), routed by `LanguageFeatureCatalog.Run(id)` →
`ShowcaseCommand.Run(id)` when the id contains `~`. `ShowcaseCommand`, `FractalRenderer`, `Raymarcher`,
and `Benchmarks` are already compiled into the Android `libdni.so` shipping today. The Android app already
exposes the same path via `FeatureCatalogService.run(id)` with three transport implementations
(`FfiFeatureService`/`HttpFeatureService`/`SqliteFeatureService`) and a transport picker.

Therefore the gated placeholder's stated reason ("new compute C-ABI exports + JNI") is **incorrect**.
This cycle is **pure Kotlin, additive, with no `.so` rebuild and no ABI change.**

## Goals

- Replace the gated Lab placeholder with the four iOS Lab demos at 1:1 parity:
  - **Fractal Explorer** — interactive colorized Mandelbrot (pinch-zoom, drag-pan, iteration slider,
    auto-dive), live FPS readout.
  - **Raymarched 3D** — CPU signed-distance-field scene, auto-rotate + drag-orbit, live FPS readout.
  - **SIMD Matrix Multiply** — scalar vs SIMD GFLOPS line chart + summary stats.
  - **Parallel Scaling** — single-thread vs `Parallel.For` line chart + summary stats.
- Per-demo transport picker (FFI/HTTP/SQLCipher). Switching to SQLCipher visibly collapses the animated
  FPS — the project's transport-cost thesis, demonstrated live.
- Add **zero** C ABI surface and **zero** native libraries. Only new Kotlin.

## Non-goals

- No engine, AI, EVS, catalog, or transport-layer changes; no native rebuild.
- No new third-party dependencies (the chart is hand-drawn on a Compose `Canvas`).
- The Features-tab static grayscale Mandelbrot stays text-only on Android (could later reuse
  `RasterPayload`) — out of scope here.
- Stretch demos from the iOS spec (particle field, prime sieve, FFT) — not in scope.

---

## The command-in-id contract (already supported by `libdni.so`)

`FeatureCatalogService.run(id)` returns `FeatureResult(id, result, elapsedMs, ok)`. For Lab the id is a
`ShowcaseCommand`. The grammar uses only RFC-3986 unreserved chars (`A–Z a–z 0–9 - _ . ~`) so it is safe
on every transport with no encoding. Fields are `~`-separated `key_value`; the leading token is the demo
name. Numeric params are parsed with `InvariantCulture` and **clamped engine-side** (never throws across
the ABI; bad/unknown → `ok=false` with explanatory text).

| Demo | Command (exact) | Engine clamp | Output |
|------|-----------------|--------------|--------|
| Fractal | `viz-mandelbrot~cx_{%.6f}~cy_{%.6f}~zoom_{%.6f}~iters_{int}~w_256~h_256` | iters 16–2000, w 32–512 | `"WxHxC:base64"` |
| Raymarcher | `viz-raymarch~angle_{%.3f}~w_220~h_220` | w/h 32–512 | `"WxHxC:base64"` |
| Matmul | `bench-matmul~max_{int}` (default 384) | 64–512 | `BenchmarkPayload` JSON |
| Parallel | `bench-parallel~size_{int}` (default 480) | 64–1024 | `BenchmarkPayload` JSON |

**Visual output** `"WxHxC:base64"`: `C` ∈ {1 (grayscale), 3 (RGB)}, row-major 8-bit, base64-encoded.
**Benchmark output** (camelCase JSON):

```json
{ "kind": "benchmark", "title": "SIMD matrix multiply",
  "series": [ { "name": "scalar", "points": [ { "x": 64, "y": 1.2 } ] },
              { "name": "SIMD",   "points": [ { "x": 64, "y": 0.2 } ] } ],
  "summary": [ { "label": "peak GFLOPS", "value": "41.8" },
               { "label": "speedup", "value": "6.8×" } ] }
```

---

## Components (all new Kotlin)

Package `io.dotnetnativeinterop.lab` (logic) and `io.dotnetnativeinterop.ui.tabs` (screens).

### `lab/RasterPayload.kt` — the one capability Android lacks
`decode(payload: String): Bitmap?` — parse `"WxHxC:base64"`, base64-decode to a `ByteArray` of length
`W*H*C`, and build an `ARGB_8888` `Bitmap`: `C=1` → gray (r=g=b=v), `C=3` → RGB triplets, alpha `0xFF`.
Returns `null` on malformed header / wrong length / bad base64 (no throw). Tolerates the legacy `"WxH:base64"`
(C defaults to 1) for forward-compat with the existing grayscale contract.

### `lab/Benchmark.kt`
`@Serializable` `BenchmarkPayload(kind, title, series, summary)`, `BenchmarkSeries(name, points)`,
`BenchmarkPoint(x: Double, y: Double)`, `SummaryStat(label, value)` (camelCase) + a lenient `Json`
(`ignoreUnknownKeys = true`) and a `decode(result: String): BenchmarkPayload?` helper (null on failure).

### `lab/LabCommands.kt`
Pure builders that reproduce the iOS `currentCommand()` strings exactly using `Locale.ROOT`/`%.6f`/`%.3f`:
`mandelbrot(cx, cy, zoom, iters, size=256)`, `raymarch(angle, size=220)`, `matmul(max=384)`,
`parallel(size=480)`.

### `lab/LabViewModel.kt`
`AndroidViewModel`. Holds a single shared `transport: TransportKind` (default `Ffi`) so all demos share
one picker (parity). `suspend fun render(command: String): FeatureResult?` =
`defaultServiceFor(transport).run(command)` wrapped in `runCatching` (null on failure). `setTransport(t)`.
Reuses the existing `FeatureCatalogService`/`defaultServiceFor`/`TransportKind` — no new transport code.

### `ui/tabs/LabScreen.kt`
A `LazyColumn`/`Column` with two sections mirroring `LabView.swift`:
- **Visual — every pixel computed in C#**: "Fractal Explorer", "Raymarched 3D".
- **Benchmarks — NativeAOT throughput**: "SIMD Matrix Multiply", "Parallel Scaling".
Each row navigates to its detail screen (local `when(route)` state in `LabScreen`, no nav lib — matches
the Features-tab detail pattern already in `AppShell`). `AppShell` `Tab.Lab` → `LabScreen(...)`.

### `ui/tabs/RasterCanvas.kt`
Composable that draws the current `Bitmap` (`Image(bitmap.asImageBitmap())`, `FilterQuality.None`,
fixed square aspect) with a live readout row: **FPS · ms/frame · W×H · transport**. Receives the latest
frame + stats as state; gestures are attached by the caller.

### `ui/tabs/FractalExplorerScreen.kt`
`RasterCanvas` + controls, parity with `FractalExplorerView.swift`:
- State: `centerX=-0.5, centerY=0.0, zoom=1.0, iterations=220.0, diving=false`, `size=256`.
- Pinch-zoom + drag-pan via `Modifier.pointerInput`/`detectTransformGestures`: `zoom = max(0.2, zoom*scale)`;
  pan `span = 3.0/zoom`, `centerX -= dx/340*span`, `centerY -= dy/340*span`.
- Iterations `Slider` (32–1000), "Dive (auto-zoom)" `Switch` (`advance(): zoom *= 1.03`), "Reset view"
  button, transport picker, caption.

### `ui/tabs/RaymarcherScreen.kt`
`RasterCanvas` + controls, parity with `RaymarcherView.swift`:
- State: `angle=0.0, spinning=true`, `size=220`. "Auto-rotate" `Switch` (`advance(): angle += 0.03`),
  drag-orbit (`angle = base + dxPx/120`, stops spinning while dragging), transport picker, caption.

### `ui/tabs/BenchmarkScreen.kt` + `lab/BenchmarkChart.kt`
Config-driven (title + command), used by both Matmul and Parallel:
- "Run" button → `LabViewModel.render(command)` → `Benchmark.decode(result)` → chart + summary row;
  shows a spinner while running and an error line on `ok=false`/decode failure.
- `BenchmarkChart` draws a multi-series line chart on a Compose `Canvas` (auto-scaled axes, x/y tick
  labels, one color per series) + a legend; below it a row of `SummaryStat` chips. No chart library.

## Frame loop & FPS (parity with `RasterDemo.renderLoop`)

A `LaunchedEffect`-driven coroutine loop in each visual screen:

```
loop:
  t0 = nanoTime
  result = labVm.render(currentCommand())          // off-main via the service's IO dispatcher
  bitmap = RasterPayload.decode(result.result)     // keep last good bitmap if null
  dtMs = (nanoTime - t0)/1e6;  fps = if (dtMs>0) 1000/dtMs else 0;  dims = "WxH"
  if (animating) { advance(); }                    // mutate params, immediately loop
  else { suspend until a param changes (snapshotFlow on the param state) }
```

Bitmap/FPS state lives on the main thread (Compose state); the render call is `suspend` and hops to IO
inside the service. The loop is cancelled with the composition. SQLCipher writes one `feature_runs` row
per frame, so selecting it visibly drops the FPS — the thesis, demonstrated.

## Data flow

`LabScreen` → tap demo → detail.
**Visual:** loop tick → `LabViewModel.render("viz-…")` → `FeatureResult.result = "WxHxC:base64"` →
`RasterPayload.decode` → `Bitmap` → `RasterCanvas` + readout; gestures/slider mutate params → next tick.
**Benchmark:** tap Run → `render("bench-…")` → `Benchmark.decode` → `BenchmarkChart` + summary.

## Error handling

- Engine clamps params and never throws across the ABI; unknown/bad → `ok=false` + text → show an error
  line, keep the last good frame (no black flash).
- `RasterPayload.decode` / `Benchmark.decode` return `null` on malformed input → visible placeholder /
  error line, mirroring the EVS and AI tab error states. Never crash on bad bytes.

## Testing & verification

- **Instrumented `LabTabTest`** (arm64 emulator, real FFI — the on-device proof):
  - `LabViewModel.render("viz-mandelbrot~cx_-0.5~cy_0~zoom_1~iters_50~w_64~h_64")` →
    `result` matches `^64x64x[13]:` and `RasterPayload.decode` yields a 64×64 `Bitmap`.
  - `render("bench-matmul~max_128")` → `Benchmark.decode` yields a `BenchmarkPayload` with ≥2 series,
    each with ≥1 point, and `ok == true`.
- **JVM unit tests:** `RasterPayload.decode("2x2x3:<b64>")` → 2×2 `Bitmap` with the expected pixels (and
  a `2x2:` grayscale case + a malformed case → null); `Benchmark.decode` of a JSON fixture.
- **Smoke (emulator):** launch → Lab → Fractal Explorer renders with a ticking FPS readout; benchmark
  screen draws a chart. Screenshot the Lab list + Fractal.
- **Build:** `assembleDebug` green; `connectedDebugAndroidTest` for `LabTabTest` passes. No `.so` rebuild.
- CI: `ci-android` green on the PR.

## Conventions

Branch `feat/android-lab-tab` (off main). Build/test on the arm64 emulator via the Mac mini
(`ssh steve-mac-mini`, overlay into `/Users/steve/dni-rag-build`, `tar`-over-ssh sync, `gradle 8.9`).
`-Xexplicit-api=strict` (public visibility on all declarations, incl. test sources). Additive only.
Conventional Commits, no AI attribution, PR → squash-merge.
