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

/// <summary>
/// Source-generated JSON metadata for <see cref="BenchmarkPayload"/> (AOT-safe, no reflection). Also
/// registers <see cref="FeatureDescriptor"/>/its list shape so <see cref="RealPayloadBenchmark"/> can
/// serialize the language-feature catalog without a reflection fallback.
/// </summary>
[JsonSourceGenerationOptions(PropertyNamingPolicy = JsonKnownNamingPolicy.CamelCase)]
[JsonSerializable(typeof(BenchmarkPayload))]
[JsonSerializable(typeof(IReadOnlyList<FeatureDescriptor>))]
[JsonSerializable(typeof(FeatureDescriptor))]
internal sealed partial class ShowcaseJsonContext : JsonSerializerContext;

/// <summary>Serializes a <see cref="BenchmarkPayload"/> to camelCase JSON via the source-gen context.</summary>
public static class ShowcaseJson
{
    public static string Serialize(BenchmarkPayload payload) =>
        JsonSerializer.Serialize(payload, ShowcaseJsonContext.Default.BenchmarkPayload);
}
