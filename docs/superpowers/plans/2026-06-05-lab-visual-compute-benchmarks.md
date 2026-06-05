# Lab — Visual Compute + Heavy Benchmarks Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a new **Lab** tab to the unified iOS app with GPU-free C# visual renderers (colorized fractal explorer, SDF raymarcher) and SIMD/parallel benchmarks, all driven over the existing transports with a live FPS readout.

**Architecture:** Purely additive. A new id grammar (`name~key_value~…`, RFC-3986 unreserved chars only) is carried over the *existing* `LanguageFeatureCatalog.Run(id)` path — so every transport (FFI/HTTP/SQLCipher) lights up with zero new C ABI and zero new native libs. Visual demos return `"WxHxC:base64"` (RGB); benchmarks return a structured JSON payload. One Mac xcframework rebuild for the whole phase. **Nothing existing is removed or modified beyond additive hooks** (one branch in `Run`, one new tab in `RootTabView`, one new `@StateObject` in the app entry).

**Tech Stack:** .NET 10 / C# 14 (NativeAOT-safe: no reflection, source-gen JSON, `System.Numerics.Vector<T>`, `Parallel.For`), SwiftUI + Swift Charts (iOS 17 target, Swift 6 strict concurrency).

---

## File Structure

**Engine (C#) — new files under `core/DotnetNativeInterop.Engine/Showcase/`:**
- `ShowcaseModels.cs` — public records `BenchmarkPayload/BenchmarkSeries/BenchmarkPoint/SummaryStat`, internal source-gen `ShowcaseJsonContext`, public `ShowcaseJson.Serialize`.
- `Raymarcher.cs` — SDF raymarcher → RGB bytes + `"WxHx3:base64"`.
- `Benchmarks.cs` — `MatmulGflops(maxSize)`, `ParallelScaling(size)` → `BenchmarkPayload`.
- `ShowcaseCommand.cs` — `IsCommand(id)` + `Run(id)` parser/dispatcher.

**Engine (C#) — modified (additive only):**
- `FractalRenderer.cs` — ADD `RenderColor(...)` + `RenderColorBase64(...)` + palette; existing grayscale members untouched.
- `LanguageFeatures.cs` — ADD one line at the top of `Run(id)`: route commands to `ShowcaseCommand`.

**iOS (Swift) — new files under `ios/Shared/Lab/`:**
- `ShowcaseModels.swift` — Codable `BenchmarkPayload/Series/Point/SummaryStat`.
- `LabViewModel.swift` — `@MainActor` services holder + `render(_:)` + `@Published transport`.
- `RasterDemo.swift` — `RasterDemo` protocol + shared `renderLoop()` + `RasterCanvas` view + `LabTransportPicker`.
- `FractalExplorerView.swift` — `FractalExplorerModel` + interactive view (pinch/pan/slider/dive).
- `RaymarcherView.swift` — `RaymarcherModel` + rotating view.
- `BenchmarkView.swift` — `BenchmarkChart` + `BenchmarkDetailView`.
- `LabView.swift` — the tab: list linking to each demo.

**iOS (Swift) — modified (additive only):**
- `ios/Shared/VisualFeature.swift` — extend `image(from:)` to decode `WxHxC` (RGB) as well as `WxH` (gray).
- `ios/Shared/RootTabView.swift` — insert the Lab tab.
- `ios/Apps/Unified/UnifiedApp.swift` — build a `LabViewModel`, pass to `RootTabView`.

---

### Task 0: Throwaway engine-probe harness (Windows, no device)

The repo has no XCTest/unit-test project; the established pattern (see the prior spec) is a throwaway console in `%TEMP%` that references the Engine, asserts, and is deleted. Engine tasks below are TDD'd against it (probe red → implement → probe green → commit the engine code only; the probe is never committed). Swift tasks can't run from Windows — they're verified by the on-device build in Task 14.

**Files:** Create (throwaway, not committed): `%TEMP%\dni-probe\` console project.

- [ ] **Step 1: Create the probe project (once; persists across engine tasks)**

Run (PowerShell):
```powershell
$probe = "$env:TEMP\dni-probe"
New-Item -ItemType Directory -Force $probe | Out-Null
Set-Location $probe
dotnet new console --force | Out-Null
dotnet add reference "C:\Users\steve\projects\dotnet-ios-android-poc-native-frontend\core\DotnetNativeInterop.Engine\DotnetNativeInterop.Engine.csproj" | Out-Null
```
Expected: a restorable console project referencing the Engine.

- [ ] **Step 2: Smoke-test the reference**

Overwrite `%TEMP%\dni-probe\Program.cs`:
```csharp
using DotnetNativeInterop.Engine;
var run = LanguageFeatureCatalog.Run("ping");
if (run.Result != "pong") throw new Exception($"FAIL: ping returned {run.Result}");
Console.WriteLine("PASS: probe harness wired to Engine");
```
Run: `dotnet run --project "$env:TEMP\dni-probe"`
Expected: `PASS: probe harness wired to Engine`. (No commit — throwaway.)

---

### Task 1: Showcase JSON contract (engine)

**Files:**
- Create: `core/DotnetNativeInterop.Engine/Showcase/ShowcaseModels.cs`
- Probe: `%TEMP%\dni-probe\Program.cs`

- [ ] **Step 1: Write the failing probe**

Overwrite `%TEMP%\dni-probe\Program.cs`:
```csharp
using DotnetNativeInterop.Engine;
var payload = new BenchmarkPayload(
    "benchmark", "demo",
    [ new BenchmarkSeries("scalar", [ new BenchmarkPoint(64, 1.5) ]) ],
    [ new SummaryStat("peak", "9.9") ]);
var json = ShowcaseJson.Serialize(payload);
if (!json.Contains("\"kind\":\"benchmark\"")) throw new Exception($"FAIL: kind missing: {json}");
if (!json.Contains("\"points\":[{\"x\":64,\"y\":1.5}]")) throw new Exception($"FAIL: points shape: {json}");
Console.WriteLine("PASS: " + json);
```

- [ ] **Step 2: Run probe to verify it fails**

Run: `dotnet run --project "$env:TEMP\dni-probe"`
Expected: FAIL — compile error (`BenchmarkPayload`/`ShowcaseJson` do not exist).

- [ ] **Step 3: Write the implementation**

Create `core/DotnetNativeInterop.Engine/Showcase/ShowcaseModels.cs`:
```csharp
using System.Text.Json;
using System.Text.Json.Serialization;

namespace DotnetNativeInterop.Engine;

/// <summary>One (x, y) sample in a benchmark series.</summary>
public sealed record BenchmarkPoint(double X, double Y);

/// <summary>A named series of benchmark points (e.g. "scalar" vs "SIMD").</summary>
public sealed record BenchmarkSeries(string Name, BenchmarkPoint[] Points);

/// <summary>A headline number for a benchmark (e.g. peak GFLOPS, speedup).</summary>
public sealed record SummaryStat(string Label, string Value);

/// <summary>
/// The structured result of a heavy-compute benchmark, embedded as JSON in
/// <see cref="FeatureRun.Result"/> so the native UI can chart it with Swift Charts.
/// </summary>
public sealed record BenchmarkPayload(string Kind, string Title, BenchmarkSeries[] Series, SummaryStat[] Summary);

/// <summary>Source-generated JSON metadata for <see cref="BenchmarkPayload"/> (AOT-safe, no reflection).</summary>
[JsonSourceGenerationOptions(PropertyNamingPolicy = JsonKnownNamingPolicy.CamelCase)]
[JsonSerializable(typeof(BenchmarkPayload))]
internal sealed partial class ShowcaseJsonContext : JsonSerializerContext;

/// <summary>Serializes a <see cref="BenchmarkPayload"/> to camelCase JSON via the source-gen context.</summary>
public static class ShowcaseJson
{
    public static string Serialize(BenchmarkPayload payload) =>
        JsonSerializer.Serialize(payload, ShowcaseJsonContext.Default.BenchmarkPayload);
}
```

- [ ] **Step 4: Run probe to verify it passes**

Run: `dotnet run --project "$env:TEMP\dni-probe"`
Expected: `PASS: {"kind":"benchmark","title":"demo","series":[{"name":"scalar","points":[{"x":64,"y":1.5}]}],"summary":[{"label":"peak","value":"9.9"}]}`

- [ ] **Step 5: Commit**

```powershell
git add core/DotnetNativeInterop.Engine/Showcase/ShowcaseModels.cs
git commit -m "feat: add benchmark JSON contract for the showcase Lab"
```

---

### Task 2: Colorized parametric fractal (engine)

**Files:**
- Modify: `core/DotnetNativeInterop.Engine/FractalRenderer.cs` (ADD members; leave existing intact)
- Probe: `%TEMP%\dni-probe\Program.cs`

- [ ] **Step 1: Write the failing probe**

Overwrite `%TEMP%\dni-probe\Program.cs`:
```csharp
using DotnetNativeInterop.Engine;
var s = FractalRenderer.RenderColorBase64(-0.5, 0.0, 1.0, 100, 64);
if (!s.StartsWith("64x64x3:")) throw new Exception($"FAIL: header {s[..Math.Min(12, s.Length)]}");
var bytes = Convert.FromBase64String(s["64x64x3:".Length..]);
if (bytes.Length != 64 * 64 * 3) throw new Exception($"FAIL: length {bytes.Length}");
Console.WriteLine("PASS: fractal RGB bytes = " + bytes.Length);
```

- [ ] **Step 2: Run probe to verify it fails**

Run: `dotnet run --project "$env:TEMP\dni-probe"`
Expected: FAIL — `RenderColorBase64` does not exist (compile error).

- [ ] **Step 3: Write the implementation**

In `core/DotnetNativeInterop.Engine/FractalRenderer.cs`, add these members inside the existing `FractalRenderer` class (do not touch `Size`, `Render()`, `RenderBase64()`):
```csharp
    /// <summary>
    /// Renders a colorized Mandelbrot for an arbitrary viewport (center + zoom) to row-major 8-bit RGB.
    /// Interior points are black; exterior points get a smooth escape-time color ramp. Square output.
    /// </summary>
    public static byte[] RenderColor(double centerX, double centerY, double zoom, int maxIter, int size)
    {
        var pixels = new byte[size * size * 3];
        var halfWidth = 1.5 / (zoom <= 0 ? 1.0 : zoom);
        double minX = centerX - halfWidth, maxX = centerX + halfWidth;
        double minY = centerY - halfWidth, maxY = centerY + halfWidth;

        for (var py = 0; py < size; py++)
        {
            var y0 = minY + ((maxY - minY) * py / (size - 1));
            for (var px = 0; px < size; px++)
            {
                var x0 = minX + ((maxX - minX) * px / (size - 1));
                double x = 0, y = 0;
                var iteration = 0;
                while ((x * x) + (y * y) <= 4.0 && iteration < maxIter)
                {
                    var xTemp = (x * x) - (y * y) + x0;
                    y = (2 * x * y) + y0;
                    x = xTemp;
                    iteration++;
                }

                var offset = ((py * size) + px) * 3;
                if (iteration >= maxIter)
                {
                    pixels[offset] = 0;
                    pixels[offset + 1] = 0;
                    pixels[offset + 2] = 0;
                }
                else
                {
                    var (r, g, b) = Palette((double)iteration / maxIter);
                    pixels[offset] = r;
                    pixels[offset + 1] = g;
                    pixels[offset + 2] = b;
                }
            }
        }

        return pixels;
    }

    /// <summary>Packs <see cref="RenderColor"/> as the native visual contract <c>"{size}x{size}x3:{base64}"</c>.</summary>
    public static string RenderColorBase64(double centerX, double centerY, double zoom, int maxIter, int size) =>
        $"{size}x{size}x3:{Convert.ToBase64String(RenderColor(centerX, centerY, zoom, maxIter, size))}";

    // A smooth polynomial color ramp (Bernstein-style): bright bands near the boundary, dark toward escape.
    private static (byte R, byte G, byte B) Palette(double t)
    {
        var r = (byte)(9 * (1 - t) * t * t * t * 255);
        var g = (byte)(15 * (1 - t) * (1 - t) * t * t * 255);
        var b = (byte)(8.5 * (1 - t) * (1 - t) * (1 - t) * t * 255);
        return (r, g, b);
    }
```

- [ ] **Step 4: Run probe to verify it passes**

Run: `dotnet run --project "$env:TEMP\dni-probe"`
Expected: `PASS: fractal RGB bytes = 12288`

- [ ] **Step 5: Commit**

```powershell
git add core/DotnetNativeInterop.Engine/FractalRenderer.cs
git commit -m "feat: add colorized parametric Mandelbrot render (RGB)"
```

---

### Task 3: SDF raymarcher (engine)

**Files:**
- Create: `core/DotnetNativeInterop.Engine/Showcase/Raymarcher.cs`
- Probe: `%TEMP%\dni-probe\Program.cs`

- [ ] **Step 1: Write the failing probe**

Overwrite `%TEMP%\dni-probe\Program.cs`:
```csharp
using DotnetNativeInterop.Engine;
var s = Raymarcher.RenderBase64(0.0, 48, 48);
if (!s.StartsWith("48x48x3:")) throw new Exception($"FAIL: header {s[..Math.Min(12, s.Length)]}");
var bytes = Convert.FromBase64String(s["48x48x3:".Length..]);
if (bytes.Length != 48 * 48 * 3) throw new Exception($"FAIL: length {bytes.Length}");
var allZero = true;
foreach (var b in bytes) if (b != 0) { allZero = false; break; }
if (allZero) throw new Exception("FAIL: image is entirely black");
Console.WriteLine("PASS: raymarch RGB bytes = " + bytes.Length);
```

- [ ] **Step 2: Run probe to verify it fails**

Run: `dotnet run --project "$env:TEMP\dni-probe"`
Expected: FAIL — `Raymarcher` does not exist (compile error).

- [ ] **Step 3: Write the implementation**

Create `core/DotnetNativeInterop.Engine/Showcase/Raymarcher.cs`:
```csharp
namespace DotnetNativeInterop.Engine;

/// <summary>
/// A tiny signed-distance-field raymarcher rendered entirely in managed code: a sphere on a checkered
/// ground plane with Lambert shading and a soft shadow, lit by one directional light. Proves NativeAOT
/// .NET can do real-time 3D on the CPU with no GPU, shaders, or external libraries. Output is row-major
/// 8-bit RGB; the camera orbits the origin by <paramref name="cameraAngle"/> (radians).
/// </summary>
public static class Raymarcher
{
    private readonly record struct V(double X, double Y, double Z)
    {
        public static V operator +(V a, V b) => new(a.X + b.X, a.Y + b.Y, a.Z + b.Z);
        public static V operator -(V a, V b) => new(a.X - b.X, a.Y - b.Y, a.Z - b.Z);
        public static V operator *(V a, double s) => new(a.X * s, a.Y * s, a.Z * s);
        public double Dot(V b) => (X * b.X) + (Y * b.Y) + (Z * b.Z);
        public double Length() => Math.Sqrt(Dot(this));
        public V Normalized() { var l = Length(); return l > 0 ? this * (1.0 / l) : this; }
    }

    public static byte[] Render(double cameraAngle, int width, int height)
    {
        var pixels = new byte[width * height * 3];

        var camPos = new V(Math.Sin(cameraAngle) * 4.0, 1.6, Math.Cos(cameraAngle) * 4.0);
        var forward = (new V(0, 0, 0) - camPos).Normalized();
        var right = Cross(forward, new V(0, 1, 0)).Normalized();
        var up = Cross(right, forward);
        var light = new V(-0.6, 0.7, -0.4).Normalized();
        var aspect = (double)width / height;

        for (var py = 0; py < height; py++)
        {
            var v = 1.0 - (2.0 * py / (height - 1));
            for (var px = 0; px < width; px++)
            {
                var u = ((2.0 * px / (width - 1)) - 1.0) * aspect;
                var dir = (forward + (right * (u * 0.6)) + (up * (v * 0.6))).Normalized();
                var color = Trace(camPos, dir, light);
                var offset = ((py * width) + px) * 3;
                pixels[offset] = ToByte(color.X);
                pixels[offset + 1] = ToByte(color.Y);
                pixels[offset + 2] = ToByte(color.Z);
            }
        }

        return pixels;
    }

    public static string RenderBase64(double cameraAngle, int width, int height) =>
        $"{width}x{height}x3:{Convert.ToBase64String(Render(cameraAngle, width, height))}";

    private static V Cross(V a, V b) =>
        new((a.Y * b.Z) - (a.Z * b.Y), (a.Z * b.X) - (a.X * b.Z), (a.X * b.Y) - (a.Y * b.X));

    // Scene: unit sphere at origin + ground plane at y = -1.
    private static double Scene(V p) => Math.Min((p - new V(0, 0, 0)).Length() - 1.0, p.Y + 1.0);

    private static V Normal(V p)
    {
        const double e = 0.001;
        return new V(
            Scene(p + new V(e, 0, 0)) - Scene(p - new V(e, 0, 0)),
            Scene(p + new V(0, e, 0)) - Scene(p - new V(0, e, 0)),
            Scene(p + new V(0, 0, e)) - Scene(p - new V(0, 0, e))).Normalized();
    }

    private static V Trace(V origin, V dir, V light)
    {
        var t = 0.0;
        for (var i = 0; i < 96; i++)
        {
            var p = origin + (dir * t);
            var d = Scene(p);
            if (d < 0.001)
            {
                var n = Normal(p);
                var diffuse = Math.Max(0.0, n.Dot(light));
                var shadow = SoftShadow(p + (n * 0.01), light);
                var lit = Math.Min(1.0, 0.15 + (diffuse * shadow));
                var baseColor = p.Y <= -0.999 ? Checker(p) : new V(0.9, 0.4, 0.3);
                return baseColor * lit;
            }

            t += d;
            if (t > 20.0)
            {
                break;
            }
        }

        var sky = 0.5 + (0.5 * dir.Y);
        return new V(0.05 + (0.10 * sky), 0.07 + (0.18 * sky), 0.15 + (0.35 * sky));
    }

    private static double SoftShadow(V origin, V light)
    {
        var res = 1.0;
        var t = 0.02;
        for (var i = 0; i < 32; i++)
        {
            var h = Scene(origin + (light * t));
            if (h < 0.001)
            {
                return 0.0;
            }

            res = Math.Min(res, 8.0 * h / t);
            t += h;
            if (t > 10.0)
            {
                break;
            }
        }

        return Math.Clamp(res, 0.0, 1.0);
    }

    private static V Checker(V p) =>
        (((int)Math.Floor(p.X) + (int)Math.Floor(p.Z)) & 1) == 0
            ? new V(0.85, 0.85, 0.85)
            : new V(0.3, 0.3, 0.35);

    private static byte ToByte(double value) => (byte)(Math.Clamp(value, 0.0, 1.0) * 255.0);
}
```

- [ ] **Step 4: Run probe to verify it passes**

Run: `dotnet run --project "$env:TEMP\dni-probe"`
Expected: `PASS: raymarch RGB bytes = 6912`

- [ ] **Step 5: Commit**

```powershell
git add core/DotnetNativeInterop.Engine/Showcase/Raymarcher.cs
git commit -m "feat: add CPU SDF raymarcher (sphere on checker plane)"
```

---

### Task 4: SIMD matmul benchmark (engine)

**Files:**
- Create: `core/DotnetNativeInterop.Engine/Showcase/Benchmarks.cs`
- Probe: `%TEMP%\dni-probe\Program.cs`

- [ ] **Step 1: Write the failing probe**

Overwrite `%TEMP%\dni-probe\Program.cs`:
```csharp
using DotnetNativeInterop.Engine;
var payload = Benchmarks.MatmulGflops(128);
if (payload.Series.Length != 2) throw new Exception($"FAIL: series {payload.Series.Length}");
if (payload.Series[0].Points.Length == 0) throw new Exception("FAIL: no points");
if (payload.Summary.Length == 0) throw new Exception("FAIL: no summary");
Console.WriteLine($"PASS: matmul series={payload.Series.Length} points={payload.Series[1].Points.Length} peak={payload.Summary[0].Value}");
```

- [ ] **Step 2: Run probe to verify it fails**

Run: `dotnet run --project "$env:TEMP\dni-probe"`
Expected: FAIL — `Benchmarks` does not exist (compile error).

- [ ] **Step 3: Write the implementation**

Create `core/DotnetNativeInterop.Engine/Showcase/Benchmarks.cs`:
```csharp
using System.Diagnostics;
using System.Globalization;
using System.Numerics;

namespace DotnetNativeInterop.Engine;

/// <summary>
/// Heavy-compute throughput showpieces executed in-AOT and returned as <see cref="BenchmarkPayload"/>
/// for the native UI to chart: a SIMD-vs-scalar matrix multiply (GFLOPS), and Mandelbrot strong-scaling
/// across CPU cores. AOT-safe: pure arithmetic, no reflection.
/// </summary>
public static class Benchmarks
{
    /// <summary>Matrix multiply throughput (GFLOPS) — naive scalar vs <see cref="Vector{T}"/> — swept over sizes.</summary>
    public static BenchmarkPayload MatmulGflops(int maxSize)
    {
        int[] candidates = [64, 128, 192, 256, 384, 512];
        var scalar = new List<BenchmarkPoint>();
        var simd = new List<BenchmarkPoint>();
        double bestScalar = 0, bestSimd = 0;
        var lastSize = 0;

        foreach (var n in candidates)
        {
            if (n > maxSize)
            {
                break;
            }

            lastSize = n;
            var a = RandomMatrix(n);
            var b = RandomMatrix(n);
            var c = new float[n * n];
            var flop = 2.0 * n * n * n;

            var scalarMs = TimeMs(() => MatmulScalar(a, b, c, n));
            var scalarGflops = flop / (scalarMs / 1000.0) / 1e9;

            var simdMs = TimeMs(() => MatmulSimd(a, b, c, n));
            var simdGflops = flop / (simdMs / 1000.0) / 1e9;

            scalar.Add(new BenchmarkPoint(n, Round(scalarGflops)));
            simd.Add(new BenchmarkPoint(n, Round(simdGflops)));
            bestScalar = Math.Max(bestScalar, scalarGflops);
            bestSimd = Math.Max(bestSimd, simdGflops);
        }

        var speedup = bestScalar > 0 ? bestSimd / bestScalar : 0;
        return new BenchmarkPayload(
            "benchmark", "SIMD matrix multiply (GFLOPS)",
            [new BenchmarkSeries("scalar", scalar.ToArray()), new BenchmarkSeries("SIMD", simd.ToArray())],
            [
                new SummaryStat("peak GFLOPS", bestSimd.ToString("F1", CultureInfo.InvariantCulture)),
                new SummaryStat("speedup", speedup.ToString("F1", CultureInfo.InvariantCulture) + "×"),
                new SummaryStat("vector width", $"{Vector<float>.Count} floats"),
            ]);
    }

    /// <summary>Mandelbrot strong-scaling: render time + speedup as threads go 1 → ProcessorCount.</summary>
    public static BenchmarkPayload ParallelScaling(int size)
    {
        var cores = Environment.ProcessorCount;
        var times = new List<BenchmarkPoint>();
        var speedups = new List<BenchmarkPoint>();
        double baseline = 0;

        foreach (var threads in ThreadCounts(cores))
        {
            var ms = TimeMs(() => RenderMandelbrotParallel(size, threads));
            if (threads == 1)
            {
                baseline = ms;
            }

            times.Add(new BenchmarkPoint(threads, Round(ms)));
            speedups.Add(new BenchmarkPoint(threads, baseline > 0 ? Round(baseline / ms) : 0));
        }

        var best = speedups.Count > 0 ? speedups[^1].Y : 0;
        return new BenchmarkPayload(
            "benchmark", "Parallel Mandelbrot scaling",
            [new BenchmarkSeries("ms", times.ToArray()), new BenchmarkSeries("speedup", speedups.ToArray())],
            [
                new SummaryStat("cores", cores.ToString(CultureInfo.InvariantCulture)),
                new SummaryStat("max speedup", best.ToString("F1", CultureInfo.InvariantCulture) + "×"),
            ]);
    }

    private static void MatmulScalar(float[] a, float[] b, float[] c, int n)
    {
        Array.Clear(c);
        for (var i = 0; i < n; i++)
        {
            for (var k = 0; k < n; k++)
            {
                var aik = a[(i * n) + k];
                for (var j = 0; j < n; j++)
                {
                    c[(i * n) + j] += aik * b[(k * n) + j];
                }
            }
        }
    }

    private static void MatmulSimd(float[] a, float[] b, float[] c, int n)
    {
        Array.Clear(c);
        var width = Vector<float>.Count;
        for (var i = 0; i < n; i++)
        {
            for (var k = 0; k < n; k++)
            {
                var aik = a[(i * n) + k];
                var av = new Vector<float>(aik);
                var j = 0;
                for (; j <= n - width; j += width)
                {
                    var bv = new Vector<float>(b, (k * n) + j);
                    var cv = new Vector<float>(c, (i * n) + j);
                    (cv + (av * bv)).CopyTo(c, (i * n) + j);
                }

                for (; j < n; j++)
                {
                    c[(i * n) + j] += aik * b[(k * n) + j];
                }
            }
        }
    }

    private static long RenderMandelbrotParallel(int size, int threads)
    {
        var rows = new long[size];
        var options = new ParallelOptions { MaxDegreeOfParallelism = threads };
        Parallel.For(0, size, options, py =>
        {
            long acc = 0;
            var y0 = -1.5 + (3.0 * py / (size - 1));
            for (var px = 0; px < size; px++)
            {
                var x0 = -2.0 + (3.0 * px / (size - 1));
                double x = 0, y = 0;
                var iteration = 0;
                while ((x * x) + (y * y) <= 4.0 && iteration < 256)
                {
                    var xTemp = (x * x) - (y * y) + x0;
                    y = (2 * x * y) + y0;
                    x = xTemp;
                    iteration++;
                }

                acc += iteration;
            }

            rows[py] = acc;
        });

        long total = 0;
        foreach (var r in rows)
        {
            total += r;
        }

        return total;
    }

    private static int[] ThreadCounts(int cores)
    {
        var list = new List<int>();
        for (var t = 1; t < cores; t *= 2)
        {
            list.Add(t);
        }

        if (list.Count == 0 || list[^1] != cores)
        {
            list.Add(cores);
        }

        return list.ToArray();
    }

    private static double TimeMs(Action action)
    {
        action(); // warm up (JIT/branch predictors; AOT has no JIT but caches still warm)
        var stopwatch = Stopwatch.StartNew();
        action();
        stopwatch.Stop();
        return stopwatch.Elapsed.TotalMilliseconds;
    }

    private static float[] RandomMatrix(int n)
    {
        var rng = new Random(12345);
        var m = new float[n * n];
        for (var i = 0; i < m.Length; i++)
        {
            m[i] = (float)(rng.NextDouble() - 0.5);
        }

        return m;
    }

    private static double Round(double value) => Math.Round(value, 3);
}
```

- [ ] **Step 4: Run probe to verify it passes**

Run: `dotnet run --project "$env:TEMP\dni-probe"`
Expected: `PASS: matmul series=2 points=2 peak=…` (a plausible GFLOPS number).

- [ ] **Step 5: Commit**

```powershell
git add core/DotnetNativeInterop.Engine/Showcase/Benchmarks.cs
git commit -m "feat: add SIMD matmul + parallel-scaling benchmarks"
```

---

### Task 5: Verify the parallel-scaling benchmark (engine)

This is the same `Benchmarks.cs` from Task 4 (both methods shipped together). This task only adds a probe assertion for `ParallelScaling` to lock its shape before the dispatcher depends on it. No new source file.

**Files:** Probe: `%TEMP%\dni-probe\Program.cs`

- [ ] **Step 1: Write the probe**

Overwrite `%TEMP%\dni-probe\Program.cs`:
```csharp
using DotnetNativeInterop.Engine;
var payload = Benchmarks.ParallelScaling(160);
if (payload.Series.Length != 2) throw new Exception($"FAIL: series {payload.Series.Length}");
if (payload.Series[0].Points.Length == 0) throw new Exception("FAIL: no thread points");
if (payload.Series[1].Points[0].Y != 1.0) throw new Exception($"FAIL: 1-thread speedup should be 1.0, got {payload.Series[1].Points[0].Y}");
Console.WriteLine($"PASS: parallel points={payload.Series[0].Points.Length} cores={payload.Summary[0].Value} maxSpeedup={payload.Summary[1].Value}");
```

- [ ] **Step 2: Run probe**

Run: `dotnet run --project "$env:TEMP\dni-probe"`
Expected: `PASS: parallel points=… cores=… maxSpeedup=…×` (1-thread speedup is exactly 1.0). No commit (assertion only; code already committed in Task 4).

---

### Task 6: Command dispatcher + wire into `Run` (engine)

**Files:**
- Create: `core/DotnetNativeInterop.Engine/Showcase/ShowcaseCommand.cs`
- Modify: `core/DotnetNativeInterop.Engine/LanguageFeatures.cs` (one branch at top of `Run`)
- Probe: `%TEMP%\dni-probe\Program.cs`

- [ ] **Step 1: Write the failing probe**

Overwrite `%TEMP%\dni-probe\Program.cs`:
```csharp
using DotnetNativeInterop.Engine;

var frac = LanguageFeatureCatalog.Run("viz-mandelbrot~cx_-0.5~cy_0~zoom_1~iters_80~w_48~h_48");
if (!frac.Ok || !frac.Result.StartsWith("48x48x3:")) throw new Exception($"FAIL: fractal {frac.Ok} {frac.Result[..Math.Min(12, frac.Result.Length)]}");

var ray = LanguageFeatureCatalog.Run("viz-raymarch~angle_0~w_48~h_48");
if (!ray.Ok || !ray.Result.StartsWith("48x48x3:")) throw new Exception($"FAIL: raymarch {ray.Ok}");

var bench = LanguageFeatureCatalog.Run("bench-matmul~max_128");
if (!bench.Ok || !bench.Result.Contains("\"kind\":\"benchmark\"")) throw new Exception($"FAIL: bench {bench.Ok}");

var par = LanguageFeatureCatalog.Run("bench-parallel~size_120");
if (!par.Ok || !par.Result.Contains("\"kind\":\"benchmark\"")) throw new Exception($"FAIL: parallel {par.Ok}");

var unknown = LanguageFeatureCatalog.Run("viz-nope~w_8");
if (unknown.Ok) throw new Exception("FAIL: unknown command should not be ok");

var legacy = LanguageFeatureCatalog.Run("ping");
if (legacy.Result != "pong") throw new Exception($"FAIL: legacy ping broke: {legacy.Result}");

Console.WriteLine("PASS: all transports route commands; legacy catalog intact");
```

- [ ] **Step 2: Run probe to verify it fails**

Run: `dotnet run --project "$env:TEMP\dni-probe"`
Expected: FAIL — `viz-mandelbrot~…` is currently treated as an unknown feature id (`frac.Ok == false`).

- [ ] **Step 3: Write the dispatcher**

Create `core/DotnetNativeInterop.Engine/Showcase/ShowcaseCommand.cs`:
```csharp
using System.Diagnostics;
using System.Globalization;

namespace DotnetNativeInterop.Engine;

/// <summary>
/// Parses and runs parametric "showcase command" ids that the Lab UI drives over the existing
/// <see cref="LanguageFeatureCatalog.Run(string)"/> path — e.g.
/// <c>viz-mandelbrot~cx_-0.5~cy_0~zoom_8~iters_500~w_256~h_256</c> or <c>bench-matmul~max_512</c>.
/// The grammar uses only RFC-3986 unreserved characters so it needs no encoding on any transport.
/// Visual commands return <c>"WxHxC:base64"</c>; benchmark commands return <see cref="BenchmarkPayload"/> JSON.
/// </summary>
public static class ShowcaseCommand
{
    /// <summary>True for ids that use the parametric command grammar (contain a <c>~</c> field separator).</summary>
    public static bool IsCommand(string id) => id.Contains('~');

    /// <summary>Executes a command id, timing it; <c>Ok</c> means it produced a valid result.</summary>
    public static FeatureRun Run(string id)
    {
        var stopwatch = Stopwatch.StartNew();
        try
        {
            var parts = id.Split('~');
            var name = parts[0];
            var p = ParseParams(parts);

            var result = name switch
            {
                "viz-mandelbrot" => FractalRenderer.RenderColorBase64(
                    GetD(p, "cx", -0.5), GetD(p, "cy", 0.0), GetD(p, "zoom", 1.0),
                    GetI(p, "iters", 200, 16, 2000), GetI(p, "w", 256, 32, 512)),
                "viz-raymarch" => Raymarcher.RenderBase64(
                    GetD(p, "angle", 0.0), GetI(p, "w", 220, 32, 512), GetI(p, "h", 220, 32, 512)),
                "bench-matmul" => ShowcaseJson.Serialize(Benchmarks.MatmulGflops(GetI(p, "max", 384, 64, 512))),
                "bench-parallel" => ShowcaseJson.Serialize(Benchmarks.ParallelScaling(GetI(p, "size", 480, 64, 1024))),
                _ => $"Unknown showcase command: {name}",
            };

            stopwatch.Stop();
            var ok = !result.StartsWith("Unknown showcase command", StringComparison.Ordinal);
            return new FeatureRun(id, result, stopwatch.Elapsed.TotalMilliseconds, ok);
        }
        catch (Exception ex)
        {
            stopwatch.Stop();
            return new FeatureRun(id, ex.Message, stopwatch.Elapsed.TotalMilliseconds, false);
        }
    }

    private static Dictionary<string, string> ParseParams(string[] parts)
    {
        var dict = new Dictionary<string, string>(StringComparer.Ordinal);
        for (var i = 1; i < parts.Length; i++)
        {
            var underscore = parts[i].IndexOf('_');
            if (underscore > 0)
            {
                dict[parts[i][..underscore]] = parts[i][(underscore + 1)..];
            }
        }

        return dict;
    }

    private static double GetD(Dictionary<string, string> p, string key, double fallback) =>
        p.TryGetValue(key, out var s)
        && double.TryParse(s, NumberStyles.Float, CultureInfo.InvariantCulture, out var v)
            ? v : fallback;

    private static int GetI(Dictionary<string, string> p, string key, int fallback, int lo, int hi) =>
        Math.Clamp(
            p.TryGetValue(key, out var s)
            && int.TryParse(s, NumberStyles.Integer, CultureInfo.InvariantCulture, out var v)
                ? v : fallback,
            lo, hi);
}
```

- [ ] **Step 4: Wire the branch into `LanguageFeatureCatalog.Run`**

In `core/DotnetNativeInterop.Engine/LanguageFeatures.cs`, add the first two lines of the existing `Run` method body (immediately after `public static FeatureRun Run(string id)\n    {`):
```csharp
        if (ShowcaseCommand.IsCommand(id))
        {
            return ShowcaseCommand.Run(id);
        }

```
(Leave the rest of `Run` — the catalog lookup — exactly as it is.)

- [ ] **Step 5: Run probe to verify it passes**

Run: `dotnet run --project "$env:TEMP\dni-probe"`
Expected: `PASS: all transports route commands; legacy catalog intact`

- [ ] **Step 6: Full managed build (release, AOT-relevant warnings as errors check)**

Run: `dotnet build DotnetNativeInterop.slnx -c Release`
Expected: `Build succeeded. 0 Warning(s) 0 Error(s)`.

- [ ] **Step 7: Commit**

```powershell
git add core/DotnetNativeInterop.Engine/Showcase/ShowcaseCommand.cs core/DotnetNativeInterop.Engine/LanguageFeatures.cs
git commit -m "feat: route parametric Lab commands through Run(id) (all transports)"
```

- [ ] **Step 8: Tear down the probe (not committed)**

Run: `Remove-Item -Recurse -Force "$env:TEMP\dni-probe"`

---

### Task 7: iOS benchmark models (swift)

> Swift tasks can't be unit-tested from Windows — acceptance is the on-device build in Task 14. Each still commits.

**Files:**
- Create: `ios/Shared/Lab/ShowcaseModels.swift`

- [ ] **Step 1: Write the implementation**

Create `ios/Shared/Lab/ShowcaseModels.swift`:
```swift
import Foundation

// Codable mirrors of the engine's BenchmarkPayload JSON (camelCase keys), decoded with JSONDecode.

struct BenchmarkPoint: Codable, Sendable {
    let x: Double
    let y: Double
}

struct BenchmarkSeries: Codable, Sendable, Identifiable {
    let name: String
    let points: [BenchmarkPoint]
    var id: String { name }
}

struct SummaryStat: Codable, Sendable, Identifiable {
    let label: String
    let value: String
    var id: String { label }
}

struct BenchmarkPayload: Codable, Sendable {
    let kind: String
    let title: String
    let series: [BenchmarkSeries]
    let summary: [SummaryStat]
}
```

- [ ] **Step 2: Commit**

```powershell
git add ios/Shared/Lab/ShowcaseModels.swift
git commit -m "feat: add iOS Codable models for benchmark payloads"
```

---

### Task 8: RGB decode in VisualFeature (swift)

**Files:**
- Modify: `ios/Shared/VisualFeature.swift` (replace the `image(from:)` body; keep `isVisual` and `FractalImageView`)

- [ ] **Step 1: Write the implementation**

In `ios/Shared/VisualFeature.swift`, replace the `image(from:)` method with this version (decodes both `"WxH:base64"` grayscale and `"WxHxC:base64"` RGB):
```swift
    /// Parses `"WxH:<base64>"` (8-bit grayscale) or `"WxHxC:<base64>"` (C = 1 gray or 3 RGB) into a
    /// SwiftUI `Image`, or nil if malformed.
    static func image(from payload: String) -> Image? {
        guard let colon = payload.firstIndex(of: ":") else { return nil }
        let dimensions = payload[..<colon].split(separator: "x")
        guard dimensions.count == 2 || dimensions.count == 3,
              let width = Int(dimensions[0]), let height = Int(dimensions[1]) else { return nil }
        let channels = dimensions.count == 3 ? (Int(dimensions[2]) ?? 1) : 1
        guard channels == 1 || channels == 3,
              let data = Data(base64Encoded: String(payload[payload.index(after: colon)...])),
              data.count == width * height * channels,
              let provider = CGDataProvider(data: data as CFData) else { return nil }

        let colorSpace = channels == 1 ? CGColorSpaceCreateDeviceGray() : CGColorSpaceCreateDeviceRGB()
        guard let cgImage = CGImage(
            width: width, height: height,
            bitsPerComponent: 8, bitsPerPixel: 8 * channels, bytesPerRow: width * channels,
            space: colorSpace,
            bitmapInfo: CGBitmapInfo(rawValue: 0),
            provider: provider, decode: nil,
            shouldInterpolate: false, intent: .defaultIntent)
        else { return nil }

        return Image(decorative: cgImage, scale: 1)
    }
```

- [ ] **Step 2: Commit**

```powershell
git add ios/Shared/VisualFeature.swift
git commit -m "feat: decode RGB (WxHxC) visual payloads, not just grayscale"
```

---

### Task 9: Lab view model + render loop + canvas (swift)

**Files:**
- Create: `ios/Shared/Lab/LabViewModel.swift`
- Create: `ios/Shared/Lab/RasterDemo.swift`

- [ ] **Step 1: Write `LabViewModel`**

Create `ios/Shared/Lab/LabViewModel.swift`:
```swift
import Foundation

/// Backs the Lab tab: holds every transport's service and the selected transport, and runs a single
/// parametric showcase command (visual or benchmark) over the selected transport. The command is just
/// an id, so it rides the existing `FeatureService.run` path.
@MainActor
final class LabViewModel: ObservableObject {
    @Published var transport: TransportKind = .ffi

    private let services: [TransportKind: FeatureService]

    init(services: [TransportKind: FeatureService]) {
        self.services = services
    }

    /// Runs one command over the currently selected transport; nil on error (surfaced as a fallback view).
    func render(_ command: String) async -> FeatureResult? {
        try? await services[transport]?.run(command)
    }
}
```

- [ ] **Step 2: Write the shared render loop + presentational views**

Create `ios/Shared/Lab/RasterDemo.swift`:
```swift
import SwiftUI

/// A visual demo whose state lives in an ObservableObject (so a long-lived async loop reads it live,
/// avoiding SwiftUI @State capture staleness). `renderLoop` renders frames as fast as the transport
/// allows; when `animating`, it advances the demo's parameters each frame.
@MainActor
protocol RasterDemo: ObservableObject {
    var payload: String? { get set }
    var fps: Double { get set }
    var frameMs: Double { get set }
    var dims: String { get set }
    var animating: Bool { get }
    func currentCommand() -> String
    func advance()
    var render: (String) async -> FeatureResult? { get }
}

extension RasterDemo {
    /// Continuously renders the current command. Re-renders immediately while animating; otherwise only
    /// when the command changes (gesture/slider), idling 50 ms between checks to avoid a busy loop.
    func renderLoop() async {
        var last = ""
        while !Task.isCancelled {
            let command = currentCommand()
            if animating || command != last {
                let start = DispatchTime.now().uptimeNanoseconds
                if let result = await render(command) {
                    payload = result.result
                    if let colon = result.result.firstIndex(of: ":") {
                        dims = String(result.result[..<colon])
                    }
                    let ms = Double(DispatchTime.now().uptimeNanoseconds - start) / 1_000_000
                    frameMs = ms
                    fps = ms > 0 ? min(120, 1000 / ms) : 0
                    last = command
                }
                if animating { advance() }
            } else {
                try? await Task.sleep(nanoseconds: 50_000_000)
            }
        }
    }
}

/// Presentational frame + live readout (fps · ms/frame · dimensions · transport). No logic.
struct RasterCanvas: View {
    let payload: String?
    let fps: Double
    let frameMs: Double
    let dims: String
    let transport: String

    var body: some View {
        VStack(spacing: 10) {
            ZStack {
                if let payload, let image = VisualFeature.image(from: payload) {
                    image.interpolation(.none).resizable().scaledToFit()
                } else {
                    ContentUnavailableView("Rendering…", systemImage: "cpu")
                }
            }
            .frame(maxWidth: .infinity)
            .frame(height: 340)
            .background(.black)
            .clipShape(RoundedRectangle(cornerRadius: 12))

            HStack {
                Label(String(format: "%.1f fps", fps), systemImage: "speedometer")
                Spacer()
                Text(String(format: "%.1f ms/frame", frameMs)).foregroundStyle(.secondary)
                Spacer()
                Text(dims).foregroundStyle(.secondary)
                Spacer()
                Text(transport).foregroundStyle(.secondary)
            }
            .font(.caption.monospacedDigit())
        }
    }
}

/// Segmented transport picker bound to the Lab's selected transport.
struct LabTransportPicker: View {
    @Binding var transport: TransportKind

    var body: some View {
        Picker("Transport", selection: $transport) {
            ForEach(TransportKind.allCases) { kind in
                Text(kind.displayName).tag(kind)
            }
        }
        .pickerStyle(.segmented)
    }
}
```

- [ ] **Step 3: Commit**

```powershell
git add ios/Shared/Lab/LabViewModel.swift ios/Shared/Lab/RasterDemo.swift
git commit -m "feat: add Lab view model, shared render loop, and raster canvas"
```

---

### Task 10: Fractal Explorer (swift)

**Files:**
- Create: `ios/Shared/Lab/FractalExplorerView.swift`

- [ ] **Step 1: Write the implementation**

Create `ios/Shared/Lab/FractalExplorerView.swift`:
```swift
import SwiftUI

/// Interactive colorized Mandelbrot: pinch to zoom, drag to pan, slide iterations, toggle an auto-dive.
/// Every pixel is computed in C# inside the NativeAOT library; switching transport changes the frame rate.
@MainActor
final class FractalExplorerModel: ObservableObject, RasterDemo {
    @Published var payload: String?
    @Published var fps = 0.0
    @Published var frameMs = 0.0
    @Published var dims = "—"

    @Published var centerX = -0.5
    @Published var centerY = 0.0
    @Published var zoom = 1.0
    @Published var iterations = 220.0
    @Published var diving = false

    let size = 256
    private let lab: LabViewModel

    init(lab: LabViewModel) { self.lab = lab }

    var animating: Bool { diving }
    var render: (String) async -> FeatureResult? { { [lab] command in await lab.render(command) } }

    func currentCommand() -> String {
        "viz-mandelbrot~cx_\(fmt(centerX))~cy_\(fmt(centerY))~zoom_\(fmt(zoom))~iters_\(Int(iterations))~w_\(size)~h_\(size)"
    }

    func advance() { zoom *= 1.03 }

    func reset() {
        centerX = -0.5
        centerY = 0.0
        zoom = 1.0
        iterations = 220
    }

    private func fmt(_ value: Double) -> String { String(format: "%.6f", value) }
}

struct FractalExplorerView: View {
    @ObservedObject var lab: LabViewModel
    @StateObject private var model: FractalExplorerModel

    @State private var baseCenter: (x: Double, y: Double)?
    @State private var baseZoom: Double?

    init(lab: LabViewModel) {
        _lab = ObservedObject(wrappedValue: lab)
        _model = StateObject(wrappedValue: FractalExplorerModel(lab: lab))
    }

    var body: some View {
        List {
            Section {
                RasterCanvas(payload: model.payload, fps: model.fps, frameMs: model.frameMs,
                             dims: model.dims, transport: lab.transport.displayName)
                    .gesture(magnify.simultaneously(with: drag))
            }
            Section("Controls") {
                Toggle("Dive (auto-zoom)", isOn: $model.diving)
                HStack {
                    Text("Iterations")
                    Slider(value: $model.iterations, in: 32...1000, step: 1)
                    Text("\(Int(model.iterations))").monospacedDigit().frame(width: 44, alignment: .trailing)
                }
                Button("Reset view") { model.reset() }
                LabTransportPicker(transport: $lab.transport)
            }
            Section {
                Text("Every pixel of this Mandelbrot set is computed in C# inside the NativeAOT library "
                     + "and sent to SwiftUI as raw bytes — no GPU, no shader, no cloud. Switch transport "
                     + "to watch the frame rate change.")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Fractal Explorer")
        .task { await model.renderLoop() }
    }

    private var magnify: some Gesture {
        MagnificationGesture()
            .onChanged { scale in
                if baseZoom == nil { baseZoom = model.zoom }
                model.zoom = max(0.2, (baseZoom ?? model.zoom) * Double(scale))
            }
            .onEnded { _ in baseZoom = nil }
    }

    private var drag: some Gesture {
        DragGesture()
            .onChanged { value in
                if baseCenter == nil { baseCenter = (model.centerX, model.centerY) }
                let span = 3.0 / model.zoom
                let dx = Double(value.translation.width) / 340.0 * span
                let dy = Double(value.translation.height) / 340.0 * span
                model.centerX = (baseCenter?.x ?? model.centerX) - dx
                model.centerY = (baseCenter?.y ?? model.centerY) - dy
            }
            .onEnded { _ in baseCenter = nil }
    }
}
```

- [ ] **Step 2: Commit**

```powershell
git add ios/Shared/Lab/FractalExplorerView.swift
git commit -m "feat: add interactive Fractal Explorer Lab demo"
```

---

### Task 11: Raymarcher view (swift)

**Files:**
- Create: `ios/Shared/Lab/RaymarcherView.swift`

- [ ] **Step 1: Write the implementation**

Create `ios/Shared/Lab/RaymarcherView.swift`:
```swift
import SwiftUI

/// A ray-marched 3D scene (sphere on a checker plane) rendered entirely on the CPU in C#. Auto-rotates;
/// drag to orbit manually. The live FPS readout is the achievable frame rate of a managed software renderer.
@MainActor
final class RaymarcherModel: ObservableObject, RasterDemo {
    @Published var payload: String?
    @Published var fps = 0.0
    @Published var frameMs = 0.0
    @Published var dims = "—"

    @Published var angle = 0.0
    @Published var spinning = true

    let size = 220
    private let lab: LabViewModel

    init(lab: LabViewModel) { self.lab = lab }

    var animating: Bool { spinning }
    var render: (String) async -> FeatureResult? { { [lab] command in await lab.render(command) } }

    func currentCommand() -> String {
        "viz-raymarch~angle_\(String(format: "%.3f", angle))~w_\(size)~h_\(size)"
    }

    func advance() { angle += 0.03 }
}

struct RaymarcherView: View {
    @ObservedObject var lab: LabViewModel
    @StateObject private var model: RaymarcherModel

    @State private var baseAngle: Double?

    init(lab: LabViewModel) {
        _lab = ObservedObject(wrappedValue: lab)
        _model = StateObject(wrappedValue: RaymarcherModel(lab: lab))
    }

    var body: some View {
        List {
            Section {
                RasterCanvas(payload: model.payload, fps: model.fps, frameMs: model.frameMs,
                             dims: model.dims, transport: lab.transport.displayName)
                    .gesture(orbit)
            }
            Section("Controls") {
                Toggle("Auto-rotate", isOn: $model.spinning)
                LabTransportPicker(transport: $lab.transport)
            }
            Section {
                Text("A signed-distance-field raymarcher — sphere, ground plane, soft shadow — with every "
                     + "ray traced on the CPU in C#. No GPU, no Metal, no shaders.")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Raymarched 3D")
        .task { await model.renderLoop() }
    }

    private var orbit: some Gesture {
        DragGesture()
            .onChanged { value in
                if baseAngle == nil { baseAngle = model.angle }
                model.spinning = false
                model.angle = (baseAngle ?? model.angle) + (Double(value.translation.width) / 120.0)
            }
            .onEnded { _ in baseAngle = nil }
    }
}
```

- [ ] **Step 2: Commit**

```powershell
git add ios/Shared/Lab/RaymarcherView.swift
git commit -m "feat: add ray-marched 3D Lab demo"
```

---

### Task 12: Benchmark chart + detail (swift)

**Files:**
- Create: `ios/Shared/Lab/BenchmarkView.swift`

- [ ] **Step 1: Write the implementation**

Create `ios/Shared/Lab/BenchmarkView.swift`:
```swift
import Charts
import SwiftUI

/// Multi-series line+point chart of a benchmark payload (e.g. scalar vs SIMD GFLOPS across sizes).
struct BenchmarkChart: View {
    let series: [BenchmarkSeries]

    var body: some View {
        Chart {
            ForEach(series) { line in
                ForEach(Array(line.points.enumerated()), id: \.offset) { _, point in
                    LineMark(x: .value("x", point.x), y: .value("y", point.y))
                        .foregroundStyle(by: .value("series", line.name))
                    PointMark(x: .value("x", point.x), y: .value("y", point.y))
                        .foregroundStyle(by: .value("series", line.name))
                }
            }
        }
        .chartLegend(position: .bottom)
        .frame(height: 240)
    }
}

/// Runs a benchmark command over the selected transport, then charts the result + summary stats.
struct BenchmarkDetailView: View {
    @ObservedObject var lab: LabViewModel
    let title: String
    let command: String

    @State private var payload: BenchmarkPayload?
    @State private var running = false
    @State private var errorMessage: String?

    var body: some View {
        List {
            Section {
                Button {
                    Task { await run() }
                } label: {
                    if running {
                        HStack(spacing: 8) { ProgressView(); Text("Running…") }
                    } else {
                        Label("Run benchmark", systemImage: "bolt.fill")
                    }
                }
                .disabled(running)
                LabTransportPicker(transport: $lab.transport)
                Text("The benchmark executes inside the NativeAOT library and returns its series as JSON "
                     + "over the selected transport.")
                    .font(.caption).foregroundStyle(.secondary)
            }

            if let payload {
                Section(payload.title) { BenchmarkChart(series: payload.series) }
                Section("Summary") {
                    ForEach(payload.summary) { stat in
                        LabeledContent(stat.label, value: stat.value)
                    }
                }
            } else if !running {
                Section {
                    ContentUnavailableView("No run yet", systemImage: "chart.xyaxis.line",
                                           description: Text("Tap Run to execute the benchmark."))
                }
            }

            if let errorMessage {
                Section("Error") { Text(errorMessage).foregroundStyle(.red) }
            }
        }
        .navigationTitle(title)
    }

    private func run() async {
        running = true
        defer { running = false }
        guard let result = await lab.render(command) else {
            errorMessage = "The native library returned no data."
            return
        }
        do {
            payload = try JSONDecode.decode(BenchmarkPayload.self, from: result.result)
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
```

- [ ] **Step 2: Commit**

```powershell
git add ios/Shared/Lab/BenchmarkView.swift
git commit -m "feat: add benchmark chart + detail Lab screen"
```

---

### Task 13: Lab tab + app wiring (swift)

**Files:**
- Create: `ios/Shared/Lab/LabView.swift`
- Modify: `ios/Shared/RootTabView.swift`
- Modify: `ios/Apps/Unified/UnifiedApp.swift`

- [ ] **Step 1: Write `LabView`**

Create `ios/Shared/Lab/LabView.swift`:
```swift
import SwiftUI

/// The Lab tab: GPU-free visual compute + heavy-compute benchmarks, each its own screen.
struct LabView: View {
    @ObservedObject var lab: LabViewModel

    var body: some View {
        NavigationStack {
            List {
                Section("Visual — every pixel computed in C#") {
                    NavigationLink {
                        FractalExplorerView(lab: lab)
                    } label: {
                        Label("Fractal Explorer", systemImage: "circle.hexagongrid.fill")
                    }
                    NavigationLink {
                        RaymarcherView(lab: lab)
                    } label: {
                        Label("Raymarched 3D", systemImage: "cube.transparent")
                    }
                }
                Section("Benchmarks — NativeAOT throughput") {
                    NavigationLink {
                        BenchmarkDetailView(lab: lab, title: "SIMD Matmul", command: "bench-matmul~max_384")
                    } label: {
                        Label("SIMD Matrix Multiply", systemImage: "function")
                    }
                    NavigationLink {
                        BenchmarkDetailView(lab: lab, title: "Parallel Scaling", command: "bench-parallel~size_480")
                    } label: {
                        Label("Parallel Scaling", systemImage: "cpu.fill")
                    }
                }
            }
            .navigationTitle("Lab")
        }
    }
}
```

- [ ] **Step 2: Insert the Lab tab in `RootTabView`**

In `ios/Shared/RootTabView.swift`: add the parameter and the tab. Change the struct's stored properties to add `lab`, and insert the tab between Features and Compare:
```swift
struct RootTabView: View {
    @ObservedObject var features: FeaturesViewModel
    @ObservedObject var comparison: ComparisonViewModel
    @ObservedObject var lab: LabViewModel

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

            LatencyView(viewModel: features)
                .tabItem { Label("Latency", systemImage: "stopwatch") }

            AboutView(infos: features.orderedInfos)
                .tabItem { Label("About", systemImage: "info.circle") }
        }
    }
}
```

- [ ] **Step 3: Build the `LabViewModel` in the app entry**

In `ios/Apps/Unified/UnifiedApp.swift`: add a `lab` `@StateObject`, build it from the same `services` dict, and pass it to `RootTabView`:
```swift
@main
struct DotnetNativeInteropUnifiedApp: App {
    @StateObject private var features: FeaturesViewModel
    @StateObject private var comparison: ComparisonViewModel
    @StateObject private var lab: LabViewModel

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
    }

    var body: some Scene {
        WindowGroup {
            RootTabView(features: features, comparison: comparison, lab: lab)
        }
    }
}
```

- [ ] **Step 4: Commit**

```powershell
git add ios/Shared/Lab/LabView.swift ios/Shared/RootTabView.swift ios/Apps/Unified/UnifiedApp.swift
git commit -m "feat: add Lab tab and wire it into the unified app"
```

---

### Task 14: Native rebuild + on-device verification (Mac mini)

The engine changed, so the xcframework must be rebuilt (the C ABI did **not** change — `dni.h` is untouched — so no bridging-header edits). Build as `steve` over SSH per the build prefs; background the long publish; see `docs/ios-build-deploy-runbook.md`.

**Files:** none (build + manual verification).

- [ ] **Step 1: Rebuild the iOS framework on the Mac mini**

From the Mac (or over SSH as `steve`):
```bash
bash build/build-ios-framework.sh
```
Expected: `ios/Frameworks/dni.xcframework` rebuilt with the new engine (ABI unchanged).

- [ ] **Step 2: Regenerate the Xcode project (new Lab files auto-glob from `Shared/`)**

```bash
cd ios && xcodegen generate
```
Expected: project includes the new `Shared/Lab/*.swift` files without manual edits.

- [ ] **Step 3: Build, sign, and install the unified app to the iPad**

Use the device-only fast path from the runbook (sign + install `DotnetNativeInteropUnified`).
Expected: app installs and launches.

- [ ] **Step 4: Verify on device (the real test)**

- [ ] Lab tab appears (6 tabs: Dashboard · Features · Lab · Compare · Latency · About).
- [ ] **Fractal Explorer:** colorized fractal renders; pinch-zoom and drag-pan work; iteration slider changes detail; "Dive" auto-zooms; FPS/ms/dims readout updates live.
- [ ] **Transport toggle (fractal):** switching FFI → SQLite visibly drops the FPS (transport cost on full frames).
- [ ] **Raymarched 3D:** scene renders and auto-rotates; drag orbits; FPS readout updates.
- [ ] **SIMD Matmul:** Run produces a 2-series chart (scalar vs SIMD) with a peak-GFLOPS + speedup summary.
- [ ] **Parallel Scaling:** Run produces ms + speedup curves with a cores + max-speedup summary.
- [ ] **No regressions:** Dashboard, Features (incl. `viz-fractal`), Compare, Latency, About all still work.

- [ ] **Step 5: Commit any build-output/runbook changes (if the runbook gained steps)**

```powershell
git add docs/ios-build-deploy-runbook.md
git commit -m "docs: note Lab phase rebuild in the iOS runbook"
```
(Skip if nothing changed.)

---

## Self-Review

**Spec coverage:**
- Command-in-id grammar / no ABI change → Tasks 1, 6 (dispatcher + `Run` branch); verified all transports route in Task 6 probe. ✅
- Colorized parametric fractal → Task 2; interactive UI → Task 10. ✅
- Raymarcher → Task 3 (engine), Task 11 (UI). ✅
- SIMD matmul + parallel benchmarks → Tasks 4/5 (engine), Task 12 (charts), Task 13 (links). ✅
- `WxHxC` RGB payload + decode → Task 2/3 (engine), Task 8 (Swift). ✅
- Live FPS readout → Task 9 (`RasterCanvas` + `renderLoop`). ✅
- Transport toggle / FPS-collapse demo → Task 9 (`LabTransportPicker`), wired in Tasks 10–12; verified Task 14. ✅
- New Lab tab, additive only → Task 13; regression check Task 14 step 4. ✅
- Verification via throwaway probe + managed build + on-device → Tasks 0–6, 14. ✅

**Placeholder scan:** No TBD/TODO; every code step has complete code. ✅

**Type consistency:** Engine `BenchmarkPayload(Kind,Title,Series[],Summary[])` / `BenchmarkSeries(Name,Points[])` / `BenchmarkPoint(X,Y)` / `SummaryStat(Label,Value)` match the Swift camelCase Codables (`kind/title/series/summary/name/points/x/y/label/value`). Command names/params used in `ShowcaseCommand` (`viz-mandelbrot` cx/cy/zoom/iters/w/h; `viz-raymarch` angle/w/h; `bench-matmul` max; `bench-parallel` size) match the Swift `currentCommand()` strings and `LabView` links. `RasterDemo` members (`payload/fps/frameMs/dims/animating/currentCommand/advance/render`) match both conforming models. ✅

---

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-06-05-lab-visual-compute-benchmarks.md`. Two execution options:**

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints.

**Which approach?**
