using System.Runtime.CompilerServices;
using System.Text;

namespace DotnetNativeInterop.Engine;

/// <summary>
/// A fully-managed, deterministic RAG generator: retrieves the top manuals passages for the prompt
/// and streams a grounded answer stitched from them — no neural model, no weights. This is the
/// guaranteed (and first shippable) generator for "Ask the Manuals"; the llama.cpp-backed
/// <c>RagLanguageModel</c> (Plan B) replaces it behind the same <see cref="ILanguageModel"/> seam
/// once the native gate passes. Honest by construction: every sentence is quoted from a source.
/// </summary>
public sealed class ExtractiveLanguageModel(
    SemanticSearch? search = null,
    int topK = 3,
    TimeSpan? perWordDelay = null) : ILanguageModel
{
    private readonly TimeSpan _delay = perWordDelay ?? TimeSpan.FromMilliseconds(18);

    /// <inheritdoc/>
    public async IAsyncEnumerable<string> GenerateAsync(
        InferenceRequest request,
        [EnumeratorCancellation] CancellationToken cancellationToken = default)
    {
        // Deferred so the heavy SemanticSearch.Default (ONNX model + corpora) builds lazily.
        var engine = search ?? SemanticSearch.Default;
        var answer = Compose(engine.Search(request.Prompt, "manuals", topK));

        var words = answer.Split(' ');
        var limit = Math.Min(words.Length, request.MaxTokens);
        for (var i = 0; i < limit; i++)
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (_delay > TimeSpan.Zero)
            {
                await Task.Delay(_delay, cancellationToken).ConfigureAwait(false);
            }

            yield return i == 0 ? words[i] : " " + words[i];
        }
    }

    /// <summary>
    /// Builds a grounded answer from retrieved passages. The wording/format is a deliberate product
    /// choice (how to present manual excerpts as an answer) — a good spot for human input during
    /// execution; this is a sensible default.
    /// </summary>
    public static string Compose(SearchResult[] hits)
    {
        if (hits.Length == 0)
        {
            return "I couldn't find anything about that in the manuals.";
        }

        var sb = new StringBuilder("Based on the manuals: ");
        sb.Append(hits[0].Text);
        for (var i = 1; i < hits.Length; i++)
        {
            sb.Append(" Related: ").Append(hits[i].Text);
        }

        return sb.ToString();
    }
}
