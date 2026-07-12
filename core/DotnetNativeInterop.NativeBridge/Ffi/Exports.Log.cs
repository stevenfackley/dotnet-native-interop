using System.Runtime.InteropServices;
using DotnetNativeInterop.Engine;

namespace DotnetNativeInterop.NativeBridge.Ffi;

/// <summary>
/// Boundary-logging export (the logging leg of the observability trio, alongside <c>dni_trace_drain</c>):
/// drains the engine's bounded log ring as JSON. The payload carries every record captured since the
/// previous drain, the engine's <c>nowUs</c> (one boot-offset per drain for client clock alignment, on the
/// same clock as spans), and the count dropped by ring overflow (disclosed, never silent). This is where
/// the errors the FFI boundary would otherwise swallow (see <c>ExportsFfi.DrainAsync</c>) surface.
/// Heap UTF-8; the caller copies the text then releases it with the existing <c>dni_string_free</c>.
/// </summary>
internal static class ExportsLog
{
    /// <summary>Drains accumulated log records as JSON. 0 on failure.</summary>
    [UnmanagedCallersOnly(EntryPoint = "dni_log_drain")]
    public static nint LogDrain()
    {
        try
        {
            return NativeText.Allocate(EngineLog.DrainJson());
        }
        catch (Exception)
        {
            return 0;
        }
    }
}
