# Screenshots

Every screen of the unified iOS app (`com.dotnetnativeinterop.unified`), captured on an iPad Pro 13"
simulator. Regenerate with [`build/capture-screenshots.sh`](../build/capture-screenshots.sh).

One NativeAOT .NET 10 engine, embedded in-process and reached over three interop transports
(**FFI** · raw **HTTP** · **SQLCipher**) — every feature, pixel, and number below is computed in C#.

---

## Dashboard

The active transport, "Run all", and aggregate results.

![Dashboard](01-dashboard.png)

## Features

The C# / .NET feature catalog grouped by language version; each runs live in-AOT with code, result, and timing.

![Features](02-features.png)

---

## Lab — visual compute + benchmarks

GPU-free graphics and throughput showpieces, every pixel/number computed in C#.

### Lab hub

![Lab](03-lab.png)

### Fractal Explorer

A colorized Mandelbrot rendered pixel-by-pixel in C# — pinch to zoom, drag to pan, auto-dive, with a live
frames-per-second readout. Flip the transport to watch the frame rate collapse on SQLCipher vs FFI.

![Fractal Explorer](04-fractal-explorer.png)

### Raymarched 3D

A signed-distance-field scene (sphere on a checker plane, soft shadow) ray-marched entirely on the CPU in C# — no GPU, no shaders.

![Raymarcher](05-raymarcher.png)

### SIMD matrix multiply

SIMD (`Vector<T>`) vs scalar matmul throughput in GFLOPS across matrix sizes.

![SIMD Matmul](06-bench-matmul.png)

### Parallel scaling

Mandelbrot strong-scaling — render time and speedup as threads go 1 → all cores.

![Parallel Scaling](07-bench-parallel.png)

---

## Compare

Every feature run over all three transports, client round-trip time side by side.

![Compare](08-compare.png)

---

## Latency Lab

### Hub

![Latency hub](09-latency-hub.png)

### Distribution

Histogram of N `ping` round-trips over the selected transport — pure transport cost.

![Distribution](10-distribution.png)

### Transport comparison

FFI / HTTP / SQLCipher compared: a CDF overlay of their distributions plus a p50 / p95 / p99 + throughput table.

![Transport comparison](11-transport-comparison.png)

### Jitter over time

Latency vs call index — flat from the first call (NativeAOT has no JIT warmup), with the occasional GC blip.

![Jitter over time](12-jitter.png)

### Payload scaling

Round-trip latency vs response size (64 B → 1 MB) per transport — where serialization/copy cost dominates.

![Payload scaling](13-payload-scaling.png)

### Engine telemetry

Live runtime stats from the NativeAOT engine (GC collections, managed heap, allocations, threads, GC pause,
uptime) via the `dni_engine_stats` C export, with a stress toggle that churns the GC.

![Engine telemetry](14-telemetry.png)

---

## About

Architecture, a NativeAOT / AOT-vs-JIT explainer, the per-transport rationale, and live runtime facts.

![About](15-about.png)
