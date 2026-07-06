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
    // An async iterator (rather than returning the inner enumerable directly) so each RAG stage —
    // retrieve, prompt-assembly, generate — gets its own EngineTrace span in the boundary waterfall.
    public async IAsyncEnumerable<string> GenerateAsync(
        InferenceRequest request,
        [EnumeratorCancellation] CancellationToken cancellationToken = default)
    {
        var engine = search ?? SemanticSearch.Default;       // lazy: built on first use

        var retrieve = EngineTrace.StartSpan("rag.retrieve");
        var hits = engine.Search(request.Prompt, "manuals", topK);
        retrieve?.Dispose();

        var assemble = EngineTrace.StartSpan("rag.prompt");
        var grounded = request with { Prompt = RagPrompt.Build(request.Prompt, hits) };
        assemble?.Dispose();

        // The generate span brackets the whole streaming completion (time-to-final).
        var generate = EngineTrace.StartSpan("rag.generate");
        try
        {
            await foreach (var fragment in inner.GenerateAsync(grounded, cancellationToken).ConfigureAwait(false))
            {
                yield return fragment;
            }
        }
        finally
        {
            generate?.Dispose();
        }
    }
}
