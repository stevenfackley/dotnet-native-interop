using System.Globalization;

namespace DotnetNativeInterop.Engine;

/// <summary>
/// Classic language syntax demos (C# 9 back to C# 1) completing the catalog's timeline: function
/// pointers, init-only setters, logical patterns, indices and ranges, async streams, null-coalescing
/// operators, default interface methods, tuples, local functions, out-var, ref returns, async/await,
/// LINQ query syntax, yield iterators, and operator overloading. All AOT-safe and deterministic.
/// </summary>
public static partial class LanguageFeatureCatalog
{
    private static List<LanguageFeature> ClassicSyntaxDemos() =>
    [
        new("function-pointers", "Function pointers (delegate*)", "C# 9",
            "delegate*<int, int, int> op = &AddOp;   // raw fnptr, no delegate allocation\nvar sum = op(6, 7);",
            static () => FunctionPointerDemo()),

        new("init-target-typed-new", "init setters + target-typed new", "C# 9",
            "ProbeConfig config = new() { Endpoint = \"native://engine\", SampleRate = 4 };",
            static () =>
            {
                ProbeConfig config = new() { Endpoint = "native://engine", SampleRate = 4 };
                return $"{config.Endpoint} sampling 1/{config.SampleRate}\ninit-only: frozen after the initializer, no mutable setters escape";
            }),

        new("logical-patterns", "Logical patterns (and / or / not)", "C# 9",
            "c switch {\n    (>= 'a' and <= 'z') or (>= 'A' and <= 'Z') => \"letter\", ...",
            static () => string.Join(",  ", "k9 #".Select(c => $"'{c}' -> {CharKind(c)}"))),

        new("indices-ranges", "Indices and ranges (^ and ..)", "C# 8",
            "data[^1]      // last element\ndata[1..^1]   // slice without ends",
            static () =>
            {
                int[] data = [11, 22, 33, 44, 55];
                return $"data[^1] = {data[^1]}\ndata[1..^1] = [{string.Join(", ", data[1..^1])}]\ndata[..2]   = [{string.Join(", ", data[..2])}]";
            }),

        new("async-streams", "Async streams (await foreach)", "C# 8",
            "await foreach (var frame in TelemetryFramesAsync()) { ... }",
            static () =>
            {
                var collected = new List<int>();
                Task.Run(async () =>
                {
                    await foreach (var frame in TelemetryFramesAsync().ConfigureAwait(false))
                    {
                        collected.Add(frame);
                    }
                }).GetAwaiter().GetResult();
                return $"await foreach pulled [{string.Join(", ", collected)}] as each frame arrived";
            }),

        new("null-coalescing", "?? and ??= operators", "C# 8",
            "cache ??= Compute();   // assigns only when null",
            static () =>
            {
                string? cache = null;
                cache ??= "computed once";
                cache ??= "computed twice?";
                int? reading = null;
                var safe = reading ?? -1;
                return $"cache after two ??=  ->  \"{cache}\"\nnull int? ?? -1      ->  {safe}";
            }),

        new("default-interface-methods", "Default interface methods", "C# 8",
            "interface ITransport {\n    string Name { get; }\n    string Describe() => $\"{Name} (default impl)\";\n}",
            static () =>
            {
                ITransportDemo ffi = new FfiTransportDemo();
                ITransportDemo http = new HttpTransportDemo();
                return $"{ffi.Describe()}\n{http.Describe()}";
            }),

        new("tuples-deconstruction", "Tuples + deconstruction", "C# 7",
            "var (min, max, mean) = Stats([4, 8, 15, 16, 23, 42]);",
            static () =>
            {
                var (min, max, mean) = Stats([4, 8, 15, 16, 23, 42]);
                return $"(min, max, mean) = ({min}, {max}, {mean.ToString("F1", CultureInfo.InvariantCulture)})";
            }),

        new("local-functions", "Local functions", "C# 7",
            "long Fib(int n) => n <= 1 ? n : memo[n] != 0 ? memo[n] : memo[n] = Fib(n - 1) + Fib(n - 2);",
            static () =>
            {
                var memo = new long[51];
                long Fib(int n) => n <= 1 ? n : memo[n] != 0 ? memo[n] : memo[n] = Fib(n - 1) + Fib(n - 2);
                return $"fib(50) = {Fib(50)} (recursive local function over captured memo)";
            }),

        new("out-var", "out variables + discards", "C# 7",
            "if (int.TryParse(text, out var n)) { ... }\nint.TryParse(noise, out _);",
            static () =>
            {
                var ok = int.TryParse("1024", NumberStyles.Integer, CultureInfo.InvariantCulture, out var n);
                var bad = int.TryParse("native", NumberStyles.Integer, CultureInfo.InvariantCulture, out _);
                return $"\"1024\"   -> {n} (ok = {ok})\n\"native\" -> discarded (ok = {bad})";
            }),

        new("ref-returns", "ref returns and ref locals", "C# 7",
            "ref var top = ref Largest(readings);\ntop = -1;   // writes through into the array",
            static () =>
            {
                int[] readings = [3, 9, 4];
                ref var top = ref Largest(readings);
                top = -1;
                return $"before: [3, 9, 4]\nref var top = ref Largest(...); top = -1\nafter:  [{string.Join(", ", readings)}]";
            }),

        new("async-await", "async / await + Task.WhenAll", "C# 5",
            "var squares = await Task.WhenAll(Work(1), Work(2), Work(3));",
            static () =>
            {
                static async Task<int> Work(int id)
                {
                    await Task.Delay(id * 5).ConfigureAwait(false);
                    return id * id;
                }

                var squares = Task.WhenAll(Work(1), Work(2), Work(3)).GetAwaiter().GetResult();
                return $"Task.WhenAll -> [{string.Join(", ", squares)}] (three awaits, one thread pool)";
            }),

        new("linq-query", "LINQ query syntax", "C# 3",
            "from word in words where word.Length > 3 orderby word select word.ToUpperInvariant()",
            static () =>
            {
                string[] words = ["span", "aot", "native", "ffi", "swift"];
                var shouted = from word in words
                              where word.Length > 3
                              orderby word
                              select word.ToUpperInvariant();
                return $"[{string.Join(", ", shouted)}]";
            }),

        new("yield-iterators", "yield return iterators", "C# 2",
            "static IEnumerable<int> Primes() { ... yield return candidate; ... }",
            static () => $"first 8 primes (computed lazily per MoveNext): [{string.Join(", ", Primes().Take(8))}]"),

        new("operator-overloading", "Operator overloading", "C# 1",
            "public static Vec2 operator +(Vec2 a, Vec2 b) => new(a.X + b.X, a.Y + b.Y);",
            static () =>
            {
                var a = new Vec2(3, 4);
                var b = new Vec2(1, 2);
                var scaled = a * 2;
                return $"{a} + {b} = {a + b}\n{a} * 2 = {scaled}\n|a| = {a.Magnitude()}";
            }),
    ];

    private static unsafe string FunctionPointerDemo()
    {
        delegate*<int, int, int> op = &AddOp;
        var added = op(6, 7);
        op = &MulOp;
        var multiplied = op(6, 7);
        return $"op = &AddOp -> op(6, 7) = {added}\nop = &MulOp -> op(6, 7) = {multiplied}";
    }

    private static int AddOp(int a, int b) => a + b;

    private static int MulOp(int a, int b) => a * b;

    private static string CharKind(char c) => c switch
    {
        (>= 'a' and <= 'z') or (>= 'A' and <= 'Z') => "letter",
        >= '0' and <= '9' => "digit",
        not ' ' => "symbol",
        _ => "space",
    };

    private static async IAsyncEnumerable<int> TelemetryFramesAsync()
    {
        for (var i = 1; i <= 5; i++)
        {
            await Task.Yield();
            yield return i * 10;
        }
    }

    private static (int Min, int Max, double Mean) Stats(ReadOnlySpan<int> values)
    {
        var min = int.MaxValue;
        var max = int.MinValue;
        var sum = 0;
        foreach (var value in values)
        {
            min = Math.Min(min, value);
            max = Math.Max(max, value);
            sum += value;
        }

        return (min, max, (double)sum / values.Length);
    }

    private static ref int Largest(int[] values)
    {
        ref var best = ref values[0];
        for (var i = 1; i < values.Length; i++)
        {
            if (values[i] > best)
            {
                best = ref values[i];
            }
        }

        return ref best;
    }

    private static IEnumerable<int> Primes()
    {
        for (var candidate = 2; ; candidate++)
        {
            var isPrime = true;
            for (var d = 2; d * d <= candidate; d++)
            {
                if (candidate % d == 0)
                {
                    isPrime = false;
                    break;
                }
            }

            if (isPrime)
            {
                yield return candidate;
            }
        }
    }

    private sealed class ProbeConfig
    {
        public string Endpoint { get; init; } = "";

        public int SampleRate { get; init; }
    }

    private interface ITransportDemo
    {
        string Name { get; }

        string Describe() => $"{Name} (default interface implementation)";
    }

    private sealed class FfiTransportDemo : ITransportDemo
    {
        public string Name => "FFI";
    }

    private sealed class HttpTransportDemo : ITransportDemo
    {
        public string Name => "HTTP";

        public string Describe() => "HTTP (class overrides the default)";
    }

    private readonly struct Vec2(double x, double y)
    {
        public double X { get; } = x;

        public double Y { get; } = y;

        public static Vec2 operator +(Vec2 a, Vec2 b) => new(a.X + b.X, a.Y + b.Y);

        public static Vec2 operator *(Vec2 v, double k) => new(v.X * k, v.Y * k);

        public double Magnitude() => Math.Sqrt((X * X) + (Y * Y));

        public override string ToString() => $"({X}, {Y})";
    }
}
