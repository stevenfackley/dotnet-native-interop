#nullable enable

using System.Runtime.InteropServices;

namespace OnDeviceLlm.NativeBridge.Grpc;

/// <summary>
/// NativeAOT C ABI exports for Pattern 2 (gRPC over Unix Domain Socket).
/// Both symbols match the declarations in <c>abi/ondevicellm.h</c> exactly.
/// These are the only two points where managed code is entered from native code
/// for this transport; all exceptions are caught here — nothing escapes into native.
/// </summary>
internal static unsafe class GrpcExports
{
    /// <summary>
    /// Starts the gRPC/UDS server.
    /// </summary>
    /// <param name="socketPath">
    /// NUL-terminated UTF-8 string (owned by the caller) giving the absolute path
    /// of the Unix Domain Socket to create inside the app sandbox.
    /// </param>
    /// <returns>
    /// <c>ONDEVICELLM_OK (0)</c> on success;
    /// <c>ONDEVICELLM_INVALID_ARGUMENT (-2)</c> for a null or empty path;
    /// <c>ONDEVICELLM_INTERNAL (-5)</c> on any unexpected failure.
    /// </returns>
    [UnmanagedCallersOnly(EntryPoint = "ondevicellm_grpc_start")]
    public static int Start(byte* socketPath)
    {
        try
        {
            var path = NativeText.Read((nint)socketPath);
            return GrpcHost.Start(path);
        }
        catch
        {
            return NativeStatus.Internal;
        }
    }

    /// <summary>
    /// Stops the gRPC/UDS server. Idempotent.
    /// </summary>
    /// <returns>
    /// <c>ONDEVICELLM_OK (0)</c> on success or if the server was not running;
    /// <c>ONDEVICELLM_INTERNAL (-5)</c> on any unexpected failure.
    /// </returns>
    [UnmanagedCallersOnly(EntryPoint = "ondevicellm_grpc_stop")]
    public static int Stop()
    {
        try
        {
            return GrpcHost.Stop();
        }
        catch
        {
            return NativeStatus.Internal;
        }
    }
}
