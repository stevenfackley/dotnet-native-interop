using System.Diagnostics;
using System.Text;

namespace DotnetNativeInterop.Engine;

/// <summary>Result of the FFI echo round-trip (boundary showcase).</summary>
/// <remarks><c>PtrIn</c> is filled by the export layer (the native input pointer); the engine leaves it empty.</remarks>
public sealed record BoundaryEcho(
    string BytesHex, int Len, string Decoded, long ManagedThreadId, double ExecuteUs, string PtrIn = "");

/// <summary>Describes a managed exception contained at the FFI boundary.</summary>
public sealed record BoundaryThrow(bool Caught, string Type, string Message, int Status);

/// <summary>
/// Plain managed logic behind the FFI Boundary-showcase exports. Lives here (not inside the
/// [UnmanagedCallersOnly] exports, which are only callable via function pointer from native) so the
/// behavior is unit-testable from a managed probe.
/// </summary>
public static class BoundaryDiagnostics
{
    /// <summary>Echoes UTF-8 input: hex, decoded text, the managed thread id, and managed execute time.</summary>
    public static BoundaryEcho Echo(ReadOnlySpan<byte> utf8)
    {
        var sw = Stopwatch.StartNew();
        long threadId = Environment.CurrentManagedThreadId;
        string decoded = Encoding.UTF8.GetString(utf8);
        // Convert.ToHexString: .NET 5+. Pre-.NET 5 alternative: BitConverter.ToString(utf8.ToArray()).Replace("-", "").
        string hex = Convert.ToHexString(utf8);
        sw.Stop();
        // Stopwatch.Elapsed.TotalMicroseconds: .NET 7+. Pre-.NET 7 alternative:
        // sw.ElapsedTicks * (1_000_000.0 / Stopwatch.Frequency).
        double executeUs = sw.Elapsed.TotalMicroseconds;
        return new BoundaryEcho(hex, utf8.Length, decoded, threadId, executeUs);
    }

    /// <summary>Throws on purpose so the export's catch can demonstrate boundary containment.</summary>
    public static void Throw() =>
        throw new InvalidOperationException("Boundary demo: managed exception crossing prevented.");

    /// <summary>Describes a contained exception for the native side. status mirrors NativeStatus.Internal (-5).</summary>
    public static BoundaryThrow Contain(Exception ex) =>
        new(true, ex.GetType().FullName ?? ex.GetType().Name, ex.Message, -5);
}
