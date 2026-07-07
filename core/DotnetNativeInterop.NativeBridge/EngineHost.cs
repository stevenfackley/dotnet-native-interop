using DotnetNativeInterop.Engine;
using DotnetNativeInterop.Engine.Llama;

namespace DotnetNativeInterop.NativeBridge;

/// <summary>
/// Process-wide singleton wiring the shared <see cref="InferenceOrchestrator"/> that every built
/// interop transport (FFI, raw-HTTP, SQLite — gRPC is excluded from the build) drives. Initialized
/// once via <see cref="Initialize"/>.
/// </summary>
public static class EngineHost
{
    private static readonly object Gate = new();
    private static InferenceOrchestrator? _orchestrator;
    private static InferenceOrchestrator? _ragOrchestrator;

    // Retained handle on the RAG model when it's the llama.cpp backend (which owns a ~0.77 GB native
    // model handle). InferenceOrchestrator doesn't expose its inner model, so we keep this reference
    // ourselves purely so Reset() can dispose it. Null when the RAG model is the managed
    // ExtractiveLanguageModel (nothing to free). Concrete type (not IDisposable) to satisfy CA1859,
    // which is warning-as-error here; LlamaLanguageModel is IDisposable so Dispose() still frees it.
    private static LlamaLanguageModel? _ragModelDisposable;

    /// <summary>The shared orchestrator. Throws if <see cref="Initialize"/> has not run.</summary>
    public static InferenceOrchestrator Orchestrator =>
        _orchestrator ?? throw new InvalidOperationException("EngineHost.Initialize() has not been called.");

    /// <summary>The shared RAG orchestrator (manuals retrieval + grounded generation).</summary>
    public static InferenceOrchestrator RagOrchestrator =>
        _ragOrchestrator ?? throw new InvalidOperationException("EngineHost.Initialize() has not been called.");

    /// <summary>True once the engine is ready.</summary>
    public static bool IsInitialized => _orchestrator is not null && _ragOrchestrator is not null;

    /// <summary>
    /// Idempotently builds the engine. This is the single seam where the offline
    /// <see cref="MockLanguageModel"/> is swapped for a real backend (e.g. llama.cpp via P/Invoke).
    /// </summary>
    public static void Initialize()
    {
        if (_orchestrator is not null && _ragOrchestrator is not null)
        {
            return;
        }

        lock (Gate)
        {
            // Payload is the C# 14 / .NET 10 language-feature showcase (no LLM).
            _orchestrator ??= new InferenceOrchestrator(new FeatureShowcaseModel());
            _ragOrchestrator ??= new InferenceOrchestrator(BuildRagModel());
        }
    }

    /// <summary>
    /// Clears the cached orchestrators so the next <see cref="Initialize"/> re-resolves
    /// <see cref="BuildRagModel"/> from scratch instead of returning the stale <c>??=</c>-cached
    /// instance. Needed because a GGUF can land on disk (Android's download-on-first-run) AFTER
    /// this process already resolved the RAG model to the extractive fallback — without a reset,
    /// the cache would keep serving extraction forever even though a real model is now present.
    /// Called from <see cref="Ffi.Lifecycle.Shutdown"/> so the existing dni_shutdown -&gt;
    /// dni_initialize sequence (no new export, no ABI change) is enough for a caller to pick up a
    /// freshly-downloaded model. Disposes the outgoing RAG model first (freeing the llama.cpp native
    /// handle when that was the backend — see <see cref="_ragModelDisposable"/>); ordering is safe
    /// because <see cref="Ffi.Lifecycle.Shutdown"/> drains the live FFI sessions before calling here,
    /// so no in-flight generation is still holding the handle. A reusable lifecycle seam (iOS parity
    /// drives the same path), so the disposal is not merely for today's one-shot Android flow.
    /// </summary>
    internal static void Reset()
    {
        lock (Gate)
        {
            _ragModelDisposable?.Dispose();
            _ragModelDisposable = null;
            _orchestrator = null;
            _ragOrchestrator = null;
        }
    }

    // The RAG generator: the llama.cpp model when its GGUF is bundled, otherwise the always-available
    // managed extractive generator — so a missing or unloadable model degrades gracefully rather than
    // breaking the feature. Swapping these two is the entire llama.cpp integration seam.
    private static ILanguageModel BuildRagModel()
    {
        try
        {
            var gguf = Path.Combine(SemanticSearch.ResolveAssetsDir(), "Llama-3.2-1B-Instruct-Q4_K_M.gguf");
            if (File.Exists(gguf))
            {
                // Retain the llama model so Reset() can dispose its native handle (LlamaLanguageModel
                // .Dispose() calls dni_llama_free). RagLanguageModel only wraps it for retrieval +
                // prompting and does not own its lifetime.
                var llama = new LlamaLanguageModel(gguf);
                _ragModelDisposable = llama;
                return new RagLanguageModel(llama, SemanticSearch.Default);
            }
        }
        catch (Exception)
        {
            // Fall through to the managed generator.
        }

        return new ExtractiveLanguageModel();
    }
}
