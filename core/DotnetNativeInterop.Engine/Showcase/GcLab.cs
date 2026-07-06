using System.Diagnostics;
using System.Globalization;
using System.Runtime.InteropServices;
using System.Threading;

namespace DotnetNativeInterop.Engine;

/// <summary>
/// GC behavior lab: runs a bounded allocation storm — rapid short-lived Gen0 churn, large-object-heap
/// (LOH) churn, or long-lived GCHandle-pinned buffers that block heap compaction — and reports
/// collection counts, pause time, and heap/committed deltas plus a heap time-series so the native UI
/// can plot .NET's GC actually working. AOT-safe: no reflection; JSON via <see cref="ShowcaseJsonContext"/>.
/// </summary>
public static class GcLab
{
    private const int SampleIntervalMs = 100; // ~10 samples/sec.

    /// <summary>Runs the <paramref name="preset"/> storm for <paramref name="secs"/> seconds, sized by
    /// <paramref name="mb"/>. <paramref name="clamped"/> reports whether the caller's raw mb/secs values
    /// were clamped to the safety caps (mb ≤ 256, secs ≤ 30) before reaching this method.</summary>
    public static BenchmarkPayload Run(string preset, int mb, int secs, bool clamped)
    {
        var before = EngineTelemetry.Snapshot();
        var heapSamples = new List<BenchmarkPoint>();
        var committedSamples = new List<BenchmarkPoint>();

        Storm(preset, mb, TimeSpan.FromSeconds(secs), heapSamples, committedSamples);

        var after = EngineTelemetry.Snapshot();

        return new BenchmarkPayload(
            "benchmark", $"GC Lab ({preset}, {mb} MB, {secs}s)",
            [
                new BenchmarkSeries("heapMB", heapSamples.ToArray()),
                new BenchmarkSeries("committedMB", committedSamples.ToArray()),
            ],
            [
                new SummaryStat("preset", preset),
                new SummaryStat("mb (effective)", mb.ToString(CultureInfo.InvariantCulture)),
                new SummaryStat("secs (effective)", secs.ToString(CultureInfo.InvariantCulture)),
                new SummaryStat("clamped", clamped ? "yes (cap: mb≤256, secs≤30)" : "no"),
                new SummaryStat("gen0 collections", (after.GcGen0 - before.GcGen0).ToString(CultureInfo.InvariantCulture)),
                new SummaryStat("gen1 collections", (after.GcGen1 - before.GcGen1).ToString(CultureInfo.InvariantCulture)),
                new SummaryStat("gen2 collections", (after.GcGen2 - before.GcGen2).ToString(CultureInfo.InvariantCulture)),
                new SummaryStat(
                    "GC pause Δ", (after.GcPauseMs - before.GcPauseMs).ToString("F2", CultureInfo.InvariantCulture) + " ms"),
                new SummaryStat(
                    "heap before → after", $"{BytesToMb(before.HeapBytes):F1} → {BytesToMb(after.HeapBytes):F1} MB"),
                new SummaryStat(
                    "committed before → after",
                    $"{BytesToMb(before.CommittedBytes):F1} → {BytesToMb(after.CommittedBytes):F1} MB"),
                new SummaryStat("allocated Δ", $"{BytesToMb(after.AllocatedBytes - before.AllocatedBytes):F1} MB"),
            ]);
    }

    // Runs the allocation storm for the wall-clock duration, sampling heap/committed bytes ~10x/sec.
    // `mb` is a THROUGHPUT target (MB/sec), not just a working-set size: a naive "allocate as fast as the
    // CPU allows" loop was measured (in manual smoke testing) to pin/unpin tens of thousands of buffers
    // per second, ballooning committed memory by ~2GB in ONE second — unacceptable for code that runs
    // inside a real mobile app's process. Pacing allocations to a wall-clock rate keeps memory growth
    // predictable and keeps mb/secs the two knobs that actually bound worst-case footprint, per the
    // clamp caps above. Returns a checksum folded from every buffer touched, so the storm can't be
    // optimized away as dead code (mirrors Benchmarks.RenderMandelbrotParallel).
    private static long Storm(
        string preset, int mb, TimeSpan duration, List<BenchmarkPoint> heapSamples, List<BenchmarkPoint> committedSamples)
    {
        var chunkBytes = preset switch
        {
            "gen0" => 256,          // small, short-lived
            "loh" => 128 * 1024,    // > ~85,000-byte LOH threshold
            _ => 64 * 1024,         // "pinned" — stays UNDER the LOH threshold (see AllocPinned)
        };

        // Chunks/sec to hit ~mb MB/sec; for loh/pinned this ALSO becomes the rolling-pool capacity, so
        // the retained working set converges to ~mb MB rather than growing for the whole run.
        var chunksPerSecond = Math.Max(1L, (mb * 1024L * 1024L) / chunkBytes);

        var stopwatch = Stopwatch.StartNew();
        var nextSampleMs = 0.0;
        long checksum = 0;
        long chunksAllocated = 0;
        var gen0Recent = new Queue<byte[]>();
        var lohPool = new Queue<byte[]>();
        var pinnedHandles = new List<GCHandle>();

        try
        {
            while (stopwatch.Elapsed < duration)
            {
                var budget = (long)(stopwatch.Elapsed.TotalSeconds * chunksPerSecond);
                if (chunksAllocated < budget)
                {
                    // Catch up in a burst: one alloc per outer-loop pass can't keep pace with the
                    // highest presets' target rate (gen0 needs 100K+ chunks/sec) once the loop's own
                    // overhead is counted, which silently collapsed throughput to a fraction of the
                    // requested mb/sec. Bounded by wall-clock budget above, so this can't over-allocate.
                    while (chunksAllocated < budget)
                    {
                        checksum += preset switch
                        {
                            "gen0" => AllocGen0(gen0Recent),
                            "loh" => AllocLoh(chunksPerSecond, lohPool),
                            _ => AllocPinned(chunksPerSecond, pinnedHandles), // "pinned"
                        };
                        chunksAllocated++;
                    }
                }
                else
                {
                    Thread.Sleep(1); // at quota for this instant — yield instead of busy-spinning
                }

                var elapsedMs = stopwatch.Elapsed.TotalMilliseconds;
                if (elapsedMs >= nextSampleMs)
                {
                    if (preset is "loh" or "pinned")
                    {
                        // LOH/pinned garbage is only ever reclaimed by a Gen2 collection, and Gen2's
                        // budget is tuned for far bigger workloads — left alone, it would not collect
                        // naturally within this demo's few-second window, so evicted/unpinned buffers
                        // would never be freed and heap would grow by mb*secs instead of converging to
                        // ~mb MB. Forcing one here bounds memory AND surfaces the real signal: committed
                        // staying above heap after a full collection IS the pin fragmentation this preset
                        // exists to demonstrate.
                        GC.Collect(2, GCCollectionMode.Forced, blocking: true);
                    }

                    var info = GC.GetGCMemoryInfo();
                    // HeapSizeBytes is "as of the last GC" — 0 until the first collection happens in this
                    // process, which would flatline the low end of the chart at zero. Fall back to a live
                    // read, same as EngineTelemetry.Snapshot.
                    var heapBytes = info.HeapSizeBytes > 0 ? info.HeapSizeBytes : GC.GetTotalMemory(false);
                    var t = Math.Round(elapsedMs / 1000.0, 2);
                    heapSamples.Add(new BenchmarkPoint(t, BytesToMb(heapBytes)));
                    committedSamples.Add(new BenchmarkPoint(t, BytesToMb(info.TotalCommittedBytes)));
                    nextSampleMs += SampleIntervalMs;
                }
            }
        }
        finally
        {
            // Never leak a pin — a stray pinned handle would fragment/hold every subsequent GC in this
            // process. NOTE: even freed, the address space a pin blocked from compacting may stay
            // fragmented/committed for the rest of the process — running "pinned" once can leave the
            // process's baseline memory footprint elevated for later demos too. That's the real
            // phenomenon being demonstrated, not a leak in this code.
            foreach (var handle in pinnedHandles)
            {
                handle.Free();
            }
        }

        return checksum;
    }

    // Small, short-lived allocations — the classic "rapid Gen0 churn" pattern. Enqueuing (rather than
    // just discarding the reference) forces a GENUINE heap allocation: measured in manual testing, an
    // unstored small fixed-size array is exactly what RyuJIT's escape-analysis stack-allocation
    // optimization targets, and once tiered compilation re-JITs this method it silently converts the
    // "allocation" to a stack slot — collapsing throughput/GC pressure to near zero with no error. A
    // capacity of 4 keeps these genuinely short-lived (evicted within a few iterations) while still
    // being real, escaping heap objects gen0 GCs actually collect.
    private static int AllocGen0(Queue<byte[]> recent)
    {
        var buf = new byte[256];
        buf[0] = 1;
        recent.Enqueue(buf);
        if (recent.Count > 4)
        {
            recent.Dequeue();
        }

        return buf[0];
    }

    // 128 KB clears the ~85,000-byte LOH threshold; a capped rolling pool means old (mid-heap) entries
    // are freed as new ones arrive, which is what fragments the LOH over time.
    private static int AllocLoh(long capacity, Queue<byte[]> pool)
    {
        const int chunk = 128 * 1024;
        var buf = new byte[chunk];
        buf[0] = 1;
        pool.Enqueue(buf);
        while (pool.Count > capacity)
        {
            pool.Dequeue();
        }

        return buf[0];
    }

    // 64 KB stays UNDER the LOH threshold so this pins a normal-heap object — a GCHandle pin on the SOH
    // is what actually blocks Gen1/2 compaction and fragments the heap (a POH-backed pinned array would not).
    private static int AllocPinned(long capacity, List<GCHandle> pool)
    {
        const int chunk = 64 * 1024;
        var buf = new byte[chunk];
        buf[0] = 1;
        pool.Add(GCHandle.Alloc(buf, GCHandleType.Pinned));
        while (pool.Count > capacity)
        {
            pool[0].Free();
            pool.RemoveAt(0);
        }

        return buf[0];
    }

    private static double BytesToMb(long bytes) => Math.Round(bytes / (1024.0 * 1024.0), 2);
}
