using System.Collections.Concurrent;

namespace DotnetNativeInterop.NativeBridge.Ffi;

/// <summary>
/// Process-wide set of session ids allocated through the FFI exports. Kept separately
/// because <see cref="SessionRegistry"/> is shared with other transports and does not
/// expose an enumeration API. Used by <see cref="Lifecycle.Shutdown"/> to cancel all
/// FFI-owned sessions.
/// </summary>
internal static class FfiState
{
    /// <summary>Live session ids issued by <c>dni_session_start</c>.</summary>
    internal static readonly ConcurrentDictionary<long, byte> AllocatedIds = new();
}
