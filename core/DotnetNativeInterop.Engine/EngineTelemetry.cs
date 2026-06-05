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
