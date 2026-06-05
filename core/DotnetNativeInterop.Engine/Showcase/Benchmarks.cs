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
