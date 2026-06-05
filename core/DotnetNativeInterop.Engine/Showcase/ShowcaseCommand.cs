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
                "bench-echo" => Echo(GetI(p, "bytes", 1024, 1, 1_048_576)),
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

    // Returns an N-byte ASCII payload; the latency lab times the round-trip to measure transport
    // cost vs response size. N is clamped by the caller via GetI.
    private static string Echo(int bytes) => new string('x', bytes);

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
