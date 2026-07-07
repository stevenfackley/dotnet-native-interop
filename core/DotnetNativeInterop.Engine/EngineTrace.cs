using System.Diagnostics;
using System.Diagnostics.Metrics;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace DotnetNativeInterop.Engine;

/// <summary>One recorded span: an <see cref="Activity"/> flattened for the drain payload.</summary>
/// <param name="Name">The <see cref="Activity.OperationName"/> (e.g. <c>pb.execute</c>).</param>
/// <param name="StartUs">Microseconds from engine boot to span start (see <see cref="EngineTrace.NowUs"/>).</param>
/// <param name="DurUs">Span wall-clock duration in microseconds (high-res, from <see cref="Activity.Duration"/>).</param>
/// <param name="RequestId">The client-supplied correlation id where one was passed; otherwise null.</param>
/// <param name="Status">The activity status when it is not <see cref="ActivityStatusCode.Unset"/>; otherwise null.</param>
public sealed record TraceSpan(string Name, double StartUs, double DurUs, string? RequestId, string? Status);

/// <summary>
/// The <c>dni_trace_drain</c> payload: every span recorded since the previous drain, plus the engine's
/// <see cref="NowUs"/> at drain time (so the client can compute a single boot-offset per drain) and the
/// count of spans dropped by ring overflow since the previous drain (overflow is disclosed, never silent).
/// </summary>
public sealed record TraceDrain(double NowUs, long Dropped, int Capacity, IReadOnlyList<TraceSpan> Spans);

/// <summary>The <c>trace~stats</c> payload: ring occupancy and drop/record counters.</summary>
public sealed record TraceStats(int Capacity, int Occupancy, long DroppedSinceDrain, long RecordedTotal, long DroppedTotal);

/// <summary>
/// In-process distributed tracing for the interop boundary. Every built transport (FFI, raw-HTTP,
/// SQLite broker, framed-protobuf) and the RAG pipeline emit <see cref="Activity"/> spans from the
/// <c>Dni.Engine</c> <see cref="ActivitySource"/>; a bounded ring listener captures them for the native UI
/// to drain and render as a per-request waterfall. This is the µs-strip grown up into first-class
/// <see cref="System.Diagnostics"/> machinery — inside one process.
///
/// Clock honesty: spans carry µs offsets from engine boot (high-resolution <see cref="Stopwatch"/>), and
/// each drain reports the engine's <see cref="NowUs"/>, so the client aligns engine-side spans to its own
/// clock with one offset per drain (accurate to ±the drain round-trip). No pretend-perfect cross-side clock.
///
/// AOT-safe: <see cref="ActivitySource"/>/<see cref="ActivityListener"/>/<see cref="Meter"/> live in the
/// shared framework and use no reflection; JSON goes through the source-gen <see cref="TraceJsonContext"/>.
///
/// Alongside the spans: the same boundaries also record onto <see cref="Meter"/> (requests-per-transport,
/// agent turns/tool calls, spans recorded/dropped, per-operation duration) — see <see cref="EngineMetrics"/>
/// for the aggregator and the <c>metrics~snapshot</c> command it backs.
/// </summary>
public static class EngineTrace
{
    /// <summary>Ring capacity — 512 spans, drop-oldest on overflow (a decided knob; see the Wave B spec).</summary>
    public const int Capacity = 512;

    /// <summary>The single source every engine/transport span is created from.</summary>
    public static readonly ActivitySource Source = new("Dni.Engine");

    /// <summary>
    /// Companion metrics source. Every instrument below is consumed in-process by
    /// <see cref="EngineMetrics"/>'s <see cref="MeterListener"/> (started from this class's static
    /// constructor) and exposed as the <c>metrics~snapshot</c> command — an external
    /// <see cref="MeterListener"/>/OpenTelemetry exporter could still subscribe alongside it, but nothing
    /// downstream depends on that; the in-process consumer is real.
    /// </summary>
    public static readonly Meter Meter = new("Dni.Engine");

    private static readonly Counter<long> SpansRecorded = Meter.CreateCounter<long>("dni.spans.recorded");
    private static readonly Counter<long> SpansDropped = Meter.CreateCounter<long>("dni.spans.dropped");
    private static readonly Histogram<double> SpanDurationUs =
        Meter.CreateHistogram<double>("dni.span.duration", unit: "us");

    // Wave B+1 (this wave): the small, honest instrument set beyond spans-recorded/dropped/duration
    // above. Reuses the exact points that already open spans (see the transport files' StartSpan calls
    // and ForemanAgent) so metrics and spans agree — see EngineMetrics for the consuming aggregator.
    private static readonly Counter<long> Requests = Meter.CreateCounter<long>("dni.requests");
    private static readonly Counter<long> AgentTurnsCounter = Meter.CreateCounter<long>("dni.agent.turns");
    private static readonly Counter<long> AgentToolCallsCounter = Meter.CreateCounter<long>("dni.agent.tool_calls");

    private const string TagTransport = "dni.transport";
    private const string TagTool = "dni.tool";
    private const string TagOperation = "dni.op";

    /// <summary>Canonical <see cref="TagTransport"/> values for <see cref="RecordRequest"/> — kept as
    /// constants so every call site (FFI/HTTP/SQLite/pb) uses the same literal.</summary>
    public static class Transports
    {
        public const string Ffi = "ffi";
        public const string Http = "http";
        public const string Sqlite = "sqlite";
        public const string Pb = "pb";
    }

    // Boot reference for the µs clock. Stopwatch is monotonic + high-resolution (unlike DateTime.UtcNow,
    // which is ~15 ms coarse on Windows) so span start/duration are genuine microseconds.
    private static readonly long BootTimestamp = Stopwatch.GetTimestamp();

    private static readonly object Gate = new();
    private static readonly TraceSpan?[] Ring = new TraceSpan?[Capacity];
    private static int _start;   // index of the oldest live span
    private static int _count;   // number of live spans in the ring
    private static long _droppedSinceDrain;
    private static long _droppedTotal;
    private static long _recordedTotal;

    static EngineTrace()
    {
        // A process-lifetime listener that records every Dni.Engine span. AllData means StartActivity
        // returns a live (non-null) Activity whose Duration is populated when it stops.
        var listener = new ActivityListener
        {
            ShouldListenTo = static src => src.Name == Source.Name,
            Sample = static (ref ActivityCreationOptions<ActivityContext> options) => ActivitySamplingResult.AllData,
            ActivityStopped = Record,
        };
        ActivitySource.AddActivityListener(listener);

        // Forces EngineMetrics's static initializer (its MeterListener subscription) to run now, so the
        // aggregator is guaranteed live before any instrument above can be touched by a caller — an
        // Add/Record with no subscribed listener is a silent no-op, not a queued event.
        EngineMetrics.EnsureStarted();
    }

    /// <summary>Microseconds elapsed since engine boot (the µs clock all spans share).</summary>
    public static double NowUs() => Stopwatch.GetElapsedTime(BootTimestamp).TotalMicroseconds;

    /// <summary>
    /// Starts a span. Returns the live <see cref="Activity"/> (dispose it — normally via <c>using</c> — to
    /// stop and record it) or null if tracing is somehow unlistened. Tags the client correlation id when
    /// one is supplied so the waterfall can group a request's engine-side spans with its client-side ones.
    /// </summary>
    public static Activity? StartSpan(string name, string? requestId = null)
    {
        var activity = Source.StartActivity(name);
        if (activity is not null && !string.IsNullOrEmpty(requestId))
        {
            activity.SetTag("dni.request_id", requestId);
        }

        return activity;
    }

    /// <summary>
    /// Records one request entering a built transport. Call this at the same call site that opens the
    /// transport's top-level request span (e.g. alongside <c>StartSpan("http.request", ...)</c>) so the
    /// <c>dni.requests</c> counter and the trace ring agree on what counts as "a request".
    /// </summary>
    /// <param name="transport">One of <see cref="Transports"/>.</param>
    public static void RecordRequest(string transport) =>
        Requests.Add(1, new KeyValuePair<string, object?>(TagTransport, transport));

    /// <summary>Records one Foreman agent turn (call once per <c>ForemanAgent.RunTurnAsync</c>).</summary>
    public static void RecordAgentTurn() => AgentTurnsCounter.Add(1);

    /// <summary>Records one Foreman tool invocation, tagged by tool name.</summary>
    public static void RecordAgentToolCall(string tool) =>
        AgentToolCallsCounter.Add(1, new KeyValuePair<string, object?>(TagTool, tool));

    private static void Record(Activity activity)
    {
        // Duration is high-res; the span started durUs ago, and the stop is ~now, so startUs = now - dur.
        var durUs = activity.Duration.TotalMicroseconds;
        var startUs = NowUs() - durUs;
        var requestId = activity.GetTagItem("dni.request_id") as string;
        var status = activity.Status == ActivityStatusCode.Unset ? null : activity.Status.ToString();
        var span = new TraceSpan(activity.OperationName, Math.Round(startUs, 1), Math.Round(durUs, 1), requestId, status);

        SpansRecorded.Add(1);
        // Tagged by operation name so the aggregator can report duration count/sum/min/max PER operation
        // (e.g. "pb.execute" vs "agent.turn") rather than one undifferentiated blob.
        SpanDurationUs.Record(durUs, new KeyValuePair<string, object?>(TagOperation, activity.OperationName));

        lock (Gate)
        {
            _recordedTotal++;
            if (_count == Capacity)
            {
                // Drop the oldest to make room — overflow is counted and surfaced in the drain payload.
                _start = (_start + 1) % Capacity;
                _count--;
                _droppedSinceDrain++;
                _droppedTotal++;
                SpansDropped.Add(1);
            }

            Ring[(_start + _count) % Capacity] = span;
            _count++;
        }
    }

    /// <summary>Removes and returns every span recorded since the previous drain, with drop count + <see cref="NowUs"/>.</summary>
    public static TraceDrain Drain()
    {
        lock (Gate)
        {
            var spans = new TraceSpan[_count];
            for (var i = 0; i < _count; i++)
            {
                spans[i] = Ring[(_start + i) % Capacity]!;
            }

            var dropped = _droppedSinceDrain;
            Array.Clear(Ring);
            _start = 0;
            _count = 0;
            _droppedSinceDrain = 0;

            return new TraceDrain(Math.Round(NowUs(), 1), dropped, Capacity, spans);
        }
    }

    /// <summary>Serializes <see cref="Drain"/> to camelCase JSON via the source-gen context.</summary>
    public static string DrainJson() => JsonSerializer.Serialize(Drain(), TraceJsonContext.Default.TraceDrain);

    /// <summary>A non-draining snapshot of ring occupancy and drop/record counters (for <c>trace~stats</c>).</summary>
    public static TraceStats Stats()
    {
        lock (Gate)
        {
            return new TraceStats(Capacity, _count, _droppedSinceDrain, _recordedTotal, _droppedTotal);
        }
    }

    /// <summary>Serializes <see cref="Stats"/> to camelCase JSON via the source-gen context.</summary>
    public static string StatsJson() => JsonSerializer.Serialize(Stats(), TraceJsonContext.Default.TraceStats);
}

/// <summary>Source-generated JSON metadata for the trace payloads (AOT-safe, no reflection).</summary>
[JsonSourceGenerationOptions(PropertyNamingPolicy = JsonKnownNamingPolicy.CamelCase)]
[JsonSerializable(typeof(TraceDrain))]
[JsonSerializable(typeof(TraceStats))]
internal sealed partial class TraceJsonContext : JsonSerializerContext;
