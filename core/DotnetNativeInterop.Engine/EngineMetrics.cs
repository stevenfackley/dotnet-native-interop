using System.Diagnostics.Metrics;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace DotnetNativeInterop.Engine;

/// <summary>Cumulative request counts for each built transport (see <see cref="EngineTrace.RecordRequest"/>).</summary>
public sealed record RequestCounts(long Ffi, long Http, long Sqlite, long Pb);

/// <summary>Cumulative invocation count for one Foreman tool name.</summary>
public sealed record ToolCallCount(string Tool, long Count);

/// <summary>
/// count/sum/min/max microsecond duration across every span recorded under one operation name (e.g.
/// <c>pb.execute</c>, <c>agent.turn</c>). No percentiles are reported — see <see cref="EngineMetrics"/>'s
/// remarks for why a bucketless summary is the honest choice here.
/// </summary>
public sealed record OperationDuration(string Op, long Count, double SumUs, double MinUs, double MaxUs);

/// <summary>
/// The <c>metrics~snapshot</c> payload. Every field is a PROCESS-LIFETIME CUMULATIVE total from engine
/// boot — see <see cref="EngineMetrics"/> for why this mirrors <c>trace~stats</c> rather than
/// <c>dni_trace_drain</c> (it is a repeatable read, not a drain; nothing here is ever reset).
/// </summary>
public sealed record MetricsSnapshot(
    double NowUs,
    RequestCounts Requests,
    long SpansRecorded,
    long SpansDropped,
    long AgentTurns,
    IReadOnlyList<ToolCallCount> AgentToolCalls,
    IReadOnlyList<OperationDuration> OperationDurations);

/// <summary>
/// A <see cref="MeterListener"/>-based aggregator for the <c>Dni.Engine</c> <see cref="Meter"/> (see
/// <see cref="EngineTrace.Meter"/> and its instruments). Subscribes to every instrument published on that
/// meter and accumulates running totals in-process — the metrics equivalent of the trace ring, except
/// nothing is ever dropped (these are aggregates, not individual records, so there is nothing to overflow)
/// and nothing is ever cleared: every counter/histogram here accumulates for the process lifetime, exactly
/// like <see cref="EngineTrace.Stats"/>'s <c>RecordedTotal</c>/<c>DroppedTotal</c> fields. <c>metrics~snapshot</c>
/// is a repeatable read, mirroring <c>trace~stats</c> — not a drain like <c>dni_trace_drain</c>.
///
/// Honesty: histograms expose count/sum/min/max only. A real percentile needs either the full sample set
/// or a proper sketch (t-digest/HDR histogram); neither is implemented here, so nothing calling itself
/// "p50"/"p99" is reported — a fabricated percentile derived from 4 summary numbers would be worse than no
/// percentile at all.
///
/// Thread-safety mirrors <see cref="EngineTrace"/>'s ring: a single lock guards all mutable state, and
/// every measurement callback (which can fire from whatever thread the instrumented code runs on) takes it.
///
/// AOT-safe: <see cref="MeterListener"/> callbacks read primitive tag values with no reflection; the
/// snapshot serializes through the source-gen <see cref="MetricsJsonContext"/>.
/// </summary>
public static class EngineMetrics
{
    private const string TagTransport = "dni.transport";
    private const string TagTool = "dni.tool";
    private const string TagOperation = "dni.op";

    private static readonly object Gate = new();

    private static long _spansRecorded;
    private static long _spansDropped;
    private static long _requestsFfi;
    private static long _requestsHttp;
    private static long _requestsSqlite;
    private static long _requestsPb;
    private static long _agentTurns;
    private static readonly Dictionary<string, long> AgentToolCalls = new(StringComparer.Ordinal);
    private static readonly Dictionary<string, DurationAccumulator> OperationDurations = new(StringComparer.Ordinal);

    // Kept alive for the process lifetime by this static field — a MeterListener with no surviving
    // reference could otherwise be collected. Start() replays InstrumentPublished for every instrument
    // already published on Dni.Engine, so subscription order relative to EngineTrace's own instrument
    // field initializers does not matter.
    private static readonly MeterListener Listener = CreateListener();

    // An explicit (even empty) static constructor gives this type "precise" type-initialization
    // semantics instead of the default "beforefieldinit": WITHOUT this, the CLR's before-first-access
    // guarantee applies only to static FIELD access, not static METHOD calls — so calling EnsureStarted()
    // below would NOT actually run the field initializers above until something later touched a field
    // directly (verified empirically: the Listener was created several seconds late, well after the
    // harness's driven pb/http/broker/agent operations had already recorded and been lost). With this
    // constructor present, EnsureStarted() is guaranteed to force it — see EngineTrace's static ctor.
    static EngineMetrics()
    {
    }

    /// <summary>
    /// Forces this class's static initializer — and so the <see cref="MeterListener"/> subscription — to
    /// run. Called once from <see cref="EngineTrace"/>'s static constructor (after its own instruments
    /// exist) so metrics collection is guaranteed live before any transport can record a measurement;
    /// otherwise a measurement recorded before the first organic touch of this type would be silently
    /// lost (an unsubscribed instrument's <c>Add</c>/<c>Record</c> is a cheap no-op, not a queued event).
    /// </summary>
    public static void EnsureStarted()
    {
        // Intentionally empty — merely referencing this method loads EngineMetrics (see the explicit
        // static constructor above for why an empty method call is enough) and runs the Listener field
        // initializer.
    }

    /// <summary>A non-draining, process-lifetime-cumulative snapshot of every metric (for <c>metrics~snapshot</c>).</summary>
    public static MetricsSnapshot Snapshot()
    {
        lock (Gate)
        {
            var toolCalls = new List<ToolCallCount>(AgentToolCalls.Count);
            foreach (var (tool, count) in AgentToolCalls)
            {
                toolCalls.Add(new ToolCallCount(tool, count));
            }

            var durations = new List<OperationDuration>(OperationDurations.Count);
            foreach (var (op, acc) in OperationDurations)
            {
                durations.Add(new OperationDuration(
                    op, acc.Count, Math.Round(acc.Sum, 1), Math.Round(acc.Min, 1), Math.Round(acc.Max, 1)));
            }

            return new MetricsSnapshot(
                Math.Round(EngineTrace.NowUs(), 1),
                new RequestCounts(_requestsFfi, _requestsHttp, _requestsSqlite, _requestsPb),
                _spansRecorded,
                _spansDropped,
                _agentTurns,
                toolCalls,
                durations);
        }
    }

    /// <summary>Serializes <see cref="Snapshot"/> to camelCase JSON via the source-gen context.</summary>
    public static string SnapshotJson() =>
        JsonSerializer.Serialize(Snapshot(), MetricsJsonContext.Default.MetricsSnapshot);

    private static MeterListener CreateListener()
    {
        var listener = new MeterListener
        {
            // Name-based match mirrors EngineTrace's own ActivityListener (`src.Name == Source.Name`) —
            // consistent with how this codebase scopes a listener to "the Dni.Engine instrumentation",
            // rather than a reference-equality check on the Meter instance.
            InstrumentPublished = static (instrument, l) =>
            {
                if (instrument.Meter.Name == EngineTrace.Meter.Name)
                {
                    l.EnableMeasurementEvents(instrument);
                }
            },
        };

        listener.SetMeasurementEventCallback<long>(OnLongMeasurement);
        listener.SetMeasurementEventCallback<double>(OnDoubleMeasurement);
        listener.Start();
        return listener;
    }

    private static void OnLongMeasurement(
        Instrument instrument, long measurement, ReadOnlySpan<KeyValuePair<string, object?>> tags, object? state)
    {
        lock (Gate)
        {
            switch (instrument.Name)
            {
                case "dni.spans.recorded":
                    _spansRecorded += measurement;
                    break;
                case "dni.spans.dropped":
                    _spansDropped += measurement;
                    break;
                case "dni.requests":
                    AddRequest(TagValue(tags, TagTransport), measurement);
                    break;
                case "dni.agent.turns":
                    _agentTurns += measurement;
                    break;
                case "dni.agent.tool_calls":
                    var tool = TagValue(tags, TagTool) ?? "(unknown)";
                    AgentToolCalls[tool] = AgentToolCalls.GetValueOrDefault(tool) + measurement;
                    break;
            }
        }
    }

    private static void OnDoubleMeasurement(
        Instrument instrument, double measurement, ReadOnlySpan<KeyValuePair<string, object?>> tags, object? state)
    {
        if (instrument.Name != "dni.span.duration")
        {
            return;
        }

        var op = TagValue(tags, TagOperation) ?? "(unknown)";
        lock (Gate)
        {
            if (!OperationDurations.TryGetValue(op, out var acc))
            {
                acc = new DurationAccumulator();
                OperationDurations[op] = acc;
            }

            acc.Count++;
            acc.Sum += measurement;
            if (measurement < acc.Min) { acc.Min = measurement; }
            if (measurement > acc.Max) { acc.Max = measurement; }
        }
    }

    // Caller holds Gate.
    private static void AddRequest(string? transport, long delta)
    {
        switch (transport)
        {
            case EngineTrace.Transports.Ffi: _requestsFfi += delta; break;
            case EngineTrace.Transports.Http: _requestsHttp += delta; break;
            case EngineTrace.Transports.Sqlite: _requestsSqlite += delta; break;
            case EngineTrace.Transports.Pb: _requestsPb += delta; break;
        }
    }

    private static string? TagValue(ReadOnlySpan<KeyValuePair<string, object?>> tags, string key)
    {
        foreach (var kv in tags)
        {
            if (kv.Key == key)
            {
                return kv.Value as string;
            }
        }

        return null;
    }

    private sealed class DurationAccumulator
    {
        public long Count;
        public double Sum;
        public double Min = double.PositiveInfinity;
        public double Max = double.NegativeInfinity;
    }
}

/// <summary>Source-generated JSON metadata for the metrics payload (AOT-safe, no reflection).</summary>
[JsonSourceGenerationOptions(PropertyNamingPolicy = JsonKnownNamingPolicy.CamelCase)]
[JsonSerializable(typeof(MetricsSnapshot))]
internal sealed partial class MetricsJsonContext : JsonSerializerContext;
