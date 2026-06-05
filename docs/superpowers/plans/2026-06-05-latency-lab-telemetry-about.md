# Latency Lab + Live Engine Telemetry + Rich About Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deepen the Latency tab into a hub (3-transport overlay + percentiles + throughput, jitter time-series, payload-size scaling), add live engine telemetry via the project's first C ABI export (`dni_engine_stats`), and expand About into a rich explainer with live runtime facts.

**Architecture:** Additive. One new C ABI export (`dni_engine_stats` → runtime-stats JSON, surfaced to Swift through the existing bridging `#import` of `dni.h`) plus a `bench-echo` command that rides the Phase 1 `Run(id)` grammar (no ABI change) for payload scaling. The Latency tab becomes a hub of focused SwiftUI screens; the existing histogram is relocated intact; telemetry polls the new export on a timer; About gains live facts.

**Tech Stack:** .NET 10 / C# 14 (NativeAOT-safe: source-gen JSON, BCL GC/runtime APIs), SwiftUI + Swift Charts (iOS 17 target, Swift 6 strict concurrency).

---

## File Structure

**Engine (C#):**
- Create `core/DotnetNativeInterop.Engine/EngineTelemetry.cs` — `EngineStats` record, `EngineTelemetry.Snapshot()`/`SnapshotJson()`, source-gen `TelemetryJsonContext`.
- Modify `core/DotnetNativeInterop.Engine/Showcase/ShowcaseCommand.cs` — add the `bench-echo` case + `Echo` helper.
- Create `core/DotnetNativeInterop.NativeBridge/Ffi/Exports.Telemetry.cs` — the `dni_engine_stats` export.
- Modify `core/DotnetNativeInterop.NativeBridge/abi/dni.h` — declare `dni_engine_stats`.

**iOS (Swift) — new `ios/Shared/Telemetry/`:**
- `EngineStats.swift` — Codable mirror. `TelemetryService.swift` — FFI call. `TelemetryPoller.swift` — timer poll loop. `TelemetryStrip.swift` — presentational live strip.

**iOS (Swift) — new `ios/Shared/Latency/`:**
- `LatencyViewModel.swift`, `LatencyStats.swift` (percentile/throughput/CDF helpers), `DistributionView.swift` (relocated histogram + `LatencyBin`), `TransportComparisonView.swift`, `JitterView.swift`, `PayloadScalingView.swift`, `TelemetryView.swift`, `LatencyHubView.swift`.

**iOS (Swift) — modified:**
- Remove `ios/Shared/LatencyView.swift` (histogram relocated into `DistributionView`).
- `ios/Shared/RootTabView.swift` — Latency tab → `LatencyHubView`; pass `latency` + `telemetry`; About gets `telemetry`.
- `ios/Apps/Unified/UnifiedApp.swift` — build `LatencyViewModel` + `TelemetryService`.
- `ios/Shared/AboutView.swift` — rich sections + live facts.

---

### Task 0: Probe harness (Windows)

**Files:** throwaway `%TEMP%\dni-probe` (not committed).

- [ ] **Step 1: Create the probe project**

Run (PowerShell):
```powershell
$probe = "$env:TEMP\dni-probe"
New-Item -ItemType Directory -Force $probe | Out-Null
Set-Location $probe
dotnet new console --force | Out-Null
dotnet add reference "C:\Users\steve\projects\dotnet-ios-android-poc-native-frontend\core\DotnetNativeInterop.Engine\DotnetNativeInterop.Engine.csproj" | Out-Null
```

- [ ] **Step 2: Smoke test**

Overwrite `%TEMP%\dni-probe\Program.cs`:
```csharp
using DotnetNativeInterop.Engine;
if (LanguageFeatureCatalog.Run("ping").Result != "pong") throw new Exception("FAIL: ping");
Console.WriteLine("PASS: harness wired");
```
Run: `dotnet run --project "$env:TEMP\dni-probe"` → Expected `PASS: harness wired`. (No commit.)

---

### Task 1: Engine telemetry snapshot (engine)

**Files:**
- Create: `core/DotnetNativeInterop.Engine/EngineTelemetry.cs`
- Probe: `%TEMP%\dni-probe\Program.cs`

- [ ] **Step 1: Write the failing probe**

Overwrite `%TEMP%\dni-probe\Program.cs`:
```csharp
using DotnetNativeInterop.Engine;
var s = EngineTelemetry.Snapshot();
if (s.HeapBytes <= 0) throw new Exception($"FAIL: heap {s.HeapBytes}");
if (s.ProcessorCount <= 0) throw new Exception($"FAIL: cores {s.ProcessorCount}");
if (s.UptimeMs < 0) throw new Exception($"FAIL: uptime {s.UptimeMs}");
var json = EngineTelemetry.SnapshotJson();
if (!json.Contains("\"heapBytes\":") || !json.Contains("\"processorCount\":")) throw new Exception($"FAIL: json {json}");
Console.WriteLine($"PASS: heap={s.HeapBytes} cores={s.ProcessorCount} threads={s.ThreadCount} gc={s.GcGen0}/{s.GcGen1}/{s.GcGen2}");
```

- [ ] **Step 2: Run probe to verify it fails**

Run: `dotnet run --project "$env:TEMP\dni-probe"` → FAIL (compile error: `EngineTelemetry` not found).

- [ ] **Step 3: Write the implementation**

Create `core/DotnetNativeInterop.Engine/EngineTelemetry.cs`:
```csharp
using System.Diagnostics;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace DotnetNativeInterop.Engine;

/// <summary>A point-in-time snapshot of NativeAOT runtime stats, for the native telemetry UI.</summary>
public sealed record EngineStats(
    int GcGen0, int GcGen1, int GcGen2,
    long HeapBytes, long CommittedBytes, long AllocatedBytes,
    double GcPauseMs, int ThreadCount, int ProcessorCount, double UptimeMs);

/// <summary>
/// Samples live runtime stats (GC, managed heap, threads, uptime) from the BCL — all AOT-safe and
/// reflection-free, so the native UI can show the .NET runtime behaving like a systems runtime.
/// </summary>
public static class EngineTelemetry
{
    private static readonly long StartTimestamp = Stopwatch.GetTimestamp();

    /// <summary>Captures current runtime stats. GC pause duration is guarded (0 if unsupported).</summary>
    public static EngineStats Snapshot()
    {
        var info = GC.GetGCMemoryInfo();
        var heap = info.HeapSizeBytes > 0 ? info.HeapSizeBytes : GC.GetTotalMemory(false);

        double pauseMs;
        try
        {
            pauseMs = GC.GetTotalPauseDuration().TotalMilliseconds;
        }
        catch
        {
            pauseMs = 0;
        }

        return new EngineStats(
            GC.CollectionCount(0), GC.CollectionCount(1), GC.CollectionCount(2),
            heap, info.TotalCommittedBytes, GC.GetTotalAllocatedBytes(),
            pauseMs, ThreadPool.ThreadCount, Environment.ProcessorCount,
            Stopwatch.GetElapsedTime(StartTimestamp).TotalMilliseconds);
    }

    /// <summary>Serializes <see cref="Snapshot"/> to camelCase JSON via the source-gen context.</summary>
    public static string SnapshotJson() =>
        JsonSerializer.Serialize(Snapshot(), TelemetryJsonContext.Default.EngineStats);
}

/// <summary>Source-generated JSON metadata for <see cref="EngineStats"/> (AOT-safe, no reflection).</summary>
[JsonSourceGenerationOptions(PropertyNamingPolicy = JsonKnownNamingPolicy.CamelCase)]
[JsonSerializable(typeof(EngineStats))]
internal sealed partial class TelemetryJsonContext : JsonSerializerContext;
```

- [ ] **Step 4: Run probe to verify it passes**

Run: `dotnet run --project "$env:TEMP\dni-probe"` → Expected `PASS: heap=… cores=… threads=… gc=…/…/…`.

- [ ] **Step 5: Commit**

```powershell
git add core/DotnetNativeInterop.Engine/EngineTelemetry.cs
git commit -m "feat: add AOT-safe engine telemetry snapshot (GC/heap/threads)"
```

---

### Task 2: Payload-scaling `bench-echo` command (engine)

**Files:**
- Modify: `core/DotnetNativeInterop.Engine/Showcase/ShowcaseCommand.cs`
- Probe: `%TEMP%\dni-probe\Program.cs`

- [ ] **Step 1: Write the failing probe**

Overwrite `%TEMP%\dni-probe\Program.cs`:
```csharp
using DotnetNativeInterop.Engine;
var r = LanguageFeatureCatalog.Run("bench-echo~bytes_4096");
if (!r.Ok || r.Result.Length != 4096) throw new Exception($"FAIL: ok={r.Ok} len={r.Result.Length}");
var clampLow = LanguageFeatureCatalog.Run("bench-echo~bytes_0");
if (clampLow.Result.Length != 1) throw new Exception($"FAIL: low clamp {clampLow.Result.Length}");
var clampHigh = LanguageFeatureCatalog.Run("bench-echo~bytes_99999999");
if (clampHigh.Result.Length != 1048576) throw new Exception($"FAIL: high clamp {clampHigh.Result.Length}");
Console.WriteLine($"PASS: echo 4096 + clamps [{clampLow.Result.Length}, {clampHigh.Result.Length}]");
```

- [ ] **Step 2: Run probe to verify it fails**

Run: `dotnet run --project "$env:TEMP\dni-probe"` → FAIL (`bench-echo` is an unknown command → `Ok == false`).

- [ ] **Step 3: Write the implementation**

In `core/DotnetNativeInterop.Engine/Showcase/ShowcaseCommand.cs`, add a switch arm to the `result` switch (after the `bench-parallel` arm, before the `_ =>` default):
```csharp
                "bench-echo" => Echo(GetI(p, "bytes", 1024, 1, 1_048_576)),
```
And add this helper method to the `ShowcaseCommand` class (after `Run`):
```csharp
    // Returns an N-byte ASCII payload; the latency lab times the round-trip to measure transport
    // cost vs response size. N is clamped by the caller via GetI.
    private static string Echo(int bytes) => new string('x', bytes);
```

- [ ] **Step 4: Run probe to verify it passes**

Run: `dotnet run --project "$env:TEMP\dni-probe"` → Expected `PASS: echo 4096 + clamps [1, 1048576]`.

- [ ] **Step 5: Commit**

```powershell
git add core/DotnetNativeInterop.Engine/Showcase/ShowcaseCommand.cs
git commit -m "feat: add bench-echo payload-scaling command (no ABI change)"
```

---

### Task 3: `dni_engine_stats` export + ABI header (engine)

**Files:**
- Create: `core/DotnetNativeInterop.NativeBridge/Ffi/Exports.Telemetry.cs`
- Modify: `core/DotnetNativeInterop.NativeBridge/abi/dni.h`

> The C export can't be invoked from the managed probe (it's `UnmanagedCallersOnly`); its body is just `EngineTelemetry.SnapshotJson()` (already probe-verified in Task 1). This task's gate is a clean managed build.

- [ ] **Step 1: Write the export**

Create `core/DotnetNativeInterop.NativeBridge/Ffi/Exports.Telemetry.cs`:
```csharp
using System.Runtime.InteropServices;
using DotnetNativeInterop.Engine;

namespace DotnetNativeInterop.NativeBridge.Ffi;

/// <summary>
/// Engine-introspection export (FFI): returns a JSON snapshot of live runtime stats. Heap UTF-8;
/// the caller copies the text then releases it with the existing <c>dni_string_free</c>.
/// </summary>
internal static class ExportsTelemetry
{
    /// <summary>Returns live engine stats as JSON. 0 on failure.</summary>
    [UnmanagedCallersOnly(EntryPoint = "dni_engine_stats")]
    public static nint EngineStats()
    {
        try
        {
            return NativeText.Allocate(EngineTelemetry.SnapshotJson());
        }
        catch (Exception)
        {
            return 0;
        }
    }
}
```

- [ ] **Step 2: Declare it in the ABI header**

In `core/DotnetNativeInterop.NativeBridge/abi/dni.h`, add this block immediately before the `#ifdef __cplusplus` closing `}` near the end (after the SQLCipher feature-route comment):
```c
/* ---- Engine introspection ----------------------------------------------- */
/* Returns heap UTF-8 JSON of live runtime stats
 * {gcGen0,gcGen1,gcGen2,heapBytes,committedBytes,allocatedBytes,gcPauseMs,
 *  threadCount,processorCount,uptimeMs}; copy then release with dni_string_free. */
const char* dni_engine_stats(void);
```

- [ ] **Step 3: Verify the managed build**

Run: `dotnet build DotnetNativeInterop.slnx -c Release`
Expected: `Build succeeded.` `0 Error(s)` (2 pre-existing `CS1668` env warnings are unrelated).

- [ ] **Step 4: Commit**

```powershell
git add core/DotnetNativeInterop.NativeBridge/Ffi/Exports.Telemetry.cs core/DotnetNativeInterop.NativeBridge/abi/dni.h
git commit -m "feat: add dni_engine_stats C ABI export for engine telemetry"
```

- [ ] **Step 5: Tear down the probe (not committed)**

Run: `Remove-Item -Recurse -Force "$env:TEMP\dni-probe"`

---

### Task 4: iOS telemetry model + service (swift)

> Swift can't compile on Windows — acceptance is the on-device build (Task 14). Copy the plan's code exactly.

**Files:**
- Create: `ios/Shared/Telemetry/EngineStats.swift`
- Create: `ios/Shared/Telemetry/TelemetryService.swift`

- [ ] **Step 1: Write `EngineStats`**

Create `ios/Shared/Telemetry/EngineStats.swift`:
```swift
import Foundation

/// Codable mirror of the engine's `dni_engine_stats` JSON (camelCase).
struct EngineStats: Codable, Sendable {
    let gcGen0: Int
    let gcGen1: Int
    let gcGen2: Int
    let heapBytes: Int
    let committedBytes: Int
    let allocatedBytes: Int
    let gcPauseMs: Double
    let threadCount: Int
    let processorCount: Int
    let uptimeMs: Double
}
```

- [ ] **Step 2: Write `TelemetryService`**

Create `ios/Shared/Telemetry/TelemetryService.swift`:
```swift
import Foundation

/// Reads live engine stats over the in-process C ABI. FFI-only: runtime introspection is engine-global,
/// not transport-specific. Copies + frees the returned heap UTF-8 JSON, then decodes it.
struct TelemetryService: Sendable {
    func stats() async throws -> EngineStats {
        let json = try await Task.detached(priority: .utility) { () throws -> String in
            guard let ptr = dni_engine_stats() else { throw FeatureServiceError.nullResult }
            defer { dni_string_free(ptr) }
            return String(cString: ptr)
        }.value
        return try JSONDecode.decode(EngineStats.self, from: json)
    }
}
```

- [ ] **Step 3: Commit**

```powershell
git add ios/Shared/Telemetry/EngineStats.swift ios/Shared/Telemetry/TelemetryService.swift
git commit -m "feat: add iOS engine-stats model + FFI telemetry service"
```

---

### Task 5: Telemetry poller + live strip (swift)

**Files:**
- Create: `ios/Shared/Telemetry/TelemetryPoller.swift`
- Create: `ios/Shared/Telemetry/TelemetryStrip.swift`

- [ ] **Step 1: Write `TelemetryPoller`**

Create `ios/Shared/Telemetry/TelemetryPoller.swift`:
```swift
import Foundation

/// Polls the engine telemetry on a timer while running, keeping the latest stats and a short
/// heap-size history (MB) for charting. State lives here (not @State) so the loop reads it live.
@MainActor
final class TelemetryPoller: ObservableObject {
    @Published var stats: EngineStats?
    @Published var heapHistory: [Double] = []
    @Published var errorMessage: String?

    private let service: TelemetryService
    private let intervalNs: UInt64
    private let historyCap = 120

    init(service: TelemetryService, intervalMs: UInt64 = 300) {
        self.service = service
        self.intervalNs = intervalMs * 1_000_000
    }

    func loop() async {
        while !Task.isCancelled {
            do {
                let snapshot = try await service.stats()
                stats = snapshot
                heapHistory.append(Double(snapshot.heapBytes) / 1_048_576)
                if heapHistory.count > historyCap {
                    heapHistory.removeFirst(heapHistory.count - historyCap)
                }
                errorMessage = nil
            } catch {
                errorMessage = error.localizedDescription
            }
            try? await Task.sleep(nanoseconds: intervalNs)
        }
    }
}
```

- [ ] **Step 2: Write `TelemetryStrip`**

Create `ios/Shared/Telemetry/TelemetryStrip.swift`:
```swift
import SwiftUI

/// Presentational live readout of engine runtime stats. No polling — pass in the latest `EngineStats`.
struct TelemetryStrip: View {
    let stats: EngineStats?

    var body: some View {
        if let s = stats {
            Grid(alignment: .leading, horizontalSpacing: 16, verticalSpacing: 6) {
                GridRow { cell("heap", mb(s.heapBytes)); cell("alloc", mb(s.allocatedBytes)) }
                GridRow { cell("GC 0/1/2", "\(s.gcGen0)/\(s.gcGen1)/\(s.gcGen2)"); cell("GC pause", String(format: "%.0f ms", s.gcPauseMs)) }
                GridRow { cell("threads", "\(s.threadCount)"); cell("cores", "\(s.processorCount)") }
                GridRow { cell("committed", mb(s.committedBytes)); cell("uptime", String(format: "%.0f s", s.uptimeMs / 1000)) }
            }
            .font(.caption.monospacedDigit())
        } else {
            Text("telemetry unavailable").font(.caption).foregroundStyle(.secondary)
        }
    }

    private func mb(_ bytes: Int) -> String { String(format: "%.1f MB", Double(bytes) / 1_048_576) }

    private func cell(_ label: String, _ value: String) -> some View {
        HStack(spacing: 6) {
            Text(label).foregroundStyle(.secondary)
            Text(value)
        }
    }
}
```

- [ ] **Step 3: Commit**

```powershell
git add ios/Shared/Telemetry/TelemetryPoller.swift ios/Shared/Telemetry/TelemetryStrip.swift
git commit -m "feat: add telemetry poll loop + live readout strip"
```

---

### Task 6: Latency view model + stats helpers (swift)

**Files:**
- Create: `ios/Shared/Latency/LatencyViewModel.swift`
- Create: `ios/Shared/Latency/LatencyStats.swift`

- [ ] **Step 1: Write `LatencyViewModel`**

Create `ios/Shared/Latency/LatencyViewModel.swift`:
```swift
import Foundation

/// Backs the Latency Lab: holds every transport's service and the selected transport, and times
/// client-side round-trips of `ping` / `bench-echo` commands over a chosen transport.
@MainActor
final class LatencyViewModel: ObservableObject {
    @Published var transport: TransportKind = .ffi

    let services: [TransportKind: FeatureService]

    init(services: [TransportKind: FeatureService]) {
        self.services = services
    }

    /// One client-side round-trip of `command` over `kind`, in milliseconds (nil on failure).
    func roundTripMs(_ command: String, on kind: TransportKind) async -> Double? {
        guard let service = services[kind] else { return nil }
        let start = DispatchTime.now().uptimeNanoseconds
        guard (try? await service.run(command)) != nil else { return nil }
        return Double(DispatchTime.now().uptimeNanoseconds - start) / 1_000_000
    }

    /// `count` sequential `ping` round-trips over `kind` (pure transport overhead).
    func pingSeries(count: Int, on kind: TransportKind) async -> [Double] {
        var samples: [Double] = []
        samples.reserveCapacity(count)
        for _ in 0..<count {
            if let ms = await roundTripMs("ping", on: kind) { samples.append(ms) }
        }
        return samples
    }
}
```

- [ ] **Step 2: Write `LatencyStats`**

Create `ios/Shared/Latency/LatencyStats.swift`:
```swift
import Foundation

/// Pure helpers for latency analysis: percentiles, throughput, and CDF points for distribution overlays.
enum LatencyStats {
    static func percentile(_ sorted: [Double], _ p: Double) -> Double {
        guard !sorted.isEmpty else { return 0 }
        return sorted[min(sorted.count - 1, Int(p * Double(sorted.count)))]
    }

    struct Summary {
        let p50: Double
        let p95: Double
        let p99: Double
        let max: Double
        let throughput: Double   // calls/sec
    }

    static func summary(_ samples: [Double]) -> Summary {
        let sorted = samples.sorted()
        let totalMs = samples.reduce(0, +)
        let throughput = totalMs > 0 ? Double(samples.count) / (totalMs / 1000) : 0
        return Summary(
            p50: percentile(sorted, 0.50),
            p95: percentile(sorted, 0.95),
            p99: percentile(sorted, 0.99),
            max: sorted.last ?? 0,
            throughput: throughput)
    }

    /// Cumulative-distribution points (value, fraction in [0,1]) for overlaying distributions.
    static func cdf(_ samples: [Double]) -> [(value: Double, fraction: Double)] {
        let sorted = samples.sorted()
        let n = Double(sorted.count)
        guard n > 0 else { return [] }
        return sorted.enumerated().map { (value: $0.element, fraction: Double($0.offset + 1) / n) }
    }
}
```

- [ ] **Step 3: Commit**

```powershell
git add ios/Shared/Latency/LatencyViewModel.swift ios/Shared/Latency/LatencyStats.swift
git commit -m "feat: add Latency Lab view model + stats helpers"
```

---

### Task 7: Distribution view (relocate the existing histogram) (swift)

**Files:**
- Create: `ios/Shared/Latency/DistributionView.swift`
- Remove: `ios/Shared/LatencyView.swift`

- [ ] **Step 1: Write `DistributionView`** (the existing histogram, adapted to `LatencyViewModel`)

Create `ios/Shared/Latency/DistributionView.swift`:
```swift
import Charts
import SwiftUI

/// Single-transport latency histogram: fires N `ping` round-trips over the selected transport and charts
/// the client-side distribution. (Relocated from the original Latency tab; the engine work is trivial, so
/// the spread is essentially pure transport cost.)
struct DistributionView: View {
    @ObservedObject var model: LatencyViewModel

    @State private var samples: [Double] = []
    @State private var running = false
    private let sampleCount = 300

    var body: some View {
        List {
            Section {
                LabTransportPicker(transport: $model.transport)
                Button {
                    Task { await measure() }
                } label: {
                    if running {
                        HStack(spacing: 8) { ProgressView(); Text("Measuring \(sampleCount) calls…") }
                    } else {
                        Label("Measure \(sampleCount) pings", systemImage: "stopwatch")
                    }
                }
                .disabled(running)
                Text("Each call round-trips a no-op `ping` over \(model.transport.displayName) — the "
                     + "histogram is pure transport cost.")
                    .font(.caption).foregroundStyle(.secondary)
            }

            if samples.isEmpty {
                Section {
                    ContentUnavailableView("No samples yet", systemImage: "chart.bar",
                                           description: Text("Tap Measure to fire \(sampleCount) pings."))
                }
            } else {
                Section("Distribution — \(samples.count) calls") {
                    Chart(bins) { bin in
                        BarMark(x: .value("round-trip (ms)", bin.midpoint), y: .value("calls", bin.count))
                            .foregroundStyle(ComparisonView.color(model.transport))
                    }
                    .chartXAxisLabel("round-trip (ms)")
                    .chartYAxisLabel("calls")
                    .frame(height: 220)
                }
                Section("Stats (ms)") {
                    LabeledContent("min") { mono(samples.min() ?? 0) }
                    LabeledContent("median") { mono(LatencyStats.percentile(samples.sorted(), 0.5)) }
                    LabeledContent("p95") { mono(LatencyStats.percentile(samples.sorted(), 0.95)) }
                    LabeledContent("max") { mono(samples.max() ?? 0) }
                }
            }
        }
        .navigationTitle("Distribution")
    }

    private func mono(_ value: Double) -> some View {
        Text(String(format: "%.3f", value)).monospacedDigit()
    }

    @MainActor
    private func measure() async {
        running = true
        defer { running = false }
        samples = await model.pingSeries(count: sampleCount, on: model.transport)
    }

    private var bins: [LatencyBin] { LatencyBin.make(from: samples, bucketCount: 24) }
}

/// One histogram bucket: its midpoint (ms) and how many samples fell in it.
struct LatencyBin: Identifiable {
    let id = UUID()
    let midpoint: Double
    let count: Int

    static func make(from samples: [Double], bucketCount: Int) -> [LatencyBin] {
        guard let low = samples.min(), let high = samples.max() else { return [] }
        guard high > low else { return [LatencyBin(midpoint: low, count: samples.count)] }
        let width = (high - low) / Double(bucketCount)
        var counts = [Int](repeating: 0, count: bucketCount)
        for sample in samples {
            let index = min(bucketCount - 1, Int((sample - low) / width))
            counts[index] += 1
        }
        return counts.enumerated().map { index, count in
            LatencyBin(midpoint: low + ((Double(index) + 0.5) * width), count: count)
        }
    }
}
```

- [ ] **Step 2: Remove the superseded file**

Run: `git rm ios/Shared/LatencyView.swift`
(The histogram now lives in `DistributionView`; `LatencyBin` moved with it. `RootTabView` is repointed in Task 12 — until then the project won't reference the new hub, but `git rm` + the new file keep the tree consistent.)

- [ ] **Step 3: Commit**

```powershell
git add ios/Shared/Latency/DistributionView.swift
git commit -m "feat: relocate the ping histogram into DistributionView"
```

---

### Task 8: Transport comparison (overlay + percentiles + throughput) (swift)

**Files:**
- Create: `ios/Shared/Latency/TransportComparisonView.swift`

- [ ] **Step 1: Write the view**

Create `ios/Shared/Latency/TransportComparisonView.swift`:
```swift
import Charts
import SwiftUI

/// Fires N pings over ALL three transports, then shows a CDF overlay of their distributions plus a
/// per-transport p50/p95/p99/max + throughput table — the cost gap, quantified.
struct TransportComparisonView: View {
    @ObservedObject var model: LatencyViewModel

    @State private var samples: [TransportKind: [Double]] = [:]
    @State private var running = false
    private let count = 200

    var body: some View {
        List {
            Section {
                Button {
                    Task { await run() }
                } label: {
                    if running {
                        HStack(spacing: 8) { ProgressView(); Text("Comparing…") }
                    } else {
                        Label("Compare all transports", systemImage: "chart.line.uptrend.xyaxis")
                    }
                }
                .disabled(running)
                Text("Fires \(count) pings over FFI, HTTP, and SQLCipher and compares the distributions.")
                    .font(.caption).foregroundStyle(.secondary)
            }

            if !samples.isEmpty {
                Section("Percentiles + throughput") {
                    ForEach(TransportKind.allCases) { kind in
                        let s = LatencyStats.summary(samples[kind] ?? [])
                        VStack(alignment: .leading, spacing: 4) {
                            Text(kind.displayName).font(.subheadline)
                            HStack {
                                stat("p50", s.p50); stat("p95", s.p95); stat("p99", s.p99); stat("max", s.max)
                            }
                            Text("\(Int(s.throughput)) calls/sec").font(.caption2).foregroundStyle(.secondary)
                        }
                        .padding(.vertical, 2)
                    }
                }
                Section("Distribution overlay (CDF)") {
                    Chart {
                        ForEach(TransportKind.allCases) { kind in
                            ForEach(Array(LatencyStats.cdf(samples[kind] ?? []).enumerated()), id: \.offset) { _, point in
                                LineMark(x: .value("ms", point.value), y: .value("fraction", point.fraction))
                                    .foregroundStyle(by: .value("transport", kind.displayName))
                            }
                        }
                    }
                    .chartXAxisLabel("round-trip (ms)")
                    .chartYAxisLabel("cumulative fraction")
                    .chartLegend(position: .bottom)
                    .frame(height: 240)
                }
            }
        }
        .navigationTitle("Transport comparison")
    }

    private func stat(_ label: String, _ value: Double) -> some View {
        VStack(spacing: 1) {
            Text(label).font(.caption2).foregroundStyle(.secondary)
            Text(String(format: "%.2f", value)).font(.caption.monospacedDigit())
        }
        .frame(maxWidth: .infinity)
    }

    @MainActor
    private func run() async {
        running = true
        defer { running = false }
        var collected: [TransportKind: [Double]] = [:]
        for kind in TransportKind.allCases {
            collected[kind] = await model.pingSeries(count: count, on: kind)
        }
        samples = collected
    }
}
```

- [ ] **Step 2: Commit**

```powershell
git add ios/Shared/Latency/TransportComparisonView.swift
git commit -m "feat: add 3-transport latency comparison (CDF + percentiles + throughput)"
```

---

### Task 9: Jitter time-series (swift)

**Files:**
- Create: `ios/Shared/Latency/JitterView.swift`

- [ ] **Step 1: Write the view**

Create `ios/Shared/Latency/JitterView.swift`:
```swift
import Charts
import SwiftUI

/// Latency vs call index over the selected transport — shows cold→warm steady state and GC jitter
/// (NativeAOT has no JIT warmup, so the line is flat from the first call apart from GC blips).
struct JitterView: View {
    @ObservedObject var model: LatencyViewModel

    @State private var samples: [Double] = []
    @State private var running = false
    private let count = 400

    var body: some View {
        List {
            Section {
                LabTransportPicker(transport: $model.transport)
                Button {
                    Task { await run() }
                } label: {
                    if running {
                        HStack(spacing: 8) { ProgressView(); Text("Sampling…") }
                    } else {
                        Label("Sample \(count) sequential pings", systemImage: "waveform.path.ecg")
                    }
                }
                .disabled(running)
                Text("Each point is one `ping` round-trip in order over \(model.transport.displayName).")
                    .font(.caption).foregroundStyle(.secondary)
            }

            if !samples.isEmpty {
                Section("Latency over call index (ms)") {
                    Chart {
                        ForEach(Array(samples.enumerated()), id: \.offset) { index, ms in
                            LineMark(x: .value("call #", index), y: .value("ms", ms))
                                .foregroundStyle(ComparisonView.color(model.transport))
                        }
                    }
                    .chartXAxisLabel("call #")
                    .chartYAxisLabel("round-trip (ms)")
                    .frame(height: 240)
                }
            }
        }
        .navigationTitle("Jitter over time")
    }

    @MainActor
    private func run() async {
        running = true
        defer { running = false }
        samples = await model.pingSeries(count: count, on: model.transport)
    }
}
```

- [ ] **Step 2: Commit**

```powershell
git add ios/Shared/Latency/JitterView.swift
git commit -m "feat: add latency jitter time-series view"
```

---

### Task 10: Payload-size scaling (swift)

**Files:**
- Create: `ios/Shared/Latency/PayloadScalingView.swift`

- [ ] **Step 1: Write the view**

Create `ios/Shared/Latency/PayloadScalingView.swift`:
```swift
import Charts
import SwiftUI

/// Latency vs response size: sweeps `bench-echo~bytes_N` over increasing N for the selected transport,
/// averaging a few reps per size — exposes where serialization/copy cost dominates per transport.
struct PayloadScalingView: View {
    @ObservedObject var model: LatencyViewModel

    @State private var points: [(label: String, ms: Double)] = []
    @State private var running = false

    private let sizes: [(label: String, bytes: Int)] =
        [("64 B", 64), ("1 KB", 1_024), ("16 KB", 16_384), ("256 KB", 262_144), ("1 MB", 1_048_576)]
    private let reps = 5

    var body: some View {
        List {
            Section {
                LabTransportPicker(transport: $model.transport)
                Button {
                    Task { await run() }
                } label: {
                    if running {
                        HStack(spacing: 8) { ProgressView(); Text("Sweeping…") }
                    } else {
                        Label("Sweep payload sizes", systemImage: "arrow.up.right.square")
                    }
                }
                .disabled(running)
                Text("Round-trips an N-byte `bench-echo` over \(model.transport.displayName), averaged "
                     + "over \(reps) reps per size.")
                    .font(.caption).foregroundStyle(.secondary)
            }

            if !points.isEmpty {
                Section("Latency vs payload size (ms)") {
                    Chart {
                        ForEach(Array(points.enumerated()), id: \.offset) { _, point in
                            BarMark(x: .value("size", point.label), y: .value("ms", point.ms))
                                .foregroundStyle(ComparisonView.color(model.transport))
                        }
                    }
                    .chartYAxisLabel("round-trip (ms)")
                    .frame(height: 240)
                }
                Section("Values") {
                    ForEach(Array(points.enumerated()), id: \.offset) { _, point in
                        LabeledContent(point.label) {
                            Text(String(format: "%.2f ms", point.ms)).monospacedDigit()
                        }
                    }
                }
            }
        }
        .navigationTitle("Payload scaling")
    }

    @MainActor
    private func run() async {
        running = true
        defer { running = false }
        var collected: [(label: String, ms: Double)] = []
        for size in sizes {
            var total = 0.0
            var hits = 0
            for _ in 0..<reps {
                if let ms = await model.roundTripMs("bench-echo~bytes_\(size.bytes)", on: model.transport) {
                    total += ms
                    hits += 1
                }
            }
            collected.append((label: size.label, ms: hits > 0 ? total / Double(hits) : 0))
        }
        points = collected
    }
}
```

- [ ] **Step 2: Commit**

```powershell
git add ios/Shared/Latency/PayloadScalingView.swift
git commit -m "feat: add payload-size scaling sweep view"
```

---

### Task 11: Live telemetry view (stress + strip + heap chart) (swift)

**Files:**
- Create: `ios/Shared/Latency/TelemetryView.swift`

- [ ] **Step 1: Write the view**

Create `ios/Shared/Latency/TelemetryView.swift`:
```swift
import Charts
import SwiftUI

/// Watch the NativeAOT runtime move: a live telemetry strip + managed-heap chart that update on a timer
/// while an optional "stress" loop hammers the Phase 1 benchmarks (which allocate), driving GC.
struct TelemetryView: View {
    @ObservedObject var model: LatencyViewModel
    @StateObject private var poller: TelemetryPoller
    @State private var stressing = false

    init(service: TelemetryService, model: LatencyViewModel) {
        _model = ObservedObject(wrappedValue: model)
        _poller = StateObject(wrappedValue: TelemetryPoller(service: service))
    }

    var body: some View {
        List {
            Section {
                Toggle("Run stress (loop benchmarks)", isOn: $stressing)
                Text("Stress loops `bench-parallel` over FFI to allocate and churn the GC. Watch heap, "
                     + "GC counts, and pause time move live.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Section("Live runtime") {
                TelemetryStrip(stats: poller.stats)
                if let error = poller.errorMessage {
                    Text(error).font(.caption).foregroundStyle(.red)
                }
            }
            Section("Managed heap (MB)") {
                Chart {
                    ForEach(Array(poller.heapHistory.enumerated()), id: \.offset) { index, mb in
                        LineMark(x: .value("t", index), y: .value("MB", mb))
                            .foregroundStyle(.green)
                    }
                }
                .chartYAxisLabel("heap MB")
                .frame(height: 200)
            }
        }
        .navigationTitle("Engine telemetry")
        .task { await poller.loop() }
        .task(id: stressing) { await stressLoop() }
    }

    private func stressLoop() async {
        while stressing && !Task.isCancelled {
            _ = await model.roundTripMs("bench-parallel~size_320", on: .ffi)
        }
    }
}
```

- [ ] **Step 2: Commit**

```powershell
git add ios/Shared/Latency/TelemetryView.swift
git commit -m "feat: add live engine-telemetry view with stress loop + heap chart"
```

---

### Task 12: Latency hub + app wiring (swift)

**Files:**
- Create: `ios/Shared/Latency/LatencyHubView.swift`
- Modify: `ios/Shared/RootTabView.swift`
- Modify: `ios/Apps/Unified/UnifiedApp.swift`

- [ ] **Step 1: Write `LatencyHubView`**

Create `ios/Shared/Latency/LatencyHubView.swift`:
```swift
import SwiftUI

/// The Latency tab: a hub of focused latency analyses + the live engine-telemetry screen.
struct LatencyHubView: View {
    @ObservedObject var model: LatencyViewModel
    let telemetry: TelemetryService

    var body: some View {
        NavigationStack {
            List {
                Section("Measure") {
                    NavigationLink { DistributionView(model: model) } label: {
                        Label("Distribution", systemImage: "chart.bar")
                    }
                    NavigationLink { TransportComparisonView(model: model) } label: {
                        Label("Transport comparison", systemImage: "chart.line.uptrend.xyaxis")
                    }
                    NavigationLink { JitterView(model: model) } label: {
                        Label("Jitter over time", systemImage: "waveform.path.ecg")
                    }
                    NavigationLink { PayloadScalingView(model: model) } label: {
                        Label("Payload scaling", systemImage: "arrow.up.right.square")
                    }
                }
                Section("Runtime") {
                    NavigationLink { TelemetryView(service: telemetry, model: model) } label: {
                        Label("Engine telemetry", systemImage: "gauge.with.dots.needle.67percent")
                    }
                }
            }
            .navigationTitle("Latency")
        }
    }
}
```

- [ ] **Step 2: Repoint the Latency tab in `RootTabView`**

In `ios/Shared/RootTabView.swift`, add the two new stored properties and replace the Latency tab. The struct becomes:
```swift
struct RootTabView: View {
    @ObservedObject var features: FeaturesViewModel
    @ObservedObject var comparison: ComparisonViewModel
    @ObservedObject var lab: LabViewModel
    @ObservedObject var latency: LatencyViewModel
    let telemetry: TelemetryService

    var body: some View {
        TabView {
            DashboardView(viewModel: features)
                .tabItem { Label("Dashboard", systemImage: "square.grid.2x2") }

            FeaturesView(viewModel: features)
                .tabItem { Label("Features", systemImage: "checkmark.seal") }

            LabView(lab: lab)
                .tabItem { Label("Lab", systemImage: "cpu") }

            ComparisonView(model: comparison)
                .tabItem { Label("Compare", systemImage: "chart.bar.xaxis") }

            LatencyHubView(model: latency, telemetry: telemetry)
                .tabItem { Label("Latency", systemImage: "stopwatch") }

            AboutView(infos: features.orderedInfos, telemetry: telemetry)
                .tabItem { Label("About", systemImage: "info.circle") }
        }
    }
}
```
(`AboutView` gains a `telemetry:` argument here — implemented in Task 13.)

- [ ] **Step 3: Build the new objects in `UnifiedApp`**

In `ios/Apps/Unified/UnifiedApp.swift`, add the `latency` `@StateObject` + the `telemetry` value and pass them down. The type becomes:
```swift
@main
struct DotnetNativeInteropUnifiedApp: App {
    @StateObject private var features: FeaturesViewModel
    @StateObject private var comparison: ComparisonViewModel
    @StateObject private var lab: LabViewModel
    @StateObject private var latency: LatencyViewModel
    private let telemetry = TelemetryService()

    init() {
        let services: [TransportKind: FeatureService] = [
            .ffi: FFIFeatureService(),
            .http: HTTPFeatureService(),
            .sqlite: SQLiteFeatureService(),
        ]
        let infos: [TransportKind: TransportInfo] = [
            .ffi: .ffi,
            .http: .http,
            .sqlite: .sqlite,
        ]
        _features = StateObject(wrappedValue: FeaturesViewModel(services: services, infos: infos))
        _comparison = StateObject(wrappedValue: ComparisonViewModel(services: services))
        _lab = StateObject(wrappedValue: LabViewModel(services: services))
        _latency = StateObject(wrappedValue: LatencyViewModel(services: services))
    }

    var body: some Scene {
        WindowGroup {
            RootTabView(features: features, comparison: comparison, lab: lab,
                        latency: latency, telemetry: telemetry)
        }
    }
}
```

- [ ] **Step 4: Commit**

```powershell
git add ios/Shared/Latency/LatencyHubView.swift ios/Shared/RootTabView.swift ios/Apps/Unified/UnifiedApp.swift
git commit -m "feat: wire the Latency Lab hub + telemetry into the app"
```

---

### Task 13: Rich About with live facts (swift)

**Files:**
- Modify: `ios/Shared/AboutView.swift`

- [ ] **Step 1: Rewrite `AboutView`**

Replace the contents of `ios/Shared/AboutView.swift` with (keeps the existing per-transport sections; adds architecture, an AOT explainer, the "why" deep-dives, and a live-facts section):
```swift
import SwiftUI

/// The project's story: architecture, why NativeAOT, why each transport, the per-transport tradeoffs,
/// and a live snapshot of the running engine's runtime facts.
struct AboutView: View {
    let infos: [TransportInfo]
    let telemetry: TelemetryService

    @State private var stats: EngineStats?

    private let architecture = """
    iOS app (SwiftUI)
      │  ffi · http · sqlcipher
      ▼
    dni  —  NativeAOT shared library (dni.dylib)
      │  C ABI + 3 transport hosts
      ▼
    DotnetNativeInterop.Engine  (pure .NET, AOT-safe)
    """

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("One NativeAOT .NET library, three interop transports. The same C# / .NET feature "
                         + "catalog runs in-process inside the AOT image; the app compares transport cost "
                         + "and shows the runtime's behaviour live.")
                        .font(.callout)
                }

                Section("Architecture") {
                    Text(architecture).font(.system(.caption, design: .monospaced))
                }

                Section("Why NativeAOT") {
                    Text("The engine is compiled ahead-of-time straight to a native binary — no JIT, no "
                         + "runtime install, no separate process. It loads directly into the UI process, so "
                         + "FFI calls are in-memory and there's no JIT warmup (see the Latency jitter view).")
                        .font(.callout)
                }

                Section("Why these transports") {
                    bullet("Raw-socket HTTP, not Kestrel — ASP.NET Core ships no NativeAOT runtime pack for "
                           + "mobile RIDs, so the HTTP host is a hand-rolled HTTP/1.1 + SSE server.")
                    bullet("SQLCipher, not e_sqlite3 — the default SQLite bundle has no iOS native lib; "
                           + "e_sqlcipher is the only one with iOS static libs, so the store is encrypted at rest for free.")
                    bullet("gRPC is kept in the tree but excluded — no NativeAOT mobile runtime pack.")
                }

                Section("Live runtime facts") {
                    if let s = stats {
                        LabeledContent("cores", value: "\(s.processorCount)")
                        LabeledContent("managed heap", value: String(format: "%.1f MB", Double(s.heapBytes) / 1_048_576))
                        LabeledContent("GC collections", value: "\(s.gcGen0)/\(s.gcGen1)/\(s.gcGen2)")
                        LabeledContent("threads", value: "\(s.threadCount)")
                        LabeledContent("uptime", value: String(format: "%.0f s", s.uptimeMs / 1000))
                    } else {
                        Text("Reading engine telemetry…").font(.caption).foregroundStyle(.secondary)
                    }
                }

                ForEach(infos, id: \.id) { info in
                    Section(info.displayName) {
                        Text(info.summary).font(.callout)
                        ForEach(info.features, id: \.self) { feature in
                            Label { Text(feature) } icon: {
                                Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
                            }
                        }
                        ForEach(info.limitations, id: \.self) { limitation in
                            Label { Text(limitation) } icon: {
                                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
                            }
                        }
                    }
                }
            }
            .navigationTitle("About")
            .task { stats = try? await telemetry.stats() }
        }
    }

    private func bullet(_ text: String) -> some View {
        Label { Text(text) } icon: { Image(systemName: "circle.fill").font(.system(size: 5)) }
    }
}
```

- [ ] **Step 2: Commit**

```powershell
git add ios/Shared/AboutView.swift
git commit -m "feat: expand About with architecture, AOT explainer, and live runtime facts"
```

---

### Task 14: Native rebuild (ABI changed) + on-device verification (Mac mini)

> **The C ABI changed** (`dni_engine_stats` added to `dni.h`), so the xcframework **must** be rebuilt — unlike Phase 1, this is mandatory. Build as `steve`; sync a clean tree with `git archive` into a fresh dir (the in-place Mac tree is stale); unlock the keychain inline. See `docs/ios-build-deploy-runbook.md`.

**Files:** none (build + manual verification).

- [ ] **Step 1: Sync the branch to a clean dir on the Mac**

From the Windows repo (branch checked out):
```powershell
ssh steve-mac-mini "zsh -lc 'rm -rf ~/dni-lab-build && mkdir -p ~/dni-lab-build'"
git archive --format=tar HEAD | ssh steve-mac-mini "tar -xf - -C /Users/steve/dni-lab-build"
```

- [ ] **Step 2: Rebuild xcframework, generate, build, install** (one backgrounded script)

Reuse the `~/run-lab-build.sh` pattern from Phase 1 (it `cd`s to `~/dni-lab-build`, unlocks the keychain inline, runs `build/build-ios-framework-device.sh` → `xcodegen generate` → signed `xcodebuild` → `xcrun devicectl device install`). Run it backgrounded with `tee ~/lab-build.log`. Expected tail: `** BUILD SUCCEEDED **` then `App installed`.

- [ ] **Step 3: Verify on device**

- [ ] Latency tab is now a hub: Distribution · Transport comparison · Jitter over time · Payload scaling · Engine telemetry.
- [ ] **Distribution** still works (the relocated histogram).
- [ ] **Transport comparison** — CDF overlay of 3 transports + p50/p95/p99 + throughput table.
- [ ] **Jitter** — flat-ish line with occasional GC blips (no JIT warmup ramp).
- [ ] **Payload scaling** — latency rises with size; the gap between FFI and SQLCipher widens at 256 KB / 1 MB.
- [ ] **Engine telemetry** — strip + heap chart update live; toggling "Run stress" makes GC counts / heap visibly move.
- [ ] **About** — architecture, AOT/why sections, and live runtime facts populated.
- [ ] **No regressions:** Dashboard, Features, Lab, Compare all still work.

---

## Self-Review

**Spec coverage:**
- `dni_engine_stats` export + `EngineTelemetry` → Tasks 1, 3. ✅
- Payload-scaling `bench-echo` (no ABI) → Task 2; UI Task 10. ✅
- Latency hub, augment-in-place, histogram relocated → Tasks 7, 12. ✅
- 3-transport overlay + percentiles + throughput → Task 8. ✅
- Jitter time-series → Task 9. ✅
- Live telemetry while a workload runs → Tasks 5, 11. ✅
- Rich About + live facts → Task 13. ✅
- ABI rebuild mandatory, fresh-dir Mac sync → Task 14. ✅

**Placeholder scan:** none — every code step is complete.

**Type consistency:** `EngineStats` fields match C#↔Swift camelCase (`gcGen0/1/2, heapBytes, committedBytes, allocatedBytes, gcPauseMs, threadCount, processorCount, uptimeMs`). `LatencyViewModel.roundTripMs/pingSeries`, `LatencyStats.summary/percentile/cdf/Summary`, and `TelemetryPoller.stats/heapHistory/errorMessage/loop` are referenced consistently across views. Command strings (`ping`, `bench-echo~bytes_N`, `bench-parallel~size_320`) match Phase 1's `ShowcaseCommand` + Task 2. `RootTabView`/`UnifiedApp` pass `latency` + `telemetry` consistently; `AboutView(infos:telemetry:)` matches Task 13. ✅

---

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-06-05-latency-lab-telemetry-about.md`. Two execution options:**

**1. Subagent-Driven (recommended)** — fresh subagent per task, review between tasks.

**2. Inline Execution** — execute in this session with checkpoints.

**Which approach?**
