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
/// </summary>
public static class EngineTrace
{
    /// <summary>Ring capacity — 512 spans, drop-oldest on overflow (a decided knob; see the Wave B spec).</summary>
    public const int Capacity = 512;

    /// <summary>The single source every engine/transport span is created from.</summary>
    public static readonly ActivitySource Source = new("Dni.Engine");

    /// <summary>
    /// Companion metrics source. Instruments are recorded unconditionally but are no-ops until an external
    /// <see cref="MeterListener"/> (or an OpenTelemetry exporter) subscribes — this wave ships the spans;
    /// the meter is the forward-looking hook for a future metrics consumer (kept minimal on purpose).
    /// </summary>
    public static readonly Meter Meter = new("Dni.Engine");

    private static readonly Counter<long> SpansRecorded = Meter.CreateCounter<long>("dni.spans.recorded");
    private static readonly Counter<long> SpansDropped = Meter.CreateCounter<long>("dni.spans.dropped");
    private static readonly Histogram<double> SpanDurationUs =
        Meter.CreateHistogram<double>("dni.span.duration", unit: "us");

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

    private static void Record(Activity activity)
    {
        // Duration is high-res; the span started durUs ago, and the stop is ~now, so startUs = now - dur.
        var durUs = activity.Duration.TotalMicroseconds;
        var startUs = NowUs() - durUs;
        var requestId = activity.GetTagItem("dni.request_id") as string;
        var status = activity.Status == ActivityStatusCode.Unset ? null : activity.Status.ToString();
        var span = new TraceSpan(activity.OperationName, Math.Round(startUs, 1), Math.Round(durUs, 1), requestId, status);

        SpansRecorded.Add(1);
        SpanDurationUs.Record(durUs);

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
