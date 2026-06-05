using System.Diagnostics;
using System.Globalization;
using System.Numerics;

namespace OnDeviceLlm.Engine;

/// <summary>Catalog entry describing one language feature for the UI (no execution).</summary>
public sealed record FeatureDescriptor(string Id, string Title, string Version, string Code, string Expected);

/// <summary>The live result of executing one feature, with timing.</summary>
public sealed record FeatureRun(string Id, string Result, double ElapsedMs, bool Ok);

/// <summary>One executable language feature: metadata plus a delegate that runs it live.</summary>
public sealed record LanguageFeature(string Id, string Title, string Version, string Code, Func<string> Execute);

/// <summary>C# 14 extension block — adds members to <see cref="IEnumerable{T}"/> with no inheritance.</summary>
public static class EnumerableExtensions
{
    extension<T>(IEnumerable<T> source)
    {
        public bool IsEmpty => !source.Any();
    }
}

/// <summary>
/// A catalog of C# / .NET language features, each executable on demand so the native UI can show the
/// live output and timing per feature. Everything here is AOT-safe — no reflection.
/// </summary>
public static class LanguageFeatureCatalog
{
    /// <summary>The executable features.</summary>
    public static IReadOnlyList<LanguageFeature> Features { get; } = Build();

    /// <summary>UI descriptors, with the deterministic expected result captured once at startup.</summary>
    public static IReadOnlyList<FeatureDescriptor> Descriptors { get; } =
        Features.Select(f => new FeatureDescriptor(f.Id, f.Title, f.Version, f.Code, Safe(f.Execute))).ToList();

    /// <summary>Executes one feature by id and times it; Ok = output matches the expected result.</summary>
    public static FeatureRun Run(string id)
    {
        var feature = Features.FirstOrDefault(f => f.Id == id);
        if (feature is null)
        {
            return new FeatureRun(id, $"Unknown feature id: {id}", 0, false);
        }

        var expected = Descriptors.First(d => d.Id == id).Expected;
        var stopwatch = Stopwatch.StartNew();
        string result;
        bool ok;
        try
        {
            result = feature.Execute();
            ok = result == expected;
        }
        catch (Exception ex)
        {
            result = ex.Message;
            ok = false;
        }

        stopwatch.Stop();
        return new FeatureRun(id, result, stopwatch.Elapsed.TotalMilliseconds, ok);
    }

    private static string Safe(Func<string> execute)
    {
        try { return execute(); }
        catch (Exception ex) { return ex.Message; }
    }

    private static List<LanguageFeature> Build() =>
    [
        new("collection-expressions", "Collection expressions + spread", "C# 12",
            "int[] a = [1, 2, 3];\nint[] b = [0, ..a, 4];",
            static () =>
            {
                int[] a = [1, 2, 3];
                int[] b = [0, .. a, 4];
                return $"b = [{string.Join(", ", b)}]";
            }),

        new("list-patterns", "List patterns", "C# 11",
            "data is [var head, .., var tail]",
            static () =>
            {
                int[] data = [10, 20, 30, 40];
                return data is [var head, .., var tail] ? $"head={head}, tail={tail}" : "no match";
            }),

        new("records-with", "Records + with-expression", "C# 9",
            "var p2 = p1 with { Y = 9 };",
            static () =>
            {
                var p1 = new Point(2, 3);
                var p2 = p1 with { Y = 9 };
                return $"p1 = {p1}\np2 = {p2}";
            }),

        new("primary-constructors", "Primary constructors", "C# 12",
            "class Greeter(string name) {\n    public string Hi() => $\"Hi, {name}!\";\n}",
            static () => new Greeter("Ada").Hi()),

        new("field-keyword", "`field` keyword", "C# 14",
            "public string Value {\n    get => field;\n    set => field = value.Trim();\n}",
            static () =>
            {
                var user = new TrimmedName { Value = "  Grace  " };
                return $"set \"  Grace  \"  ->  \"{user.Value}\"";
            }),

        new("extension-members", "Extension members (extension blocks)", "C# 14",
            "extension<T>(IEnumerable<T> source) {\n    public bool IsEmpty => !source.Any();\n}",
            static () =>
            {
                int[] one = [1];
                return $"[].IsEmpty = {Array.Empty<int>().IsEmpty},  [1].IsEmpty = {one.IsEmpty}";
            }),

        new("params-span", "params ReadOnlySpan<T>", "C# 13",
            "int Sum(params ReadOnlySpan<int> xs)",
            static () => $"Sum(1, 2, 3, 4) = {Sum(1, 2, 3, 4)}"),

        new("generic-math", "Generic math (INumber<T>)", "C# 11 / .NET 7",
            "T Add<T>(T a, T b) where T : INumber<T> => a + b;",
            static () => $"Add(3, 4) = {Add(3, 4)}\nAdd(2.5, 1.5) = {Add(2.5, 1.5).ToString(CultureInfo.InvariantCulture)}"),

        new("switch-expression", "Switch expression + patterns", "C# 8",
            "n switch { < 0 => \"neg\", 0 => \"zero\", _ => \"pos\" }",
            static () => $"-5 -> {Classify(-5)},  0 -> {Classify(0)},  7 -> {Classify(7)}"),

        new("raw-strings", "Raw string literals", "C# 11",
            "var json = \"\"\"{ \"ok\": true }\"\"\";",
            static () => """{ "feature": "raw string literal", "ok": true }"""),
    ];

    private static int Sum(params ReadOnlySpan<int> values)
    {
        var total = 0;
        foreach (var v in values)
        {
            total += v;
        }

        return total;
    }

    private static T Add<T>(T a, T b) where T : INumber<T> => a + b;

    private static string Classify(int n) => n switch
    {
        < 0 => "neg",
        0 => "zero",
        _ => "pos",
    };

    private sealed record Point(int X, int Y);

    private sealed class Greeter(string name)
    {
        public string Hi() => $"Hi, {name}!";
    }

    private sealed class TrimmedName
    {
        public string Value
        {
            get => field;
            set => field = value.Trim();
        } = "";
    }
}
