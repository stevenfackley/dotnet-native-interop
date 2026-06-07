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

    private static SemanticSearch? _default;
    private static readonly object Gate = new();

    /// <summary>Process-wide search over two corpora ("features", "facts"), built once from bundled assets.</summary>
    public static SemanticSearch Default
    {
        get
        {
            lock (Gate)
            {
                if (_default is not null)
                {
                    return _default;
                }

                var dir = ResolveAssetsDir();
                var tokenizer = new WordPieceTokenizer(File.ReadAllLines(Path.Combine(dir, "vocab.txt")));
                var search = new SemanticSearch(new OnnxTextEncoder(Path.Combine(dir, "model.onnx"), tokenizer));
                search.SetCorpus("features",
                    LanguageFeatureCatalog.Descriptors.Select(d => $"{d.Title}: {d.Version}").ToArray());
                search.SetCorpus("facts", File.ReadAllLines(Path.Combine(dir, "corpus.txt"))
                    .Where(l => l.Trim().Length > 0).ToArray());
                search.SetCorpus("manuals", ManualsCorpus.Load(dir));
                _default = search;
                return _default;
            }
        }
    }

    /// <summary>Searches the default instance; returns JSON top-K for the native export.</summary>
    public static string SearchJson(string query, string corpusId, int topK = 5) =>
        AiJson.Serialize(Default.Search(query, corpusId, topK));

    // Assets land in different layouts: the .NET build output preserves "Ai/assets/", the iOS app bundle
    // copies the folder reference to "assets/" at the bundle root, and the Android host extracts them to a
    // filesDir path supplied via SetAssetsDirOverride. Probe the known spots for vocab.
    // Public so other engine components (e.g. the llama GGUF loader) resolve the same bundled-assets dir.
    private static string? _assetsDirOverride;

    /// <summary>Platform host (Android) points the engine at the extracted on-device assets dir.</summary>
    public static void SetAssetsDirOverride(string path) => _assetsDirOverride = path;

    public static string ResolveAssetsDir()
    {
        var ov = _assetsDirOverride;
        if (!string.IsNullOrEmpty(ov) && File.Exists(Path.Combine(ov, "vocab.txt")))
        {
            return ov;
        }

        var baseDir = AppContext.BaseDirectory;
        string[] candidates =
        [
            Path.Combine(baseDir, "Ai", "assets"),
            Path.Combine(baseDir, "assets"),
            baseDir,
        ];
        return Array.Find(candidates, c => File.Exists(Path.Combine(c, "vocab.txt"))) ?? candidates[0];
    }
}
