using System.Numerics.Tensors;

namespace DotnetNativeInterop.Engine;

/// <summary>
/// Embeds a corpus once, then ranks it by cosine similarity to a query embedding (SIMD via
/// <see cref="TensorPrimitives"/>). Encoder-agnostic: works with the ONNX or pure-.NET encoder.
/// </summary>
public sealed class SemanticSearch(ITextEncoder encoder)
{
    private readonly Dictionary<string, (string[] Texts, float[][] Embeddings)> _corpora = new(StringComparer.Ordinal);

    /// <summary>Embeds and stores a named corpus.</summary>
    public void SetCorpus(string id, IReadOnlyList<string> texts)
    {
        var embeddings = new float[texts.Count][];
        for (var i = 0; i < texts.Count; i++)
        {
            embeddings[i] = encoder.Encode(texts[i]);
        }

        _corpora[id] = (texts.ToArray(), embeddings);
    }

    /// <summary>Ranks corpus <paramref name="corpusId"/> by cosine similarity to <paramref name="query"/>.</summary>
    public SearchResult[] Search(string query, string corpusId, int topK)
    {
        if (!_corpora.TryGetValue(corpusId, out var corpus))
        {
            return [];
        }

        var q = encoder.Encode(query);
        var scored = new List<SearchResult>(corpus.Texts.Length);
        for (var i = 0; i < corpus.Texts.Length; i++)
        {
            var score = TensorPrimitives.CosineSimilarity(q, corpus.Embeddings[i]);
            scored.Add(new SearchResult(corpus.Texts[i], Math.Round(score, 4)));
        }

        scored.Sort((a, b) => b.Score.CompareTo(a.Score));
        return scored.Take(topK).ToArray();
    }
}
