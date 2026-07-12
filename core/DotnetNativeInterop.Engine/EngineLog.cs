using System.Text.Json;
using System.Text.Json.Serialization;
using Microsoft.Extensions.Logging;

namespace DotnetNativeInterop.Engine;

/// <summary>One captured log record, flattened for the <c>dni_log_drain</c> payload.</summary>
/// <param name="Level">The <see cref="LogLevel"/> name (e.g. <c>Warning</c>).</param>
/// <param name="Category">The <see cref="ILogger"/> category the record was logged through.</param>
/// <param name="Message">The formatted message (structured params already interpolated).</param>
/// <param name="TimestampUs">Microseconds from engine boot (shares <see cref="EngineTrace.NowUs"/>'s clock,
/// so a log record aligns to the same timeline as spans).</param>
/// <param name="RequestId">Client correlation id when one is in scope; otherwise null (omitted from JSON).</param>
/// <param name="Exception">"<c>TypeName: message</c>" when the record carried an exception; else null
/// (omitted). This is the detail the FFI boundary used to swallow.</param>
public sealed record LogRecord(
    string Level, string Category, string Message, double TimestampUs,
    string? RequestId = null, string? Exception = null);

/// <summary>
/// The <c>dni_log_drain</c> payload: every record captured since the previous drain, the engine's
/// <see cref="EngineTrace.NowUs"/> at drain time (one boot-offset per drain for client clock alignment),
/// and the count dropped by ring overflow since the previous drain (disclosed, never silent).
/// </summary>
public sealed record LogDrain(double NowUs, long Dropped, int Capacity, IReadOnlyList<LogRecord> Records);

/// <summary>The <c>log~stats</c> payload: ring occupancy and drop/record counters (non-draining).</summary>
public sealed record LogStats(int Capacity, int Occupancy, long DroppedSinceDrain, long RecordedTotal, long DroppedTotal);

/// <summary>
/// The logging leg of the engine's observability trio — completing tracing (<see cref="EngineTrace"/>)
/// and metrics (<see cref="EngineMetrics"/>). Structured <c>[LoggerMessage]</c> events
/// (<see cref="EngineLogEvents"/>) funnel through a hand-written, reflection-free <see cref="ILogger"/>
/// (<see cref="RingLogger"/>) into a bounded ring the native UI drains via <c>dni_log_drain</c>. Its whole
/// reason to exist: the errors the FFI boundary would otherwise <c>catch (Exception) { return … }</c> away
/// silently now land here where a client can see them.
///
/// Same ring mechanics as the span ring: drop-oldest on overflow with the drop count disclosed in the
/// drain payload, µs offsets from engine boot, source-gen JSON (<see cref="LogJsonContext"/>) — AOT-safe,
/// no reflection.
/// </summary>
public static class EngineLog
{
    /// <summary>Ring capacity — 256 records, drop-oldest on overflow (a decided knob; mirrors the span ring).</summary>
    public const int Capacity = 256;

    /// <summary>
    /// Minimum level captured. Information and above only: on a bounded on-device ring, high-frequency
    /// Trace/Debug would evict the Warnings/Errors this leg exists to surface, so they are dropped at the
    /// logger before ever reaching the ring (a decided knob, like <see cref="Capacity"/>).
    /// </summary>
    public const LogLevel MinLevel = LogLevel.Information;

    private static readonly object Gate = new();
    private static readonly LogRecord?[] Ring = new LogRecord?[Capacity];
    private static int _start;   // index of the oldest live record
    private static int _count;   // number of live records in the ring
    private static long _droppedSinceDrain;
    private static long _droppedTotal;
    private static long _recordedTotal;

    /// <summary>The engine-wide logger (category <c>Dni.Engine</c>) the <see cref="EngineLogEvents"/> log through.</summary>
    public static ILogger Logger { get; } = new RingLogger("Dni.Engine", Append, MinLevel);

    /// <summary>A logger over the same ring tagged with <paramref name="category"/>.</summary>
    public static ILogger CreateLogger(string category) => new RingLogger(category, Append, MinLevel);

    // RingLogger calls this for each enabled record; drop-oldest insert, overflow counted (not silent).
    private static void Append(LogRecord record)
    {
        lock (Gate)
        {
            _recordedTotal++;
            if (_count == Capacity)
            {
                _start = (_start + 1) % Capacity;
                _count--;
                _droppedSinceDrain++;
                _droppedTotal++;
            }

            Ring[(_start + _count) % Capacity] = record;
            _count++;
        }
    }

    /// <summary>Removes and returns every record captured since the previous drain, with drop count + nowUs.</summary>
    public static LogDrain Drain()
    {
        lock (Gate)
        {
            var records = new LogRecord[_count];
            for (var i = 0; i < _count; i++)
            {
                records[i] = Ring[(_start + i) % Capacity]!;
            }

            var dropped = _droppedSinceDrain;
            Array.Clear(Ring);
            _start = 0;
            _count = 0;
            _droppedSinceDrain = 0;

            return new LogDrain(Math.Round(EngineTrace.NowUs(), 1), dropped, Capacity, records);
        }
    }

    /// <summary>Serializes <see cref="Drain"/> to camelCase JSON via the source-gen context.</summary>
    public static string DrainJson() => JsonSerializer.Serialize(Drain(), LogJsonContext.Default.LogDrain);

    /// <summary>A non-draining snapshot of ring occupancy and drop/record counters (for <c>log~stats</c>).</summary>
    public static LogStats Stats()
    {
        lock (Gate)
        {
            return new LogStats(Capacity, _count, _droppedSinceDrain, _recordedTotal, _droppedTotal);
        }
    }

    /// <summary>Serializes <see cref="Stats"/> to camelCase JSON via the source-gen context.</summary>
    public static string StatsJson() => JsonSerializer.Serialize(Stats(), LogJsonContext.Default.LogStats);
}

/// <summary>
/// A minimal, AOT-safe <see cref="ILogger"/> that writes formatted records into <see cref="EngineLog"/>'s
/// ring. Hand-rolled over <c>Microsoft.Extensions.Logging.Abstractions</c> so the whole logging leg needs
/// no concrete <c>Microsoft.Extensions.Logging</c> (DI/Options/filtering) package — the <c>[LoggerMessage]</c>
/// events only need an <see cref="ILogger"/>, and this keeps the NativeAOT surface tiny and reflection-free.
/// </summary>
internal sealed class RingLogger(string category, Action<LogRecord> sink, LogLevel minLevel) : ILogger
{
    public IDisposable? BeginScope<TState>(TState state) where TState : notnull => NullScope.Instance;

    public bool IsEnabled(LogLevel logLevel) => logLevel >= minLevel && logLevel != LogLevel.None;

    public void Log<TState>(
        LogLevel logLevel, EventId eventId, TState state, Exception? exception,
        Func<TState, Exception?, string> formatter)
    {
        if (!IsEnabled(logLevel))
        {
            return;
        }

        var message = formatter(state, exception);
        var ex = exception is null ? null : $"{exception.GetType().Name}: {exception.Message}";
        sink(new LogRecord(logLevel.ToString(), category, message, Math.Round(EngineTrace.NowUs(), 1), null, ex));
    }

    // Scopes aren't captured into the flat ring payload (the POC has no scoped correlation yet), so
    // BeginScope returns a shared no-op rather than allocating per call.
    private sealed class NullScope : IDisposable
    {
        public static readonly NullScope Instance = new();
        public void Dispose() { }
    }
}

/// <summary>Source-generated JSON metadata for the log payloads (AOT-safe, no reflection).</summary>
[JsonSourceGenerationOptions(
    PropertyNamingPolicy = JsonKnownNamingPolicy.CamelCase,
    DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull)]
[JsonSerializable(typeof(LogDrain))]
[JsonSerializable(typeof(LogStats))]
internal sealed partial class LogJsonContext : JsonSerializerContext;
