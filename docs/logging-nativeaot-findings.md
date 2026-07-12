# Structured logging under NativeAOT — findings

**Status: PASS.** `Microsoft.Extensions.Logging.Abstractions` + the `[LoggerMessage]` source generator are
AOT-clean inside the engine, completing the observability trio: **tracing** (`EngineTrace` / `dni_trace_drain`)
+ **metrics** (`EngineMetrics` / `metrics~snapshot`) + **logging** (`EngineLog` / `dni_log_drain`).

## What shipped

- `EngineLog` — a bounded (256-record, drop-oldest) log ring, byte-for-byte the same mechanics as the span
  ring: overflow is counted and disclosed in the drain payload (never silent), records carry µs offsets on
  the **same** boot clock as spans (`EngineTrace.NowUs`), and JSON goes through a source-gen context
  (`LogJsonContext`, camelCase, null `requestId`/`exception` omitted). Pull model.
- `EngineLogEvents` — three `[LoggerMessage]` structured events (`EngineInitialized`, `FfiDrainAborted`,
  `ExportFailed`). The generator emits reflection-free, zero-alloc logging code that early-outs on a
  disabled level.
- `RingLogger` — a ~20-line hand-written `ILogger` funnelling records into the ring. Rolling it by hand
  means the whole leg needs **only** `Microsoft.Extensions.Logging.Abstractions` — no concrete
  `Microsoft.Extensions.Logging` (DI/Options/filtering) package, so the NativeAOT surface stays tiny.
  Level filter is Information+ (Trace/Debug would evict the Warnings/Errors this leg exists to surface).
- `dni_log_drain` (additive FFI export, mirrors `dni_trace_drain`) + `log~stats` command (mirrors
  `trace~stats`). Documented in `abi/dni.h`.

## The payoff — swallowed exceptions become observable

The FFI boundary's fire-and-forget token drain (`ExportsFfi.DrainAsync`) previously ended a faulted/cancelled
turn with a bare `catch (Exception) { }` — the client's only signal was the *absence* of an `is_final=1`
marker. It now logs `FfiDrainAborted` (Warning, with the exception's `Type: message`) to the ring, so a
client draining `dni_log_drain` can see *why* a turn died. `dni_agent_session_start`'s error path logs
`ExportFailed` (Error) instead of returning a bare `DNI_INTERNAL` with no detail.

## Verification

- `spike/LogSinkHarness` — **29/29**: event capture with level/category/message/exception fidelity, the
  Information+ filter, drop-oldest overflow disclosure, drain-clears-the-ring, `log~stats` counters, and the
  camelCase/omit-null JSON wire shape. AOT-publishable (the win-x64 NativeAOT gate for M.E.Logging).
- Full solution `dotnet build -c Release`: **0 errors, 0 IL/AOT warnings** (the Engine is
  `IsAotCompatible=true`, so an AOT-hostile dependency would warn — none did).
- `ci-ios` / `ci-android` compile the Engine into the real NativeAOT image, so a green run there is the
  end-to-end AOT proof on device targets.
- No regression: `AgentSessionHarness` 25/25, `ForemanHarness` 94/94.

## Why Abstractions-only, and why pull

`[LoggerMessage]` is the AOT-first logging path Microsoft recommends precisely because it needs no
reflection and no logging *host* — just an `ILogger`. Pairing it with a hand-written ring `ILogger` keeps
the dependency to the tiny Abstractions package. The **pull** model (drain on demand) matches the existing
trace/metrics legs and sidesteps an FFI push-callback's lifecycle/re-entrancy hazards; a `dni_set_log_sink`
push callback remains a possible later addition.

## Scope

Engine + export + gate + docs. The iOS/Android **log view** (a UI surfacing `dni_log_drain`, the way the
Trace waterfall surfaces `dni_trace_drain`) is a separate additive increment — the same engine-first order
the tracing and metrics legs shipped in.
