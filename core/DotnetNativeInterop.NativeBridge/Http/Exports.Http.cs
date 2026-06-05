using System.Runtime.InteropServices;

namespace DotnetNativeInterop.NativeBridge.Http;

/// <summary>
/// C ABI exports for Pattern 1 (HTTP loopback). Entry-point names match
/// <c>abi/dni.h</c> exactly and must never be renamed.
/// </summary>
internal static class HttpExports
{
    /// <summary>
    /// Starts the Kestrel loopback server and returns the bound port (&gt; 0).
    /// Returns a negative <see cref="NativeStatus"/> code on failure.
    /// <para>
    /// Idempotent: if the server is already running the existing port is returned.
    /// </para>
    /// <para>
    /// iOS note: the listener is killed when the OS suspends the app into the background.
    /// The Swift host MUST call this export again on every foreground-resume to obtain
    /// a valid (possibly new) port before issuing requests.
    /// </para>
    /// </summary>
    [UnmanagedCallersOnly(EntryPoint = "dni_http_start")]
    public static int Start()
    {
        try
        {
            // Block synchronously inside the export. Spinning a fresh Task and
            // blocking the calling native thread is deliberate: native callers
            // cannot await, and the Kestrel startup path is short (~ms).
            return HttpHost.StartAsync().GetAwaiter().GetResult();
        }
        catch (Exception)
        {
            return NativeStatus.Internal;
        }
    }

    /// <summary>
    /// Stops the loopback server. Returns <see cref="NativeStatus.Ok"/> (0) on success
    /// or <see cref="NativeStatus.Internal"/> (-5) if an unexpected error occurs.
    /// Safe to call when the server is not running.
    /// </summary>
    [UnmanagedCallersOnly(EntryPoint = "dni_http_stop")]
    public static int Stop()
    {
        try
        {
            HttpHost.StopAsync().GetAwaiter().GetResult();
            return NativeStatus.Ok;
        }
        catch (Exception)
        {
            return NativeStatus.Internal;
        }
    }
}
