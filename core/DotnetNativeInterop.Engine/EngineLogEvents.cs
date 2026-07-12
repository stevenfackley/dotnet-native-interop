using Microsoft.Extensions.Logging;

namespace DotnetNativeInterop.Engine;

/// <summary>
/// Source-generated (<c>[LoggerMessage]</c>) structured log events for the engine's diagnostic paths.
/// The generator emits reflection-free, zero-allocation logging code (each partial method compiles to a
/// direct <see cref="ILogger.Log{TState}"/> call that early-outs when the level is disabled), so these are
/// AOT-clean. They are the events the FFI boundary raises INSTEAD of silently swallowing a caught
/// exception, so a client draining <c>dni_log_drain</c> can finally see why an operation failed.
///
/// Kept in the Engine (not NativeBridge) so every transport and the RAG/agent pipelines share them, and
/// so the pure domain owns its own diagnostics vocabulary. Log through <see cref="EngineLog.Logger"/>.
/// </summary>
public static partial class EngineLogEvents
{
    /// <summary>The engine finished <c>dni_initialize</c> — a happy-path marker so a clean run's log
    /// stream is non-empty, not only errors.</summary>
    [LoggerMessage(EventId = 1, Level = LogLevel.Information, Message = "Engine initialized")]
    public static partial void EngineInitialized(ILogger logger);

    /// <summary>A background token/fragment drain ended by throwing (typically a cancelled session or a
    /// faulted channel). The FFI <c>DrainAsync</c> used to swallow this with a bare <c>catch</c>; now the
    /// client can see a turn ended abnormally (no <c>is_final</c> was the only prior signal).</summary>
    [LoggerMessage(EventId = 2, Level = LogLevel.Warning,
        Message = "Session {sessionId} token drain ended abnormally (cancelled, or the channel faulted)")]
    public static partial void FfiDrainAborted(ILogger logger, long sessionId, Exception exception);

    /// <summary>An <c>[UnmanagedCallersOnly]</c> export caught an exception and returned an error status.
    /// The status code alone tells the client "internal error" with no detail; this records what it was.</summary>
    [LoggerMessage(EventId = 3, Level = LogLevel.Error, Message = "Export {export} failed; returning an error status")]
    public static partial void ExportFailed(ILogger logger, string export, Exception exception);
}
