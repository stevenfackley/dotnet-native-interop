using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;

namespace OnDeviceLlm.NativeBridge.Sqlite;

/// <summary>
/// C ABI exports for Pattern 4 — SQLite WAL broker.
/// Entry points match <c>abi/ondevicellm.h</c> exactly.
/// </summary>
internal static class SqliteExports
{
    // One broker instance per process; guarded by a lock so concurrent Start calls
    // from a buggy host are safe rather than undefined.
    private static SqliteBroker? _broker;
    private static readonly object Gate = new();

    /// <summary>
    /// Initialises the engine (idempotent), opens or creates the SQLite database at
    /// <paramref name="dbPath"/>, enables WAL, ensures the schema, and starts the
    /// background poll loop.
    /// </summary>
    /// <returns>
    /// <see cref="NativeStatus.Ok"/> (0) on success, or a negative
    /// <see cref="NativeStatus"/> code on failure.
    /// </returns>
    [UnmanagedCallersOnly(EntryPoint = "ondevicellm_broker_start")]
    public static unsafe int Start(byte* dbPath)
    {
        try
        {
            var path = NativeText.Read((nint)dbPath);
            if (string.IsNullOrWhiteSpace(path))
            {
                return NativeStatus.InvalidArgument;
            }

            lock (Gate)
            {
                if (_broker is not null)
                {
                    return NativeStatus.AlreadyRunning;
                }

                EngineHost.Initialize();

                var broker = new SqliteBroker(path);
                broker.Start();
                _broker = broker;
            }

            return NativeStatus.Ok;
        }
        catch (Exception)
        {
            return NativeStatus.Internal;
        }
    }

    /// <summary>
    /// Cancels the poll loop, waits for any in-flight inference to reach a
    /// terminal state, then disposes the database connection.
    /// </summary>
    [UnmanagedCallersOnly(EntryPoint = "ondevicellm_broker_stop")]
    public static int Stop()
    {
        try
        {
            SqliteBroker? broker;
            lock (Gate)
            {
                broker = _broker;
                _broker = null;
            }

            if (broker is null)
            {
                return NativeStatus.Ok; // Already stopped — idempotent.
            }

            broker.Stop();
            broker.Dispose();

            return NativeStatus.Ok;
        }
        catch (Exception)
        {
            return NativeStatus.Internal;
        }
    }
}
