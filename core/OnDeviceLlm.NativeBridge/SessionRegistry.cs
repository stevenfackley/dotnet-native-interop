using System.Collections.Concurrent;
using OnDeviceLlm.Engine;

namespace OnDeviceLlm.NativeBridge;

/// <summary>
/// Maps opaque integer handles handed to native callers onto live <see cref="InferenceSession"/>s.
/// Native code holds only a <see cref="long"/>; the managed object never leaves this process.
/// </summary>
public static class SessionRegistry
{
    private static readonly ConcurrentDictionary<long, InferenceSession> Sessions = new();
    private static long _next;

    /// <summary>Registers a session and returns its handle (always &gt; 0).</summary>
    public static long Add(InferenceSession session)
    {
        var id = Interlocked.Increment(ref _next);
        Sessions[id] = session;
        return id;
    }

    /// <summary>Looks up a live session by handle.</summary>
    public static bool TryGet(long id, out InferenceSession session) => Sessions.TryGetValue(id, out session!);

    /// <summary>Removes and disposes a session. Returns false if the handle was unknown.</summary>
    public static async ValueTask<bool> RemoveAsync(long id)
    {
        if (!Sessions.TryRemove(id, out var session))
        {
            return false;
        }

        await session.DisposeAsync().ConfigureAwait(false);
        return true;
    }
}
