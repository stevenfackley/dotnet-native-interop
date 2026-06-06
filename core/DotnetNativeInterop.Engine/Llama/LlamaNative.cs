using System.Runtime.InteropServices;

namespace DotnetNativeInterop.Engine.Llama;

/// <summary>P/Invoke surface for the <c>dni_llama</c> C shim (static-linked into the NativeAOT image
/// on mobile; resolved from the native lib on the host probe). Names match <c>native/llama-shim/dni_llama.h</c>.</summary>
internal static unsafe partial class LlamaNative
{
    [LibraryImport("dni_llama")]
    internal static partial nint dni_llama_load([MarshalAs(UnmanagedType.LPUTF8Str)] string ggufPath);

    [LibraryImport("dni_llama")]
    internal static partial int dni_llama_generate(
        nint handle,
        [MarshalAs(UnmanagedType.LPUTF8Str)] string prompt,
        int maxTokens,
        float temp,
        delegate* unmanaged[Cdecl]<void*, byte*, void> callback,
        void* userData);

    [LibraryImport("dni_llama")]
    internal static partial void dni_llama_free(nint handle);
}
