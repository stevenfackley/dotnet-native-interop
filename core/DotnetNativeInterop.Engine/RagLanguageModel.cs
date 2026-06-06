using System.Runtime.CompilerServices;

namespace DotnetNativeInterop.Engine;

/// <summary>
/// Retrieval-augmented generation: retrieves the top manuals passages for the request, assembles a
/// grounded prompt via <see cref="RagPrompt"/>, and delegates token generation to an inner raw text
/// model (the llama.cpp-backed <c>LlamaLanguageModel</c> in production). The retrieval +
/// prompting live here; the inner model only completes text. Swapping the inner model is the only
/// difference between a mock run and a real on-device LLM.
/// </summary>
public sealed class RagLanguageModel(
    ILanguageModel inner,
    SemanticSearch? search = null,
    int topK = 3) : ILanguageModel
{
    /// <inheritdoc/>
    public IAsyncEnumerable<string> GenerateAsync(
        InferenceRequest request,
        CancellationToken cancellationToken = default)
    {
        var engine = search ?? SemanticSearch.Default;       // lazy: built on first use
        var hits = engine.Search(request.Prompt, "manuals", topK);
        var grounded = request with { Prompt = RagPrompt.Build(request.Prompt, hits) };
        return inner.GenerateAsync(grounded, cancellationToken);
    }
}
