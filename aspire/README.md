# DniObservability — a dev-time Aspire observability host for DotnetNativeInterop.Engine

## What this is

A [.NET Aspire](https://aspire.dev) `AppHost` that drives `core/DotnetNativeInterop.Engine` as
ordinary managed code and forwards its real OpenTelemetry (`ActivitySource("Dni.Engine")` spans +
`Meter("Dni.Engine")` counters/histograms — see `EngineTrace.cs`/`EngineMetrics.cs`) to the Aspire
dashboard over OTLP. It exists to make the engine's telemetry watchable during local development,
using the same `ffi.*`/`http.*`/`sqlite.*`/`pb.*`/`rag.*`/`agent.*` spans and `dni.*` metrics the
shipped mobile engine already emits — nothing here is fake or bolted on separately.

**Read `docs/aspire-nativeaot-findings.md` first** for the full feasibility-gate writeup, including a
sandbox network limitation that blocked verifying the Dashboard UI directly in this environment (the
core OTel-export claim was still verified, just via an in-process harness instead).

## THE IMPORTANT CAVEAT — this is a dev tool, not part of the shipped app

**Nothing under `aspire/` runs on a phone.** The Aspire AppHost is a JIT-compiled, `net10.0`,
`dotnet run`-only local development tool — it orchestrates a local dashboard and whatever services you
point it at, for your dev inner loop, full stop. The engine's telemetry pipeline (`EngineTrace`/
`EngineMetrics`) is genuinely shared with the mobile app (same `ActivitySource`/`Meter`, same span
names), but:

- The **shipped mobile artifact** is the NativeAOT `DotnetNativeInterop.NativeBridge` library, built
  for `ios-arm64` / `linux-bionic-arm64`, that the iOS/Android apps embed directly.
- **`EngineHost`** in this folder references `DotnetNativeInterop.Engine` as a plain managed
  `ProjectReference` — no `PublishAot`, no AOT/trim constraints apply here. It runs the engine's C#
  exactly like any other .NET service would; the fact that the *same* engine assembly is also
  AOT-compatible for mobile is a separate, already-proven property (see the other `docs/*-findings.md`
  gates), not something this example re-tests.
- If Aspire itself were ever used for anything beyond local dev (it isn't, in this repo), that would
  be an entirely separate decision from anything in this folder.

## Layout

- **`DniObservability.AppHost`** — the Aspire orchestrator. Its `AppHost.cs` registers one resource:
  `EngineHost`. `dotnet run` from here launches the dashboard and starts `EngineHost` under it.
- **`DniObservability.ServiceDefaults`** — the stock, unmodified Aspire `ServiceDefaults` template
  (OTel wiring, health checks, service discovery, HTTP resilience). Kept 100% generic so it stays
  reusable if a second service is ever added; all `Dni.Engine`-specific OTel wiring lives in
  `EngineHost` instead.
- **`DniObservability.EngineHost`** — a minimal ASP.NET Core host. `Program.cs` adds
  `AddSource(EngineTrace.Source.Name)` / `AddMeter(EngineTrace.Meter.Name)` on top of
  `AddServiceDefaults()`, then runs an `EngineDriver` background service that periodically exercises
  real engine work (`LanguageFeatureCatalog.Run`, a real `RagLanguageModel` generation over the
  bundled ONNX-encoded manuals corpus) so the dashboard has live spans/metrics to show.

This is its own, isolated project set — **not** added to `DotnetNativeInterop.slnx` — with its own
`Directory.Build.props` so the repo-root build settings (tuned for the shipped AOT engine/bridge/UI
code) don't leak into scaffolded Aspire template code. Same isolation spirit as `spike/`.

## Running it

Prerequisites: .NET 10 SDK, Aspire project templates (`dotnet new install Aspire.ProjectTemplates`),
and — unlike everything else in this repo — a working internet connection with no restrictions on
large NuGet downloads. The very first restore of the AppHost project pulls the Aspire dashboard's
self-contained web app and the DCP orchestrator binaries (`Aspire.Dashboard.Sdk.win-x64` /
`Aspire.Hosting.Orchestration.win-x64`, each a sizeable download) — see the findings doc if this
hangs or fails; that's a known sandbox limitation here, not expected on a normal dev machine.

```powershell
dotnet run --project aspire/DniObservability.AppHost
```

This opens the Aspire dashboard (URL printed to the console). The `enginehost` resource should show
healthy; its **Traces** page shows `aspire.demo.feature.*` and `rag.retrieve`/`rag.prompt`/
`rag.generate` spans ticking every ~5 seconds; its **Metrics** page shows `dni.spans.recorded` and
`dni.span.duration` under the `Dni.Engine` meter. `POST /demo/run` against the `enginehost` resource's
endpoint triggers one extra iteration on demand.

## Why no second "caller" service (yet)

A more Aspire-idiomatic version of this example would add a second tiny service that calls
`EngineHost` over real HTTP (via Aspire service discovery) so the dashboard shows cross-service
request telemetry too. Skipped in this first pass to keep the feasibility gate minimal — see the
findings doc. Natural next step if this example gets extended.
