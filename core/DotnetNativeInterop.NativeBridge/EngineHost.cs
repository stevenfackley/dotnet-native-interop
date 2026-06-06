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
                return new RagLanguageModel(new LlamaLanguageModel(gguf), SemanticSearch.Default);
            }
        }
        catch (Exception)
        {
            // Fall through to the managed generator.
        }

        return new ExtractiveLanguageModel();
    }
}
