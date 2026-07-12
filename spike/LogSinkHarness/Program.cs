// EngineLog proof harness — the logging leg of the observability trio (tracing + metrics + LOGGING).
// Exercises EngineLog + the [LoggerMessage] EngineLogEvents directly (no NativeBridge, no transport):
// event capture with level/message/exception fidelity, the Information+ level filter, drop-oldest ring
// overflow disclosure, drain-clears-the-ring, log~stats counters, and camelCase/omit-null JSON. Every
// check prints PASS/FAIL; a non-zero exit means at least one failed. Also the win-x64 NativeAOT gate for
// Microsoft.Extensions.Logging.Abstractions + its source generator (see the .csproj comment).
using System.Text.Json;
using DotnetNativeInterop.Engine;
using Microsoft.Extensions.Logging;

internal static class Program
{
    private static readonly List<(string Name, bool Ok)> Results = [];

    private static int Main()
    {
        Console.WriteLine("== EngineLog proof harness ==");

        try
        {
            RunEventCaptureAndFidelity();
            RunLevelFilter();
            RunDrainClearsRing();
            RunOverflowDropsOldestAndDiscloses();
            RunStatsCounters();
            RunJsonShape();
        }
        catch (Exception ex)
        {
            Console.WriteLine($"[FAIL] harness aborted: {ex.GetType().Name}: {ex.Message}");
            Results.Add(("harness completed", false));
        }

        Console.WriteLine();
        var passed = Results.Count(r => r.Ok);
        Console.WriteLine($"== {passed}/{Results.Count} checks passed ==");
        return Results.All(r => r.Ok) ? 0 : 1;
    }

    // The three shipped [LoggerMessage] events must land in the ring with the right level, category,
    // rendered message, and (where present) the exception's "Type: message" — the detail the FFI boundary
    // used to swallow.
    private static void RunEventCaptureAndFidelity()
    {
        EngineLog.Drain(); // clean slate

        EngineLogEvents.EngineInitialized(EngineLog.Logger);
        EngineLogEvents.FfiDrainAborted(EngineLog.Logger, sessionId: 42, new OperationCanceledException("cancelled"));
        EngineLogEvents.ExportFailed(EngineLog.Logger, "dni_agent_session_start", new InvalidOperationException("boom"));

        var records = EngineLog.Drain().Records;
        Check("event capture: all three [LoggerMessage] events landed in the ring", records.Count == 3);

        var info = records[0];
        Check("info event: level is Information", info.Level == "Information");
        Check("info event: category is Dni.Engine", info.Category == "Dni.Engine");
        Check("info event: message rendered", info.Message == "Engine initialized");
        Check("info event: no exception", info.Exception is null);

        var warn = records[1];
        Check("warning event: level is Warning", warn.Level == "Warning");
        Check("warning event: structured {sessionId} rendered into the message", warn.Message.Contains("42", StringComparison.Ordinal));
        Check("warning event: exception carries type + message (the detail the boundary used to swallow)",
            warn.Exception == "OperationCanceledException: cancelled");

        var error = records[2];
        Check("error event: level is Error", error.Level == "Error");
        Check("error event: structured {export} rendered", error.Message.Contains("dni_agent_session_start", StringComparison.Ordinal));
        Check("error event: exception carries type + message",
            error.Exception == "InvalidOperationException: boom");

        Check("timestamps are engine µs-since-boot (> 0, shared with the span clock)",
            records.All(r => r.TimestampUs > 0));
    }

    // Information and above only: a Debug/Trace record must be dropped at the logger, never reaching the
    // ring — otherwise high-frequency debug chatter would evict the Warnings/Errors this leg exists for.
    private static void RunLevelFilter()
    {
        EngineLog.Drain();

        EngineLog.Logger.LogTrace("trace noise");
        EngineLog.Logger.LogDebug("debug noise");
        EngineLog.Logger.LogInformation("kept");

        var records = EngineLog.Drain().Records;
        Check("level filter: Trace/Debug are dropped, Information is kept", records.Count == 1 && records[0].Level == "Information");
        Check("EngineLog.MinLevel is Information", EngineLog.MinLevel == LogLevel.Information);
    }

    private static void RunDrainClearsRing()
    {
        EngineLog.Drain();
        EngineLog.Logger.LogWarning("one");
        Check("drain returns the pending record", EngineLog.Drain().Records.Count == 1);
        Check("a second immediate drain is empty (drain clears the ring)", EngineLog.Drain().Records.Count == 0);
    }

    // Overflow past Capacity drops the OLDEST and DISCLOSES the count — never silent, never unbounded.
    private static void RunOverflowDropsOldestAndDiscloses()
    {
        EngineLog.Drain();

        var overBy = 10;
        for (var i = 0; i < EngineLog.Capacity + overBy; i++)
        {
            // Distinct message per record so we can prove WHICH were dropped.
            EngineLog.Logger.LogInformation("rec {n}", i);
        }

        var drain = EngineLog.Drain();
        Check("overflow: occupancy is capped at Capacity", drain.Records.Count == EngineLog.Capacity);
        Check("overflow: the drop count is disclosed (== the overflow amount)", drain.Dropped == overBy);
        Check("overflow: capacity is reported", drain.Capacity == EngineLog.Capacity);
        // drop-OLDEST: records 0..overBy-1 are gone; the newest (Capacity+overBy-1) is present, the oldest
        // surviving is record #overBy.
        Check("overflow: drop-OLDEST — the newest record survived",
            drain.Records[^1].Message.Contains($"rec {EngineLog.Capacity + overBy - 1}", StringComparison.Ordinal));
        Check("overflow: drop-OLDEST — the oldest survivor is record #overBy, not #0",
            drain.Records[0].Message.Contains($"rec {overBy}", StringComparison.Ordinal));
    }

    private static void RunStatsCounters()
    {
        EngineLog.Drain();
        var before = EngineLog.Stats();

        EngineLog.Logger.LogError("counted");
        var afterLog = EngineLog.Stats();
        Check("stats: recordedTotal increments and occupancy reflects the pending record",
            afterLog.RecordedTotal == before.RecordedTotal + 1 && afterLog.Occupancy == 1);

        EngineLog.Drain();
        var afterDrain = EngineLog.Stats();
        Check("stats: occupancy returns to 0 after a drain; recordedTotal is cumulative (never reset by a read)",
            afterDrain.Occupancy == 0 && afterDrain.RecordedTotal == afterLog.RecordedTotal);
        Check("stats: capacity is reported", afterDrain.Capacity == EngineLog.Capacity);
    }

    // The dni_log_drain wire shape: camelCase keys, null requestId/exception OMITTED, exception INCLUDED
    // when present.
    private static void RunJsonShape()
    {
        EngineLog.Drain();
        EngineLog.Logger.LogInformation("plain");
        EngineLogEvents.ExportFailed(EngineLog.Logger, "dni_log_drain", new Exception("x"));
        var json = EngineLog.DrainJson();
        Console.WriteLine($"dni_log_drain JSON: {json}");

        using var doc = JsonDocument.Parse(json);
        var root = doc.RootElement;
        Check("json: camelCase top-level keys (nowUs/dropped/capacity/records)",
            root.TryGetProperty("nowUs", out _) && root.TryGetProperty("records", out _));

        var records = root.GetProperty("records");
        var plain = records[0];
        Check("json: a record with no exception OMITS the exception key (WhenWritingNull)",
            !plain.TryGetProperty("exception", out _));
        Check("json: a record with no requestId OMITS the requestId key",
            !plain.TryGetProperty("requestId", out _));

        var withEx = records[1];
        Check("json: a record WITH an exception includes it", withEx.TryGetProperty("exception", out var exVal)
            && exVal.GetString() == "Exception: x");
        Check("json: level/category/message present and camelCase",
            plain.GetProperty("level").GetString() == "Information"
            && plain.GetProperty("category").GetString() == "Dni.Engine"
            && plain.GetProperty("message").GetString() == "plain");
    }

    private static void Check(string name, bool ok)
    {
        Results.Add((name, ok));
        Console.WriteLine($"[{(ok ? "PASS" : "FAIL")}] {name}");
    }
}
