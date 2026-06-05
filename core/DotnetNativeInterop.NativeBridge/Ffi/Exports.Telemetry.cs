using System.Runtime.InteropServices;
using DotnetNativeInterop.Engine;

namespace DotnetNativeInterop.NativeBridge.Ffi;

/// <summary>
/// Engine-introspection export (FFI): returns a JSON snapshot of live runtime stats. Heap UTF-8;
/// the caller copies the text then releases it with the existing <c>dni_string_free</c>.
/// </summary>
internal static class ExportsTelemetry
{
    /// <summary>Returns live engine stats as JSON. 0 on failure.</summary>
    [UnmanagedCallersOnly(EntryPoint = "dni_engine_stats")]
    public static nint EngineStats()
    {
        try
        {
            return NativeText.Allocate(EngineTelemetry.SnapshotJson());
        }
        catch (Exception)
        {
            return 0;
        }
    }
}
