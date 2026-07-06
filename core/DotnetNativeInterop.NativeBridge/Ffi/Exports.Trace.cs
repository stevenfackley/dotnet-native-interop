using System.Runtime.InteropServices;
using DotnetNativeInterop.Engine;

namespace DotnetNativeInterop.NativeBridge.Ffi;

/// <summary>
/// Boundary-tracing export (Wave B): drains the engine's bounded span ring as JSON. The payload carries
/// every span recorded since the previous drain, the engine's <c>nowUs</c> (one boot-offset per drain for
/// client clock alignment), and the count of spans dropped by ring overflow (disclosed, never silent).
/// Heap UTF-8; the caller copies the text then releases it with the existing <c>dni_string_free</c>.
/// </summary>
internal static class ExportsTrace
{
    /// <summary>Drains accumulated spans as JSON. 0 on failure.</summary>
    [UnmanagedCallersOnly(EntryPoint = "dni_trace_drain")]
    public static nint TraceDrain()
    {
        try
        {
            return NativeText.Allocate(EngineTrace.DrainJson());
        }
        catch (Exception)
        {
            return 0;
        }
    }
}
