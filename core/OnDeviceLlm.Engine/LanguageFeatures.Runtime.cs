using System.Globalization;
using System.IO.Compression;
using System.Numerics;
using System.Numerics.Tensors;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;
using System.Threading.Channels;

namespace OnDeviceLlm.Engine;

/// <summary>
/// The second showcase tier: .NET <em>runtime / BCL</em> capabilities (not language syntax) executed
/// live inside the NativeAOT library — cryptography, source-generated JSON and regex, SIMD tensor math,
/// arbitrary-precision integers, compression, channels, and zero-allocation parsing. Every demo is
/// deterministic and reflection-free so the existing self-check (<c>Ok = result == expected</c>) holds.
/// </summary>
public static partial class LanguageFeatureCatalog
{
    [GeneratedRegex(@"\b(\w+)@(\w+)\.(\w+)\b")]
    private static partial Regex EmailRegex();

    private static List<LanguageFeature> RuntimeDemos() =>
    [
        new("aes-gcm", "AES-GCM authenticated encryption", "Cryptography",
            "using var aes = new AesGcm(key, tagSizeInBytes: 16);\naes.Encrypt(nonce, plaintext, cipher, tag);\naes.Decrypt(nonce, cipher, tag, decrypted);",
            static () =>
            {
                // iOS/macOS route AES-GCM through Apple CryptoKit (.NET 9+). Guard so an unsupported
                // platform fails visibly rather than throwing (CryptoKit also requires a 16-byte tag).
                if (!AesGcm.IsSupported)
                {
                    return "AES-GCM is not supported on this platform.";
                }

                // Fixed key + nonce keep the demo deterministic. NEVER reuse a nonce with a real key.
                var key = Convert.FromHexString("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
                var nonce = Convert.FromHexString("0000000000000000deadbeef");
                var plaintext = Encoding.UTF8.GetBytes("C# running natively on iOS");
                var cipher = new byte[plaintext.Length];
                var tag = new byte[16];

                using var aes = new AesGcm(key, 16);
                aes.Encrypt(nonce, plaintext, cipher, tag);

                var decrypted = new byte[plaintext.Length];
                aes.Decrypt(nonce, cipher, tag, decrypted);

                return $"cipher = {Convert.ToHexString(cipher)}\ntag    = {Convert.ToHexString(tag)}\ndecrypted = \"{Encoding.UTF8.GetString(decrypted)}\"";
            }),

        new("json-sourcegen", "System.Text.Json source-gen round-trip", "Serialization",
            "var json = JsonSerializer.Serialize(profile, DemoJsonContext.Default.DemoProfile);\nvar back = JsonSerializer.Deserialize(json, DemoJsonContext.Default.DemoProfile);",
            static () =>
            {
                var profile = new DemoProfile("Ada", [98, 91, 100], Active: true);
                var json = JsonSerializer.Serialize(profile, DemoJsonContext.Default.DemoProfile);
                var back = JsonSerializer.Deserialize(json, DemoJsonContext.Default.DemoProfile)!;
                var avg = back.Scores.Average().ToString("F1", CultureInfo.InvariantCulture);
                return $"{json}\nround-trip avg = {avg}";
            }),

        new("regex-sourcegen", "[GeneratedRegex] compiled match + replace", "Text & Regex",
            "[GeneratedRegex(@\"\\b(\\w+)@(\\w+)\\.(\\w+)\\b\")]\nprivate static partial Regex EmailRegex();",
            static () =>
            {
                const string input = "ping ada@dotnet.dev and grace@dotnet.dev today";
                var matches = EmailRegex().Matches(input);
                var redacted = EmailRegex().Replace(input, "***@$2.$3");
                return $"matches = {matches.Count}\nfirst user = {matches[0].Groups[1].Value}\nredacted: {redacted}";
            }),

        new("tensor-cosine", "TensorPrimitives cosine similarity", "Vector Math · AI",
            "float cos = TensorPrimitives.CosineSimilarity(a, b); // SIMD-accelerated",
            static () =>
            {
                ReadOnlySpan<float> a = [0.10f, 0.20f, 0.30f, 0.40f, 0.50f];
                ReadOnlySpan<float> b = [0.20f, 0.10f, 0.40f, 0.30f, 0.60f];
                var cosine = TensorPrimitives.CosineSimilarity(a, b);
                return $"cosine(a, b) = {cosine.ToString("F4", CultureInfo.InvariantCulture)}";
            }),

        new("bigint", "BigInteger arbitrary precision", "Big Numbers",
            "BigInteger f = 1;\nfor (var n = 2; n <= 100; n++) f *= n;   // 100!",
            static () =>
            {
                BigInteger factorial = 1;
                for (var n = 2; n <= 100; n++)
                {
                    factorial *= n;
                }

                var pow = BigInteger.Pow(2, 256);
                return $"100!  = {factorial}\n2^256 = {pow}";
            }),

        new("brotli", "Brotli compress → decompress round-trip", "Compression",
            "using var b = new BrotliStream(ms, CompressionLevel.SmallestSize);\nb.Write(original);",
            static () =>
            {
                var original = Encoding.UTF8.GetBytes(string.Concat(Enumerable.Repeat("the quick brown fox; ", 50)));

                byte[] compressed;
                using (var ms = new MemoryStream())
                {
                    using (var brotli = new BrotliStream(ms, CompressionLevel.SmallestSize))
                    {
                        brotli.Write(original);
                    }

                    compressed = ms.ToArray();
                }

                byte[] restored;
                using (var input = new MemoryStream(compressed))
                using (var brotli = new BrotliStream(input, CompressionMode.Decompress))
                using (var output = new MemoryStream())
                {
                    brotli.CopyTo(output);
                    restored = output.ToArray();
                }

                var ratio = ((double)compressed.Length / original.Length * 100).ToString("F1", CultureInfo.InvariantCulture);
                var ok = restored.AsSpan().SequenceEqual(original) ? "verified" : "FAILED";
                return $"{original.Length} B -> {compressed.Length} B ({ratio}%)\nround-trip {ok}";
            }),

        new("channels", "Channels bounded pipeline (backpressure)", "Concurrency",
            "var ch = Channel.CreateBounded<int>(new BoundedChannelOptions(4) {\n    FullMode = BoundedChannelFullMode.Wait });",
            static () =>
            {
                var channel = Channel.CreateBounded<int>(
                    new BoundedChannelOptions(4) { FullMode = BoundedChannelFullMode.Wait });

                var producer = Task.Run(async () =>
                {
                    for (var i = 1; i <= 20; i++)
                    {
                        await channel.Writer.WriteAsync(i).ConfigureAwait(false);
                    }

                    channel.Writer.Complete();
                });

                var sum = 0;
                var count = 0;
                var consumer = Task.Run(async () =>
                {
                    await foreach (var item in channel.Reader.ReadAllAsync().ConfigureAwait(false))
                    {
                        sum += item;
                        count++;
                    }
                });

                Task.WaitAll(producer, consumer);
                return $"capacity = 4 (producer blocks when full)\nconsumed = {count}, sum(1..20) = {sum}";
            }),

        new("span-parse", "Span + stackalloc zero-allocation parse", "Memory",
            "Span<Range> ranges = stackalloc Range[8];\nvar n = csv.Split(ranges, ',');   // no heap",
            static () =>
            {
                ReadOnlySpan<char> csv = "10,20,30,40,50";
                Span<Range> ranges = stackalloc Range[8];
                var n = csv.Split(ranges, ',');

                var sum = 0;
                for (var i = 0; i < n; i++)
                {
                    sum += int.Parse(csv[ranges[i]], CultureInfo.InvariantCulture);
                }

                Span<char> buffer = stackalloc char[16];
                sum.TryFormat(buffer, out var written, provider: CultureInfo.InvariantCulture);
                return $"parsed {n} ints via Span split (no heap)\nsum = {buffer[..written]}";
            }),

        new("viz-fractal", ".NET-rendered Mandelbrot fractal", "Visual",
            "var pixels = FractalRenderer.Render();   // 128×128, every pixel computed in C#\nreturn $\"{Size}x{Size}:{Convert.ToBase64String(pixels)}\";",
            static () => FractalRenderer.RenderBase64()),

        new("ping", "Round-trip ping (latency target)", "Diagnostics",
            "// Minimal export the native latency chart hammers to measure pure call overhead.",
            static () => "pong"),
    ];
}

/// <summary>A small record used by the source-generated JSON round-trip demo.</summary>
internal sealed record DemoProfile(string Name, int[] Scores, bool Active);

/// <summary>
/// Source-generated JSON metadata for <see cref="DemoProfile"/>. Source generation keeps the demo
/// reflection-free, which NativeAOT requires.
/// </summary>
[JsonSourceGenerationOptions(PropertyNamingPolicy = JsonKnownNamingPolicy.CamelCase)]
[JsonSerializable(typeof(DemoProfile))]
internal sealed partial class DemoJsonContext : JsonSerializerContext;
