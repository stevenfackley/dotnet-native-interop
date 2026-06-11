using System.Buffers;
using System.Collections.Frozen;
using System.Globalization;
using System.Runtime.Intrinsics;
using System.Runtime.Intrinsics.Arm;
using System.Runtime.Intrinsics.X86;
using System.Security.Cryptography;

namespace DotnetNativeInterop.Engine;

/// <summary>
/// The third showcase tier: modern BCL capabilities joining the runtime sections — SIMD-probed
/// SearchValues, read-optimized frozen collections, PriorityQueue, DateOnly/TimeOnly, work-stealing
/// Parallel.For, Vector128 hardware SIMD with ISA detection, and SHA-256/HMAC hashing. All
/// deterministic per process so the startup self-check holds.
/// </summary>
public static partial class LanguageFeatureCatalog
{
    private static readonly SearchValues<char> Vowels = SearchValues.Create("aeiou");

    private static readonly FrozenDictionary<int, string> HttpStatus = new Dictionary<int, string>
    {
        [200] = "OK",
        [404] = "Not Found",
        [418] = "I'm a teapot",
    }.ToFrozenDictionary();

    private static List<LanguageFeature> BclDemos() =>
    [
        new("sha256-hmac", "SHA-256 + HMAC one-shot hashing", "Cryptography",
            "var hash = SHA256.HashData(data);\nvar mac = HMACSHA256.HashData(key, data);   // static, allocation-light",
            static () =>
            {
                ReadOnlySpan<byte> data = "C# on metal"u8;
                ReadOnlySpan<byte> key = "demo-shared-secret"u8;
                var hash = SHA256.HashData(data);
                var mac = HMACSHA256.HashData(key, data);
                return $"SHA-256 = {Convert.ToHexString(hash)}\nHMAC    = {Convert.ToHexString(mac)}";
            }),

        new("searchvalues", "SearchValues SIMD-probed scan", "Text & Regex",
            "private static readonly SearchValues<char> Vowels = SearchValues.Create(\"aeiou\");\nvar hit = text.IndexOfAny(Vowels);   // vectorized membership probe",
            static () =>
            {
                ReadOnlySpan<char> text = "the engine compiles ahead of time";
                var count = 0;
                var remaining = text;
                int hit;
                while ((hit = remaining.IndexOfAny(Vowels)) >= 0)
                {
                    count++;
                    remaining = remaining[(hit + 1)..];
                }

                return $"first vowel at index {text.IndexOfAny(Vowels)}, total vowels = {count}\n(set is precompiled into a SIMD probe at startup)";
            }),

        new("parallel-for", "Parallel.For work-stealing sum", "Concurrency",
            "Parallel.For(1, 100_001, () => 0L,\n    (i, _, local) => local + (long)i * i,\n    local => Interlocked.Add(ref total, local));",
            static () =>
            {
                long total = 0;
                Parallel.For(1, 100_001,
                    static () => 0L,
                    static (i, _, local) => local + ((long)i * i),
                    local => Interlocked.Add(ref total, local));
                return $"sum of squares 1..100,000 = {total:N0}\npartitioned across {Environment.ProcessorCount} cores, merged once per worker";
            }),

        new("simd-vector128", "Vector128 hardware SIMD", "Vector Math · AI",
            "var sum = a + b;               // one NEON/SSE instruction\nvar dot = Vector128.Dot(a, b);",
            static () =>
            {
                var a = Vector128.Create(1f, 2f, 3f, 4f);
                var b = Vector128.Create(10f, 20f, 30f, 40f);
                var sum = a + b;
                var dot = Vector128.Dot(a, b);
                var isa = AdvSimd.IsSupported ? "ARM NEON" : Sse2.IsSupported ? "x86 SSE2" : "software fallback";
                return $"ISA = {isa}, accelerated = {Vector128.IsHardwareAccelerated}\na + b = {sum}\ndot(a, b) = {dot}";
            }),

        new("frozen-collections", "FrozenDictionary read-optimized lookup", "Collections",
            "private static readonly FrozenDictionary<int, string> HttpStatus =\n    map.ToFrozenDictionary();   // pays once at freeze, every read after is faster",
            static () => $"200 -> {HttpStatus[200]},  418 -> {HttpStatus[418]}\ncontains 500: {HttpStatus.ContainsKey(500)}"),

        new("priority-queue", "PriorityQueue scheduling", "Collections",
            "var queue = new PriorityQueue<string, int>();\nqueue.Enqueue(\"render frame\", priority: 1);",
            static () =>
            {
                var queue = new PriorityQueue<string, int>();
                queue.Enqueue("flush telemetry", 3);
                queue.Enqueue("render frame", 1);
                queue.Enqueue("poll sensors", 2);
                var order = new List<string>();
                while (queue.TryDequeue(out var job, out var priority))
                {
                    order.Add($"{priority}:{job}");
                }

                return string.Join("  ->  ", order);
            }),

        new("dateonly-timeonly", "DateOnly / TimeOnly arithmetic", "Date & Time",
            "var ga = release.AddDays(90);\nvar later = standup.AddHours(45, out var wrappedDays);",
            static () =>
            {
                var release = new DateOnly(2026, 6, 11);
                var ga = release.AddDays(90);
                var standup = new TimeOnly(9, 30);
                var later = standup.AddHours(45, out var wrappedDays);
                return $"{release.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture)} + 90 days = "
                     + $"{ga.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture)} ({ga.DayOfWeek})\n"
                     + $"09:30 + 45h = {later.ToString("HH:mm", CultureInfo.InvariantCulture)} (+{wrappedDays} days, wraps cleanly)";
            }),
    ];
}
