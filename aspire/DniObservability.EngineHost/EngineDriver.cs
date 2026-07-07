using DotnetNativeInterop.Engine;
using Microsoft.Extensions.Logging;

namespace DniObservability.EngineHost;

/// <summary>Source-generated log messages for <see cref="EngineDriver"/> (avoids CA1848/CA1873).</summary>
internal static partial class EngineDriverLog
{
    [LoggerMessage(Level = LogLevel.Information, Message = "EngineDriver tick {Tick}: feature={FeatureId} rag_chars={RagChars}")]
    public static partial void Tick(ILogger logger, int tick, string featureId, int ragChars);

    [LoggerMessage(Level = LogLevel.Error, Message = "EngineDriver tick {Tick} failed")]
    public static partial void TickFailed(ILogger logger, Exception ex, int tick);
}

/// <summary>One iteration's result, returned by the manual <c>/demo/run</c> trigger.</summary>
public sealed record DriveResult(string FeatureId, string FeatureOutput, string RagOutput);

/// <summary>
/// Periodically drives real <c>DotnetNativeInterop.Engine</c> work so the Aspire dashboard has live
/// <c>Dni.Engine</c> traces and metrics to show. Every span/metric produced here comes from the exact
/// same <see cref="EngineTrace"/>/<see cref="EngineMetrics"/> machinery the shipped mobile engine uses
/// — this class does not fabricate telemetry, it just gives the engine something to do on a timer.
///
/// Deliberately does NOT call <see cref="EngineTrace.RecordRequest"/>: that counter's transport tags
/// (ffi/http/sqlite/pb) are reserved for the real built transports in NativeBridge, and tagging demo
/// traffic with one of them would make the dashboard's per-transport request counts misleading. What
/// this class DOES produce honestly: <c>dni.spans.recorded</c>/<c>dni.span.duration</c> (from every
/// span below) and the genuine <c>rag.retrieve</c>/<c>rag.prompt</c>/<c>rag.generate</c> spans that
/// <see cref="RagLanguageModel"/> already emits in production.
/// </summary>
public sealed class EngineDriver(ILogger<EngineDriver> logger) : BackgroundService
{
    // A handful of real, no-asset-dependency feature ids from LanguageFeatureCatalog — enough variety
    // that the dashboard's per-operation duration breakdown (see EngineMetrics.OperationDurations)
    // shows more than one bucket.
    private static readonly string[] FeatureIds =
    [
        "collection-expressions", "list-patterns", "records-with", "generic-math", "switch-expression",
    ];

    private static readonly string[] RagPrompts =
    [
        "How do I fix a HVAC airflow problem?",
        "The server room is overheating, what should I check?",
        "Network link keeps dropping, where do I start?",
    ];

    // Built once and reused: SemanticSearch.Default lazily loads the bundled ONNX encoder + manuals
    // corpus on first touch (see Ai/SemanticSearch.cs) — expensive enough that it should happen once,
    // not per iteration.
    private readonly RagLanguageModel _rag = new(new MockLanguageModel(TimeSpan.FromMilliseconds(2)), topK: 2);

    private int _tick;

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        // Give the dashboard/OTLP pipe a moment to come up before the first export.
        await Task.Delay(TimeSpan.FromSeconds(3), stoppingToken).ConfigureAwait(false);

        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                var result = await RunOnceAsync(stoppingToken).ConfigureAwait(false);
                EngineDriverLog.Tick(logger, _tick, result.FeatureId, result.RagOutput.Length);
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                EngineDriverLog.TickFailed(logger, ex, _tick);
            }

            await Task.Delay(TimeSpan.FromSeconds(5), stoppingToken).ConfigureAwait(false);
        }
    }

    /// <summary>Runs one feature + one RAG generation, each wrapped in a real <c>Dni.Engine</c> span.</summary>
    public async Task<DriveResult> RunOnceAsync(CancellationToken ct)
    {
        var featureId = FeatureIds[Interlocked.Increment(ref _tick) % FeatureIds.Length];

        string featureOutput;
        using (var span = EngineTrace.StartSpan($"aspire.demo.feature.{featureId}"))
        {
            var run = LanguageFeatureCatalog.Run(featureId);
            span?.SetTag("dni.feature.ok", run.Ok);
            featureOutput = run.Result;
        }

        var prompt = RagPrompts[_tick % RagPrompts.Length];
        var request = new InferenceRequest(prompt, MaxTokens: 8);
        var chunks = new List<string>();
        await foreach (var fragment in _rag.GenerateAsync(request, ct).ConfigureAwait(false))
        {
            chunks.Add(fragment);
        }

        return new DriveResult(featureId, featureOutput, string.Concat(chunks));
    }
}
