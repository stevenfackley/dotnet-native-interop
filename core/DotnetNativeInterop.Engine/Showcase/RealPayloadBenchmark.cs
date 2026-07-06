using System.Diagnostics;
using System.Globalization;
using System.Text;
using System.Text.Json;

namespace DotnetNativeInterop.Engine;

/// <summary>
/// Benchmarks REAL structured payloads instead of <c>bench-echo</c>'s synthetic filler string: the
/// full language-feature catalog JSON (the same data <c>dni_features_json</c> returns), and a genuine
/// RAG context blob built from the manuals corpus retrieval path (embed the query, rank passages,
/// assemble the grounded prompt — the same steps <see cref="RagLanguageModel"/> runs before calling the
/// LLM). Feeds the POC's core FFI-vs-HTTP-vs-SQLCipher chart, which needs realistic payload sizes; the
/// transport cost itself is measured client-side, so this only has to produce a genuine payload and
/// time producing it. AOT-safe: no reflection; JSON via <see cref="ShowcaseJsonContext"/>.
/// </summary>
public static class RealPayloadBenchmark
{
    // Representative maintenance questions mirroring the bundled manuals, cycled by rep index so
    // "ragctx"/"mixed" reps exercise varied retrieval instead of one memoized query.
    private static readonly string[] SampleQuestions =
    [
        "Server shuts down under load with a thermal trip",
        "Switch port link keeps flapping",
        "Weak airflow and the coil keeps icing over",
        "Rooftop unit isn't cooling",
        "Server won't power back on after a PSU swap",
    ];

    /// <summary>Runs <paramref name="reps"/> repetitions of the <paramref name="kind"/> payload, timing
    /// each rep's production + serialization.</summary>
    public static BenchmarkPayload Run(string kind, int reps)
    {
        var bytesSeries = new List<BenchmarkPoint>(reps);
        var microsSeries = new List<BenchmarkPoint>(reps);
        long totalBytes = 0;
        double totalMicros = 0;

        for (var i = 0; i < reps; i++)
        {
            var stopwatch = Stopwatch.StartNew();
            var bytes = kind switch
            {
                "catalog" => CatalogBytes(),
                "ragctx" => RagCtxBytes(i),
                _ => CatalogBytes() + RagCtxBytes(i), // "mixed"
            };
            stopwatch.Stop();

            var micros = stopwatch.Elapsed.TotalMicroseconds;
            bytesSeries.Add(new BenchmarkPoint(i + 1, bytes));
            microsSeries.Add(new BenchmarkPoint(i + 1, Math.Round(micros, 1)));
            totalBytes += bytes;
            totalMicros += micros;
        }

        return new BenchmarkPayload(
            "benchmark", $"Realistic payload bench ({kind}, {reps} reps)",
            [
                new BenchmarkSeries("payloadBytes", bytesSeries.ToArray()),
                new BenchmarkSeries("serializeUs", microsSeries.ToArray()),
            ],
            [
                new SummaryStat("total bytes", totalBytes.ToString(CultureInfo.InvariantCulture)),
                new SummaryStat("avg bytes/rep", (totalBytes / reps).ToString(CultureInfo.InvariantCulture)),
                new SummaryStat("total serialize", (totalMicros / 1000.0).ToString("F2", CultureInfo.InvariantCulture) + " ms"),
                new SummaryStat("avg serialize/rep", (totalMicros / reps).ToString("F1", CultureInfo.InvariantCulture) + " µs"),
            ]);
    }

    // Serializes the full language-feature catalog via the Type-based overload (mirrors the NativeBridge
    // dni_features_json export) so no source-gen property name is needed for the collection shape.
    private static int CatalogBytes()
    {
        var json = JsonSerializer.Serialize(
            LanguageFeatureCatalog.Descriptors, typeof(IReadOnlyList<FeatureDescriptor>), ShowcaseJsonContext.Default);
        return Encoding.UTF8.GetByteCount(json);
    }

    // Runs the SAME retrieval path "Ask the Manuals" uses (embed -> rank -> assemble prompt) so the
    // context blob is a genuine RAG payload, not synthetic filler. NOTE: rep 1's serializeUs will be a
    // large outlier the first time ANY "ragctx"/"mixed" command runs in this process — SemanticSearch's
    // lazily-built default instance loads the ONNX model and embeds the whole corpus on first touch.
    // That's a genuine cold-start cost, not a bug; left un-warmed-up on purpose since it's real evidence
    // for the FFI-vs-HTTP-vs-SQLCipher thesis chart.
    private static int RagCtxBytes(int rep)
    {
        var question = SampleQuestions[rep % SampleQuestions.Length];
        var hits = SemanticSearch.Default.Search(question, "manuals", topK: 3);
        var prompt = RagPrompt.Build(question, hits);
        return Encoding.UTF8.GetByteCount(prompt);
    }
}
