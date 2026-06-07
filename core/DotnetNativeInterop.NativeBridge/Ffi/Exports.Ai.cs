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

    /// <summary>
    /// Points the engine at an on-device assets dir (model.onnx/vocab.txt/corpus.txt/manuals) and enables
    /// the Android NNAPI EP. Call before the first dni_search/RAG. Returns 0 on success, -1 on a bad path.
    /// </summary>
    [UnmanagedCallersOnly(EntryPoint = "dni_set_assets_dir")]
    public static unsafe int SetAssetsDir(byte* path)
    {
        try
        {
            var dir = NativeText.Read((nint)path);
            if (string.IsNullOrEmpty(dir) || !System.IO.File.Exists(System.IO.Path.Combine(dir, "vocab.txt")))
            {
                return -1;
            }

            SemanticSearch.SetAssetsDirOverride(dir);
            OnnxRuntimeConfig.UseNnapi = true;
            return 0;
        }
        catch (Exception)
        {
            return -1;
        }
    }
}
