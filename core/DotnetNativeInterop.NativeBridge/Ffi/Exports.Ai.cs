using System.Runtime.InteropServices;
using DotnetNativeInterop.Engine;

namespace DotnetNativeInterop.NativeBridge.Ffi;

/// <summary>Semantic-search export (FFI): ranks a corpus by cosine similarity to a free-text query.</summary>
internal static class ExportsAi
{
    /// <summary>Ranks corpus ("features"|"facts") by similarity to query; JSON [{text,score}]. 0 on failure.</summary>
    [UnmanagedCallersOnly(EntryPoint = "dni_search")]
    public static unsafe nint Search(byte* query, byte* corpus)
    {
        try
        {
            var q = NativeText.Read((nint)query);
            var c = NativeText.Read((nint)corpus);
            return NativeText.Allocate(SemanticSearch.SearchJson(q, c, 5));
        }
        catch (Exception)
        {
            return 0;
        }
    }
}
