using System.Runtime.CompilerServices;
using System.Text;

using GridCell = (int Row, int Col);

namespace DotnetNativeInterop.Engine;

/// <summary>
/// Modern language syntax demos (C# 14 down to C# 10) that round out the catalog: null-conditional
/// assignment, unbound-generic nameof, user-defined compound assignment, System.Threading.Lock,
/// from-the-end object initializers, partial properties, inline arrays, lambda defaults, type aliases,
/// required members, UTF-8 literals, file-local types, record structs, custom interpolated string
/// handlers, and extended property patterns. All AOT-safe and deterministic.
/// </summary>
public static partial class LanguageFeatureCatalog
{
    private static List<LanguageFeature> ModernSyntaxDemos() =>
    [
        new("null-conditional-assignment", "Null-conditional assignment", "C# 14",
            "label?.Text = \"ready\";   // assigns only when label is not null",
            static () =>
            {
                StatusLabel? offline = null;
                StatusLabel? online = new StatusLabel();
                offline?.Text = "ready";
                online?.Text = "ready";
                return $"offline?.Text = \"ready\"  ->  skipped, offline is still null: {offline is null}\nonline?.Text  = \"ready\"  ->  \"{online?.Text}\"";
            }),

        new("nameof-unbound-generics", "nameof with unbound generics", "C# 14",
            "nameof(List<>)          // \"List\"\nnameof(Dictionary<,>)   // \"Dictionary\"",
            static () => $"nameof(List<>) = {nameof(List<>)}\nnameof(Dictionary<,>) = {nameof(Dictionary<,>)}\nnameof(IEnumerable<>) = {nameof(IEnumerable<>)}"),

        new("compound-assignment-operators", "User-defined compound assignment", "C# 14",
            "public void operator +=(int km) => Km += km;   // instance op, mutates in place",
            static () =>
            {
                var trip = new Odometer();
                trip += 42;
                trip += 8;
                return $"trip += 42; trip += 8  ->  {trip.Km} km (in-place, no new instance)";
            }),

        new("threading-lock", "System.Threading.Lock", "C# 13",
            "private static readonly Lock TallyLock = new();\nlock (TallyLock) { total++; }   // lowers to Lock.EnterScope()",
            static () =>
            {
                var total = 0;
                var workers = Enumerable.Range(0, 4).Select(_ => Task.Run(() =>
                {
                    for (var i = 0; i < 25_000; i++)
                    {
                        lock (TallyLock)
                        {
                            total++;
                        }
                    }
                })).ToArray();
                Task.WaitAll(workers);
                return $"4 workers x 25,000 increments under Lock  ->  total = {total:N0}";
            }),

        new("index-initializers", "^ index in object initializers", "C# 13",
            "var c = new Countdown { [0] = 9, [^1] = 1, [^2] = 2 };",
            static () =>
            {
                var countdown = new Countdown { [0] = 9, [^1] = 1, [^2] = 2 };
                return $"slots = [{string.Join(", ", countdown.Buffer)}] (^1 resolved via Length at init time)";
            }),

        new("partial-properties", "Partial properties", "C# 13",
            "public partial string Text { get; }          // declaration\npublic partial string Text => \"...\";         // implementation elsewhere",
            static () => $"declaration and implementation live in separate partial parts\nText = \"{new EngineMotto().Text}\""),

        new("inline-arrays", "Inline arrays", "C# 12",
            "[InlineArray(8)]\nstruct Frame8 { private int _element0; }   // fixed buffer, span-convertible",
            static () =>
            {
                var frame = new Frame8();
                Span<int> view = frame;
                for (var i = 0; i < 8; i++)
                {
                    view[i] = (i + 1) * (i + 1);
                }

                var total = 0;
                foreach (var value in view)
                {
                    total += value;
                }

                return $"frame = [{string.Join(", ", view.ToArray())}]\nsum = {total} (one struct, zero heap arrays)";
            }),

        new("lambda-defaults", "Default lambda parameters", "C# 12",
            "var scale = (int value, int factor = 10) => value * factor;",
            static () =>
            {
                var scale = (int value, int factor = 10) => value * factor;
                return $"scale(7) = {scale(7)},  scale(7, 3) = {scale(7, 3)}";
            }),

        new("alias-any-type", "Alias any type", "C# 12",
            "using GridCell = (int Row, int Col);   // alias a tuple shape file-wide",
            static () =>
            {
                GridCell cell = (3, 4);
                return $"cell = {cell}, cell.Row = {cell.Row} (alias is erased at compile time)";
            }),

        new("required-members", "Required members", "C# 11",
            "public required string Region { get; init; }",
            static () =>
            {
                var plan = new LaunchPlan { Region = "eu-north", Channel = "stable" };
                return $"initialized: {plan.Region} / {plan.Channel}\nomitting either is a compile error (CS9035), not a runtime surprise";
            }),

        new("utf8-literals", "UTF-8 string literals", "C# 11",
            "ReadOnlySpan<byte> payload = \"C# on metal\"u8;   // bytes baked into the binary",
            static () =>
            {
                ReadOnlySpan<byte> payload = "C# on metal"u8;
                return $"{payload.Length} bytes, no Encoding.GetBytes at runtime\nhex = {Convert.ToHexString(payload)}";
            }),

        new("file-local-types", "File-local types", "C# 11",
            "file static class FileScopedStamp { ... }   // invisible outside this file",
            static () => $"{FileScopedStamp.Make()}\n(other files in the same assembly cannot even name it)"),

        new("record-structs", "Readonly record structs", "C# 10",
            "readonly record struct Reading(double Celsius, int Hour);\nvar noon = morning with { Celsius = 24.5, Hour = 12 };",
            static () =>
            {
                var morning = new Reading(18.5, 9);
                var noon = morning with { Celsius = 24.5, Hour = 12 };
                var copy = morning with { };
                return $"{morning}\n{noon}\nmorning == copy: {morning == copy} (value equality, lives on the stack)";
            }),

        new("string-handlers", "Custom interpolated string handler", "C# 10",
            "[InterpolatedStringHandler]\nstruct AnnotatingHandler {\n    public void AppendLiteral(string s);\n    public void AppendFormatted<T>(T value);\n}",
            static () => Annotate($"engine reports {8} cores at {72.5} C")),

        new("extended-property-patterns", "Extended property patterns", "C# 10",
            "site is { Address.City: \"Oslo\" }   // dotted member access in patterns",
            static () =>
            {
                var hq = new Site("Engine HQ", new Address("Oslo", "NO"));
                var inOslo = hq is { Address.City: "Oslo" };
                var inUs = hq is { Address.Country: "US" };
                return $"{{ Address.City: \"Oslo\" }} -> {inOslo}\n{{ Address.Country: \"US\" }} -> {inUs}";
            }),
    ];

    private static readonly Lock TallyLock = new();

    private sealed class StatusLabel
    {
        public string Text { get; set; } = "";
    }

    private sealed class Odometer
    {
        public int Km { get; private set; }

        public void operator +=(int km) => Km += km;
    }

    private sealed class Countdown
    {
        public int[] Buffer { get; } = new int[5];

        public int Length => Buffer.Length;

        public int this[int index]
        {
            get => Buffer[index];
            set => Buffer[index] = value;
        }
    }

    private sealed partial class EngineMotto
    {
        public partial string Text { get; }
    }

    private sealed partial class EngineMotto
    {
        private readonly string _suffix = "ahead of time";

        public partial string Text => $"compiled {_suffix}";
    }

    [InlineArray(8)]
    private struct Frame8
    {
        private int _element0;
    }

    private sealed class LaunchPlan
    {
        public required string Region { get; init; }

        public required string Channel { get; init; }
    }

    private readonly record struct Reading(double Celsius, int Hour);

    private sealed record Address(string City, string Country);

    private sealed record Site(string Name, Address Address);

    /// <summary>Forces interpolated-string arguments through <see cref="AnnotatingHandler"/>.</summary>
    private static string Annotate(AnnotatingHandler handler) => handler.ToString();

    /// <summary>
    /// The compiler routes every literal chunk and interpolation hole of a <c>$"..."</c> argument
    /// through this struct at the call site — no intermediate string, no boxing for value types.
    /// </summary>
    [InterpolatedStringHandler]
    private readonly struct AnnotatingHandler
    {
        private readonly StringBuilder _text;

        public AnnotatingHandler(int literalLength, int formattedCount)
            => _text = new StringBuilder(literalLength + (formattedCount * 8));

        public void AppendLiteral(string s) => _text.Append(s);

        public void AppendFormatted<T>(T value) =>
            _text.Append('[').Append(value?.ToString()).Append(':').Append(typeof(T).Name).Append(']');

        public override string ToString() => _text.ToString();
    }
}

/// <summary>A file-local type: only code inside this file can reference it (C# 11).</summary>
file static class FileScopedStamp
{
    public static string Make() => "stamped by a file-local type";
}
