using System.Runtime.InteropServices;

namespace DotnetNativeInterop.NativeBridge;

/// <summary>Stable integer status codes returned across the C ABI (mirrored in <c>abi/dni.h</c>).</summary>
public static class NativeStatus
{
    public const int Ok = 0;
    public const int NotInitialized = -1;
    public const int InvalidArgument = -2;
    public const int UnknownSession = -3;
    public const int AlreadyRunning = -4;
    public const int Internal = -5;
}

/// <summary>UTF-8 marshalling helpers shared by every export.</summary>
public static class NativeText
{
    /// <summary>Reads a NUL-terminated UTF-8 string from native memory (null-safe).</summary>
    public static string Read(nint utf8) =>
        utf8 == 0 ? string.Empty : Marshal.PtrToStringUTF8(utf8) ?? string.Empty;

    /// <summary>Allocates a NUL-terminated UTF-8 copy in native memory; free with <see cref="Free"/>.</summary>
    public static nint Allocate(string value) => Marshal.StringToCoTaskMemUTF8(value);

    /// <summary>Frees memory returned by <see cref="Allocate"/>.</summary>
    public static void Free(nint handle)
    {
        if (handle != 0)
        {
            Marshal.FreeCoTaskMem(handle);
        }
    }
}
