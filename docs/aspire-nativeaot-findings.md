# .NET Aspire as a dev-time observability host for DotnetNativeInterop.Engine

**Date:** 2026-07-06
**Status:** Core claim **PASSED** (verified in-process). Full AppHost + Aspire Dashboard run
**BLOCKED in this sandbox** by network egress limits on large NuGet packages — documented below with
exact errors. This is an environment constraint, not an Aspire/.NET 10/engine compatibility problem.

**Why this doc exists:** the engine (`core/DotnetNativeInterop.Engine`) already emits real
OpenTelemetry — an `ActivitySource("Dni.Engine")` for spans (`ffi.*`/`http.*`/`sqlite.*`/`pb.*`/
`rag.*`/`agent.*`, see `EngineTrace.cs`) and a `Meter("Dni.Engine")` for counters/histograms (see
`EngineMetrics.cs`, merged just before this gate). Aspire's dashboard is fundamentally an OTLP
viewer, so a dev-only host that references the engine as ordinary managed code and forwards its
`Dni.Engine` telemetry over OTLP is a natural, low-risk showcase — provided it doesn't imply Aspire
ships on-device (it does not; see [Caveats](#caveats)).

## TL;DR

- ✅ **The actual technical claim holds**: subscribing `Dni.Engine`'s `ActivitySource`/`Meter` into a
  real OpenTelemetry SDK `TracerProvider`/`MeterProvider` via `AddSource`/`AddMeter` — exactly what
  `DniObservability.EngineHost`'s `Program.cs` does on top of Aspire's `ServiceDefaults` — captures
  the engine's spans and instruments as genuine, export-ready OTel records. Verified with an
  in-process harness (below): 4 real spans captured, including the actual `rag.retrieve`/
  `rag.prompt`/`rag.generate` spans production code emits, plus the `dni.spans.recorded` counter and
  `dni.span.duration` histogram.
- ✅ Aspire 13.4.6 (see [version caveat](#version-caveat-9x--13x)) templates, SDK, and the small
  `ServiceDefaults`-class packages (OpenTelemetry, ServiceDiscovery, Http.Resilience) install and
  restore cleanly on .NET 10 (SDK 10.0.301) — this part is a clean PASS with no code changes needed.
- ✅ `EngineHost` (references `DotnetNativeInterop.Engine` as ordinary managed code, no `PublishAot`)
  builds, transitively pulls the engine's bundled ONNX model/vocab/manuals assets and the
  `onnxruntime.dll` native binary into its own output, and **runs standalone for real**: `GET /`
  responds, `POST /demo/run` returns genuine `LanguageFeatureCatalog`/`RagLanguageModel` output, and
  its background `EngineDriver` ticks on its own every 5 seconds with correctly-rendered
  `[LoggerMessage]` logs — the full example minus the OTLP-to-dashboard hop.
- ❌ **Could not verify the actual Aspire Dashboard UI / a live `dotnet run` of the AppHost** in this
  sandboxed environment: `dotnet restore` on the AppHost project repeatedly fails to download
  `Aspire.Dashboard.Sdk.win-x64`, `Aspire.Hosting.Orchestration.win-x64`, `Grpc.Tools`,
  `KubernetesClient`, and `StreamJsonRpc` — large packages the Aspire SDK pulls in unconditionally for
  any AppHost, even a single-project one. Three attempts (~55 minutes of wall-clock network time
  total), including one with NuGet's enhanced HTTP retry (10 tries/package) and cleared local caches,
  all failed with the same packages timing out, hitting mid-stream EOF, or (occasionally) failing DNS
  resolution outright. Meanwhile every *small* package (the entire `ServiceDefaults` dependency set,
  `OpenTelemetry.Exporter.InMemory`, the engine's own dependencies) restored in under a minute each
  time. This is the signature of a sandbox that cannot sustain long-lived/large HTTP downloads, not a
  problem with the packages themselves — they are real, current, and this is Microsoft's own
  documented pattern (see [Aspire SDK for distributed apps](https://aspire.dev/get-started/aspire-sdk/)).

## Version caveat: 9.x → 13.x

The task framing (and most Aspire docs cached in older training data) says "Aspire 9.x". As of this
writing (verified via `microsoft_docs_search`/`microsoft_docs_fetch` and `dotnet new install
Aspire.ProjectTemplates`, which pulled **13.4.6** — the current stable release, docs dated as
recently as 2026-06-25), **Aspire has moved past major version 9** — Microsoft's own upgrade guide is
titled ["Upgrade to Aspire 13.0"](https://learn.microsoft.com/dotnet/aspire/get-started/upgrade-to-aspire-13),
and it explicitly folds in what used to be "Aspire 9 → Aspire 9" version-to-version guidance. The
`aspire workload` (the old .NET *workload* mechanism, `dotnet workload install aspire`) was already
removed as of Aspire 9 — **not needed here at all**; the project templates + `Aspire.AppHost.Sdk`
NuGet-based SDK are the only mechanism, confirmed by `dotnet workload list` on this box showing no
`aspire` entry and templates still installing/working via `dotnet new install Aspire.ProjectTemplates`.
This gate targets **whatever is actually current** (13.4.6) rather than the assumed 9.x, since 9.x
templates are superseded and not what `dotnet new` gives you today.

## What we tried

### 1. Templates + SDK resolve on .NET 10

```powershell
dotnet --version                       # 10.0.301 (global.json pins 10.0.100, rollForward=latestFeature)
dotnet workload list                   # no "aspire" entry — confirms no legacy workload needed
dotnet new install Aspire.ProjectTemplates
# Success: Aspire.ProjectTemplates@13.4.6 installed the following templates:
#   Aspire AppHost, Aspire Empty App, Aspire Service Defaults, Aspire Single-File App Host,
#   Aspire Starter App (ASP.NET Core/Blazor), Aspire Starter App (ASP.NET Core/React, C#...),
#   Aspire Test Project (MSTest/NUnit/xUnit)
```

Scaffolded, under a new **isolated** `aspire/` top-level directory (own solution-less project set,
*not* added to `DotnetNativeInterop.slnx`, matching how `spike/` work stays out of the main solution):

```powershell
dotnet new aspire-apphost -n DniObservability.AppHost
dotnet new aspire-servicedefaults -n DniObservability.ServiceDefaults
```

`DniObservability.AppHost.csproj` (as scaffolded, unmodified except for the added
`ProjectReference` to `EngineHost`):

```xml
<Project Sdk="Aspire.AppHost.Sdk/13.4.6">
  <PropertyGroup>
    <OutputType>Exe</OutputType>
    <TargetFramework>net10.0</TargetFramework>
    <ImplicitUsings>enable</ImplicitUsings>
    <Nullable>enable</Nullable>
    <UserSecretsId>...</UserSecretsId>
  </PropertyGroup>
  <ItemGroup>
    <ProjectReference Include="..\DniObservability.EngineHost\DniObservability.EngineHost.csproj" />
  </ItemGroup>
</Project>
```

`ServiceDefaults` restored cleanly in **1.08 min** on the first try — its dependency set is just
`Microsoft.Extensions.Http.Resilience`, `Microsoft.Extensions.ServiceDiscovery`,
`OpenTelemetry.Exporter.OpenTelemetryProtocol`, `OpenTelemetry.Extensions.Hosting`,
`OpenTelemetry.Instrumentation.{AspNetCore,Http,Runtime}` — all small.

### 2. `EngineHost` — the actual Dni.Engine coupling

`aspire/DniObservability.EngineHost` (`Microsoft.NET.Sdk.Web`, `net10.0`) references
`DniObservability.ServiceDefaults` and `core/DotnetNativeInterop.Engine` as **ordinary managed
`ProjectReference`s** — no `PublishAot`, no `IsAotCompatible`. In `Program.cs`:

```csharp
builder.AddServiceDefaults();                 // stock Aspire wiring: OTel, health, service discovery

builder.Services.AddOpenTelemetry()
    .WithTracing(tracing => tracing.AddSource(EngineTrace.Source.Name))   // "Dni.Engine"
    .WithMetrics(metrics => metrics.AddMeter(EngineTrace.Meter.Name));    // "Dni.Engine"
```

This is the one addition beyond stock `ServiceDefaults`: `AddSource`/`AddMeter` compose additively
across multiple `WithTracing`/`WithMetrics` calls in the OpenTelemetry .NET SDK, so `ServiceDefaults`
itself needed **zero changes** — it stays the generic, reusable Aspire boilerplate; `EngineHost`
layers the engine-specific subscription on top.

`EngineDriver` (a `BackgroundService`) periodically drives **real** engine work so the dashboard has
something to show: `LanguageFeatureCatalog.Run(featureId)` for a rotating set of no-asset-dependency
language features, wrapped in an `EngineTrace.StartSpan("aspire.demo.feature.<id>")` span, plus a real
`RagLanguageModel` generation (the same `SemanticSearch.Default` ONNX encoder + bundled manuals
corpus the mobile engine uses, with a `MockLanguageModel` standing in for llama.cpp) — which emits
the engine's actual `rag.retrieve`/`rag.prompt`/`rag.generate` spans. **Deliberately does not** call
`EngineTrace.RecordRequest` with a transport tag (`ffi`/`http`/`sqlite`/`pb`): those tags are reserved
for the real built transports in `NativeBridge`, and reusing one from a dev-host demo loop would make
the dashboard's per-transport request counts misleading. What this host *does* produce honestly:
`dni.spans.recorded`/`dni.span.duration` (from every span, automatic) and the genuine `rag.*` spans.

Build (standalone, without the AppHost):

```powershell
cd aspire/DniObservability.ServiceDefaults && dotnet build   # Build succeeded, 1 warning (pre-existing CS1668 LIB-path noise, unrelated), 0 errors
cd aspire/DniObservability.EngineHost      && dotnet build   # Build succeeded, 1 warning (same), 0 errors
```

Confirmed the engine's bundled ONNX assets and native runtime **flow transitively** through the plain
`ProjectReference` into `EngineHost`'s own output directory (`Ai/assets/{model.onnx,vocab.txt,
corpus.txt,manuals/*.md}` and `runtimes/win-x64/native/onnxruntime.dll` both present after
`dotnet build`) — no extra MSBuild wiring needed; this is standard .NET SDK-style project behavior.

**Ran `EngineHost` standalone** (`dotnet run`, no AppHost — since the AppHost couldn't restore in this
sandbox, see below) to confirm the whole thing works end to end as a real, long-lived web host, not
just something that compiles:

```
info: Microsoft.Hosting.Lifetime[14]
      Now listening on: http://127.0.0.1:5299
info: Microsoft.Hosting.Lifetime[0]
      Application started. Press Ctrl+C to shut down.

GET / -> 200 "DniObservability.EngineHost — dev-time OTel host for DotnetNativeInterop.Engine. ..."

POST /demo/run -> 200
  {"featureId":"list-patterns","featureOutput":"head=10, tail=40",
   "ragOutput":"You asked: \"You are a maintenance assistant. Answer"}

info: DniObservability.EngineHost.EngineDriver[570628668]
      EngineDriver tick 2: feature=records-with rag_chars=51
info: DniObservability.EngineHost.EngineDriver[570628668]
      EngineDriver tick 3: feature=generic-math rag_chars=51
info: DniObservability.EngineHost.EngineDriver[570628668]
      EngineDriver tick 4: feature=switch-expression rag_chars=51
info: DniObservability.EngineHost.EngineDriver[570628668]
      EngineDriver tick 5: feature=collection-expressions rag_chars=51
```

The `EngineDriver` background service ticks every 5 seconds on its own, `/demo/run` triggers an extra
iteration synchronously, and the `[LoggerMessage]`-generated log calls render correctly with the
expected category (`DniObservability.EngineHost.EngineDriver`). This is the actual example code
running for real, not a proxy — the only piece not exercised here is the OTLP hop to an Aspire
dashboard, covered instead by the in-process harness below.

### 3. Proving the OTel coupling in-process (bypassing the network-blocked AppHost)

Because the AppHost's Dashboard/DCP packages could not be downloaded in this sandbox (see
[below](#4-apphost-restore---blocked-by-sandbox-network-egress)), the actual technical risk — does
`AddSource("Dni.Engine")`/`AddMeter("Dni.Engine")` really capture the engine's telemetry? — was
verified with a throwaway harness (not part of the shipped example; lived outside the repo, deleted
after this gate) using the OTel SDK directly with an in-memory exporter instead of OTLP. OTLP vs.
in-memory differ only in the last-mile export step, not in whether the `TracerProvider`/
`MeterProvider` observes `Dni.Engine`'s telemetry — so this is a faithful proxy for "does the
dashboard receive it":

```csharp
using var tracerProvider = Sdk.CreateTracerProviderBuilder()
    .AddSource(EngineTrace.Source.Name)
    .AddInMemoryExporter(capturedActivities)
    .Build();
using var meterProvider = Sdk.CreateMeterProviderBuilder()
    .AddMeter(EngineTrace.Meter.Name)
    .AddInMemoryExporter(capturedMetrics)
    .Build();

// ... run LanguageFeatureCatalog.Run(...) and RagLanguageModel.GenerateAsync(...) ...

tracerProvider.ForceFlush();
meterProvider.ForceFlush();
```

**Run output (verbatim, trimmed):**

```
== Driving real DotnetNativeInterop.Engine work ==
feature run: ok=True result=b = [0, 1, 2, 3, 4]
rag run: You asked: "You are a maintenance

== OTel SDK capture (via AddSource/AddMeter("Dni.Engine")) ==
Captured activities: 4
  span: aspire.demo.feature.collection-expressions    duration_us=  147443.3  source=Dni.Engine
  span: rag.retrieve                                  duration_us=   11420.8  source=Dni.Engine
  span: rag.prompt                                    duration_us=     580.9  source=Dni.Engine
  span: rag.generate                                  duration_us=   86567.1  source=Dni.Engine
Captured metric points: 2
  instrument: dni.spans.recorded        type=LongSum              points=1
  instrument: dni.span.duration         type=Histogram            points=4

GATE RESULT: allExpectedSpansCaptured=True dniEngineMetricsCaptured=True
PASS
```

Package versions: `OpenTelemetry` 1.15.3, `OpenTelemetry.Exporter.InMemory` 1.15.3 (same SDK version
line as `ServiceDefaults`' `OpenTelemetry.Extensions.Hosting` 1.15.3 — no version skew).

### 4. AppHost restore — BLOCKED by sandbox network egress

Three attempts, all failing on the same handful of large packages:

| Attempt | Settings | Duration | Result |
|---|---|---|---|
| 1 | plain `dotnet new aspire-apphost` (default restore) | 25.06 min | Failed — timeouts + EOF on `Aspire.Dashboard.Sdk.win-x64`, `Aspire.Hosting.Orchestration.win-x64`, `Grpc.Tools`, `KubernetesClient`, `StreamJsonRpc` |
| 2 | `NUGET_ENABLE_ENHANCED_HTTP_RETRY=true`, max-try=6, `dotnet restore` | 13.93 min | Failed — same packages, same failure modes plus one DNS resolution failure (`No such host is known`) |
| 3 | Cleared `dotnet nuget locals http-cache`/`temp`, max-try=10, `--disable-parallel` | (ongoing/failed, same packages) | Failed — identical packages fail again |

Representative errors (verbatim):

```
Failed to download package 'Aspire.Dashboard.Sdk.win-x64.13.4.6' from '...'.
The download of '...' timed out because no data was received for 60000ms.
    The operation has timed out.
Failed to download package 'KubernetesClient.19.0.2' from '...'.
Received an unexpected EOF or 0 bytes from the transport stream.
Failed to download package 'Grpc.Tools.2.80.0' from '...'.
No such host is known. (api.nuget.org:443)
```

**Why these packages specifically**: the Aspire SDK's `AppHost` project unconditionally references
the DCP orchestrator (`Aspire.Hosting.Orchestration.win-x64`) and the dashboard's self-contained web
app (`Aspire.Dashboard.Sdk.win-x64`) — both large binary payloads — plus `Grpc.Tools`/
`KubernetesClient`/`StreamJsonRpc` as their transitive dependencies (gRPC codegen tooling and
Kubernetes-deployment-target support, pulled in regardless of whether you deploy to k8s). Every
*small* package in this same exercise (`ServiceDefaults`'s entire OTel/resilience/service-discovery
set, `OpenTelemetry.Exporter.InMemory`, the engine's own `Microsoft.ML.OnnxRuntime`/
`Microsoft.Extensions.AI.Abstractions`) restored without issue, repeatedly, in well under a minute.
The consistent pattern — large payloads fail via timeout/EOF/occasional DNS failure, small payloads
never fail — points at this sandbox's network egress being unable to sustain large or long-lived
downloads, not at anything wrong with the packages or the AppHost project shape.

## Verdict

**PASS on the actual question** ("does Aspire 13.x + .NET 10 + OTel export of `Dni.Engine` work?"):
yes — `Dni.Engine`'s `ActivitySource`/`Meter`, once subscribed via the same `AddSource`/`AddMeter`
calls Aspire's `ServiceDefaults` pattern expects, produce genuine OTel SDK records ready for OTLP
export, verified end-to-end in-process. The `aspire/` example (Step 2) is built on exactly this
pattern and should work unmodified on any machine with normal NuGet access to `api.nuget.org` — this
sandbox's inability to download ~3 large packages is the only open item, and it is an infrastructure
constraint of *this* environment, not of the approach.

**BLOCKED IN THIS SANDBOX**, not resolved: actually launching `dotnet run` on
`DniObservability.AppHost` and observing the Dashboard UI / DCP-orchestrated resource. Whoever picks
this up next on a machine with unrestricted NuGet access should just be able to run
`dotnet run --project aspire/DniObservability.AppHost` and see it work — nothing in the code needs to
change for that.

## Caveats

- **Aspire's AppHost is NOT AOT and does not ship on-device.** It is a `net10.0` JIT-compiled dev
  tool (`OutputType=Exe`, no `PublishAot`) that orchestrates a local dashboard + the services it's
  told about, for the local dev inner loop only. The shipped mobile artifact remains the NativeAOT
  `DotnetNativeInterop.NativeBridge` library built for `ios-arm64`/`linux-bionic-arm64` — completely
  separate from anything under `aspire/`.
- **`EngineHost` runs the engine as ordinary managed code**, not NativeAOT. `DotnetNativeInterop.
  Engine`'s `IsAotCompatible=true` is irrelevant in this context — it just happens to also be true,
  it isn't being tested here. Nothing about this example proves or re-proves AOT compatibility; that
  is already covered by the mobile build pipeline and the other `docs/*-findings.md` gates.
- **`ServiceDefaults` stays 100% stock** — the Aspire template's own `Extensions.cs`, unmodified. All
  `Dni.Engine`-specific OTel wiring lives in `EngineHost`'s `Program.cs` alone, so `ServiceDefaults`
  remains reusable if this example ever grows a second service.
- **`aspire/` has its own `Directory.Build.props`** (see `aspire/Directory.Build.props`), scoped only
  to that subtree, so the repo-root `Directory.Build.props`'s `TreatWarningsAsErrors`/
  `EnforceCodeStyleInBuild`/analyzer defaults (tuned for the shipped AOT engine/bridge/UI code) don't
  leak into scaffolded Aspire template code that wasn't written with those rules in mind. One
  violation *was* fixed properly rather than suppressed: `EngineDriver`'s logging was rewritten with
  `[LoggerMessage]` source-generated delegates (CA1848/CA1873) since that's simply better practice,
  not a rule worth turning off.
- **No `.NET 10 SDK`/Aspire compatibility issues found anywhere in this gate.** Every failure was
  network-shaped (timeout/EOF/DNS), never a version-resolution, template, or build error.
