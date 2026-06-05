# Latency Lab + Live Engine Telemetry + Rich About (Phase 2) — Design

**Date:** 2026-06-05
**Status:** Approved — ready for implementation plan.
**Author:** pairing session

## Context

`DotnetNativeInterop` embeds one NativeAOT .NET 10 engine in a native iOS app over three interop
transports (FFI, raw-HTTP, SQLCipher). Phase 1 (merged, PR #4) added a **Lab** tab of GPU-free C# visual
renderers and benchmarks, driven by a URL-safe `name~key_value~…` **command-in-id** grammar routed
through the existing `LanguageFeatureCatalog.Run(id)` — keeping the C ABI frozen.

Phase 2 is the **instrumentation** release. It deepens the existing single-transport Latency histogram
into a full latency lab, adds **live runtime telemetry** (GC/heap/threads) surfaced from the engine, and
expands the thin About tab into a portfolio-grade explainer with live runtime facts.

This is the second of three phases (each its own spec → plan → build):
1. (merged) Lab — visual compute + benchmarks.
2. **(this spec)** Latency Lab + live engine telemetry + rich About.
3. Onboard AI — .NET-driven ONNX semantic search + Apple Foundation Models chat.

## Decisions (user, 2026-06-05)

- **Latency tab: augment in place** — grow it into a hub; the existing histogram is preserved as one view.
- **Telemetry: a dedicated `dni_engine_stats()` C ABI export** — the project's **first ABI addition**
  (vs. riding the command path). A real, honest "engine introspection" surface.
- **Telemetry depth: live-updating while a workload runs** — a strip that updates on a timer so GC/heap
  visibly move while a benchmark is hammered.
- **About: rich** — architecture + AOT explainer + the "why" deep-dives + live runtime facts.

## Goals

- Quantify the transport-cost thesis: percentiles, throughput, jitter, and payload-size scaling — not
  just a single histogram.
- Make the NativeAOT runtime's behaviour visible in real time (GC, heap, threads, allocations).
- Turn About into a self-contained explanation of the whole architecture, with live device/runtime facts.
- Keep every change additive: nothing removed; the existing histogram and all existing tabs preserved.

## Non-goals (this spec)

- Phase 3 (ONNX semantic search, Apple Foundation Models). Android.
- Telemetry over HTTP/SQLite — introspection is engine-global, so telemetry is **FFI-only**.
- Per-transport telemetry or historical persistence of stats.

---

## Architecture

### 1. Engine — telemetry source + the new export (`core/DotnetNativeInterop.Engine/` + `…NativeBridge/`)

**`EngineTelemetry`** (Engine, AOT-safe — no reflection): a `Snapshot()` returning an `EngineStats` record
sourced from BCL runtime APIs that are AOT-safe on the iOS .NET 10 runtime:

- `gcGen0` / `gcGen1` / `gcGen2` — `GC.CollectionCount(0|1|2)`
- `heapBytes` — `GC.GetGCMemoryInfo().HeapSizeBytes` (fallback `GC.GetTotalMemory(false)`)
- `committedBytes` — `GC.GetGCMemoryInfo().TotalCommittedBytes`
- `allocatedBytes` — `GC.GetTotalAllocatedBytes()` (cumulative)
- `gcPauseMs` — `GC.GetTotalPauseDuration().TotalMilliseconds` (.NET 10). **Guarded:** if unavailable on
  the iOS runtime, drop this field (return 0 / omit) — the one runtime-availability risk in this spec.
- `threadCount` — `ThreadPool.ThreadCount`
- `processorCount` — `Environment.ProcessorCount`
- `uptimeMs` — elapsed since a start timestamp captured once at static init / first `EngineHost.Initialize`.

`EngineStats` is a public record; JSON via a source-gen `TelemetryJsonContext` (camelCase), like the
existing `FeaturesJsonContext` / `ShowcaseJsonContext`.

**`dni_engine_stats()`** (NativeBridge, `Ffi/`): a new `[UnmanagedCallersOnly]` export returning heap
UTF-8 JSON of `EngineTelemetry.Snapshot()` (calls `EngineHost.Initialize()` first, idempotent), freed by
the existing `dni_string_free`. Declared in `abi/dni.h` under a new "engine introspection" section. The
Swift bridging header already `#import`s `dni.h`, so **no bridging-header edit** is needed — the framework
rebuild surfaces the symbol.

### 2. Engine — payload-scaling command (no ABI change)

Add a `bench-echo` case to the Phase 1 `ShowcaseCommand` dispatcher: `bench-echo~bytes_N` returns a
deterministic N-byte ASCII string (N clamped, e.g. 1 … 1_048_576). It rides the existing `Run(id)` path,
so all three transports carry it with no ABI change. The latency lab sweeps N to measure transport cost
vs response size. (The telemetry "stress workload" needs **no** new engine code — the Latency UI loops the
existing Phase 1 `bench-matmul` / `bench-parallel`, which allocate, to move GC.)

### 3. iOS — Latency tab → Latency Lab (`ios/Shared/Latency/`)

The tab becomes a hub (`NavigationStack` + `List`) linking to focused screens, each its own small file
(keeps units focused vs one large view). The existing `ios/Shared/LatencyView.swift` histogram is
**relocated intact** into `DistributionView` (feature preserved, just nested under the hub).

- **`LatencyHubView`** — the tab content: a list with sections *Measure* and *Runtime*.
- **`DistributionView`** — the existing single-transport ping histogram + min/median/p95/max stats
  (moved verbatim from `LatencyView.swift`, including the `LatencyBin` bucketing).
- **`TransportComparisonView`** — fires N pings over **all three** transports; an overlaid distribution
  chart (Swift Charts, one series per transport) + a table of **p50 / p95 / p99 / max** and **throughput
  (calls/sec)** per transport.
- **`JitterView`** — fires N sequential pings over the selected transport; a line chart of latency vs
  call-index (cold→warm, GC jitter visible).
- **`PayloadScalingView`** — sweeps `bench-echo~bytes_N` over N ∈ {64, 1 024, 16 384, 262 144, 1 048 576}
  for the selected transport(s); a line chart of latency vs payload size (log-x).
- **`LatencyStats.swift`** — shared percentile/throughput helpers (so each view doesn't re-derive them).

These reuse the existing `[TransportKind: FeatureService]` services and the `LabTransportPicker` /
`FeatureService.run` seam from Phase 1; ping latencies reuse `FeaturesViewModel.pingLatencies`-style
timing (a small `LatencyViewModel` holding the services + selected transport).

### 4. iOS — Live telemetry (`ios/Shared/Telemetry/`)

- **`EngineStats.swift`** — Codable mirror of the engine JSON (camelCase), decoded with `JSONDecode`.
- **`TelemetryService.swift`** — FFI-only: calls `dni_engine_stats()` on a background `Task`, copies +
  frees the string, decodes. (Mirrors `FFIFeatureService`'s marshalling.)
- **`TelemetryStrip.swift`** — a reusable view that, while visible, polls `TelemetryService` on a ~300 ms
  timer (a `Task` loop, `@MainActor` state) and shows live: GC gen0/1/2, heap MB, allocated MB, threads,
  GC pause ms, uptime, cores.
- **`TelemetryView.swift`** — the *Runtime* entry in the Latency hub: a **"Run stress"** toggle that
  loops the Phase 1 benchmarks (`bench-matmul` / `bench-parallel`) on a background task while the
  `TelemetryStrip` and a **heap-over-time** mini line chart update live — the "watch GC/heap move" payoff.

### 5. iOS — Rich About (augment `ios/Shared/AboutView.swift`)

Keep the existing per-transport tradeoff sections. Add, above them:

- **Architecture** — a monospaced text diagram of UI → transports → `dni` → Engine (mirrors the README).
- **NativeAOT explainer** — AOT vs JIT, why "no runtime, no JIT, in-process," what that buys.
- **Why these transports** — the raw-HTTP (no Kestrel AOT pack), SQLCipher (only iOS SQLite bundle),
  gRPC-excluded rationale (condensed from the README).
- **Live runtime facts** — a section sourcing `EngineStats` (refreshes on appear): .NET/runtime, cores,
  heap, uptime — ties About to the telemetry.

About gains a small dependency on `TelemetryService` (injected like the Lab services).

### 6. Wiring

`LatencyView` (the old tab view) is replaced in `RootTabView` by `LatencyHubView(model:)`. A
`LatencyViewModel` and the `TelemetryService` are built in `UnifiedApp` from the existing `services` dict
(FFI service reused for telemetry) and passed down — additive `@StateObject`s, mirroring how `lab` was
added in Phase 1. No other tab changes.

## Data flow

- **Latency analyses:** view → `LatencyViewModel` fires pings / `bench-echo` over the selected
  transport(s) via `FeatureService.run`, times each round-trip client-side, computes percentiles /
  throughput / series → Swift Charts.
- **Telemetry:** `TelemetryStrip`/`TelemetryView` timer tick → `TelemetryService.stats()` →
  `dni_engine_stats()` (FFI, background) → decode `EngineStats` → `@MainActor` state → strip + heap chart.
  Stress toggle runs benchmarks on a separate background task so the polling and the workload are
  concurrent (GC moves while the strip samples).

## Error handling

- `dni_engine_stats()` returns 0 on failure → service throws → strip shows "telemetry unavailable" rather
  than blank; About's live-facts section degrades to "unavailable".
- `EngineStats` decode failure → visible message (mirrors `FeatureServiceError` handling).
- `bench-echo` clamps N; oversized requests are bounded, never OOM.
- `GC.GetTotalPauseDuration()` guarded server-side (0 if unsupported) so the strip never crashes.

## Testing & verification

- **Engine (Windows, throwaway probe):** assert `EngineTelemetry.Snapshot()` returns plausible non-zero
  `heapBytes` / `processorCount` / `uptimeMs` and serializes to camelCase JSON; assert
  `LanguageFeatureCatalog.Run("bench-echo~bytes_4096").Result.Length == 4096` and `Ok`. Managed build
  `-c Release` clean. (The `dni_engine_stats` C export and all SwiftUI are verified on-device.)
- **iOS (Mac mini — ABI changed, framework rebuild mandatory):** build as `steve` (fresh `git archive`
  sync to a clean dir; unlock-keychain inline; device-only framework script), install to the iPad, and
  confirm: Latency hub's four analyses; the p50/p95/p99 + throughput table; jitter + payload-scaling
  charts; the telemetry strip moving during a stress run with a live heap chart; rich About with live
  runtime facts; and **no regressions** (Dashboard, Features, Lab, Compare, the original histogram).

## Risks

- **`GC.GetTotalPauseDuration()` on the iOS .NET 10 runtime** — if absent/throwing, the guard returns 0
  and the field is effectively dropped; no other impact.
- **Timer-driven polling + a concurrent benchmark workload** under Swift 6 strict concurrency — telemetry
  polling on `@MainActor`, the stress workload and the FFI stats call on background tasks (same discipline
  as Phase 1's render loop).
- ABI change means the **framework MUST be rebuilt** (unlike Phase 1) — flagged so the Mac step isn't
  skipped.

## Follow-on (named, not built here)

- **Phase 3:** .NET-driven ONNX embeddings → on-device semantic search; Apple Foundation Models chat
  (eligible iPad confirmed; gated contrast).
