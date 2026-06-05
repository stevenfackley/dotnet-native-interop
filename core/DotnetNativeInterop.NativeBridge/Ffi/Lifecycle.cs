using System.Runtime.InteropServices;

namespace DotnetNativeInterop.NativeBridge.Ffi;

/// <summary>FFI lifecycle exports: initialize and shutdown the engine.</summary>
internal static class Lifecycle
{
    /// <summary>
    /// Initializes the engine. Idempotent. Returns <see cref="NativeStatus.Ok"/> (0) or a
    /// negative status on failure.
    /// </summary>
    [UnmanagedCallersOnly(EntryPoint = "dni_initialize")]
    public static int Initialize()
    {
        try
        {
            EngineHost.Initialize();
            return NativeStatus.Ok;
        }
        catch (Exception)
        {
            return NativeStatus.Internal;
        }
    }

    /// <summary>
    /// Shuts down the engine: cancels and disposes every live session via
    /// <see cref="SessionRegistry"/>, then returns. HTTP/gRPC/broker servers are
    /// stopped by their own exports.
    /// </summary>
    [UnmanagedCallersOnly(EntryPoint = "dni_shutdown")]
    public static void Shutdown()
    {
        try
        {
            // Drain the registry. RemoveAsync is a ValueTask; block synchronously here
            // because UnmanagedCallersOnly must be void and we cannot await across the ABI.
            // Each DisposeAsync issues CancelAsync then awaits the pump — expected to complete
            // quickly because the active model (FeatureShowcaseModel) honours cancellation.
            // Snapshot FFI-owned ids and dispose them. RemoveAsync returns a ValueTask, which
            // must be consumed exactly once — convert to Task before collecting (CA2012).
            var pending = new List<Task<bool>>();
            foreach (var id in FfiState.AllocatedIds.Keys)
            {
                pending.Add(SessionRegistry.RemoveAsync(id).AsTask());
            }

            FfiState.AllocatedIds.Clear();

            // Block synchronously: UnmanagedCallersOnly must be void; we cannot await the ABI.
            Task.WhenAll(pending).GetAwaiter().GetResult();
        }
        catch (Exception)
        {
            // Never let managed exceptions escape across the ABI.
        }
    }
}
