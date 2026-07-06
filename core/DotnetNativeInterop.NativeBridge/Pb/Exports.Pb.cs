using System.Runtime.InteropServices;
using DotnetNativeInterop.NativeBridge.Pb;

namespace DotnetNativeInterop.NativeBridge.Ffi;

/// <summary>
/// Framed-protobuf transport exports (Wave B, the 4th transport) — raw <see cref="System.Net.Sockets"/>,
/// no ASP.NET/gRPC. Same exception-containment discipline as every other export: no managed exception
/// crosses the ABI.
/// </summary>
internal static class ExportsPb
{
    /// <summary>
    /// Starts the loopback framed-protobuf server and returns the bound 127.0.0.1 port (&gt; 0), or a
    /// negative <see cref="NativeStatus"/> on failure. <paramref name="flags"/> bit 0 requires the PQ
    /// handshake on every connection.
    /// </summary>
    [UnmanagedCallersOnly(EntryPoint = "dni_pb_start")]
    public static int Start(int flags)
    {
        try
        {
            return PbFrameServer.Start(flags);
        }
        catch (Exception)
        {
            return NativeStatus.Internal;
        }
    }

    /// <summary>Stops the framed-protobuf server. Idempotent.</summary>
    [UnmanagedCallersOnly(EntryPoint = "dni_pb_stop")]
    public static void Stop()
    {
        try
        {
            PbFrameServer.Stop();
        }
        catch (Exception)
        {
            // Never let a managed exception cross the ABI.
        }
    }
}
