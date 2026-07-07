using System.Globalization;
using System.Numerics.Tensors;

namespace DotnetNativeInterop.Engine.Ai.Agent;

/// <summary>
/// The no-LLM fallback selector: embeds the query + each tool description and picks the nearest
/// tool above a threshold, else returns null (the brain then answers). Deterministic + testable.
/// For search-style tools the raw query is passed through as the "query" arg.
/// </summary>
public sealed class DeterministicRouter
{
    private readonly (ToolDefinition Tool, float[] Vec)[] _tools;
    private readonly Func<string, float[]> _embed;
    private readonly float _threshold;

    public DeterministicRouter(IReadOnlyList<ToolDefinition> tools, Func<string, float[]> embed, float threshold)
    {
        _embed = embed; _threshold = threshold;
        _tools = new (ToolDefinition, float[])[tools.Count];
        for (int i = 0; i < tools.Count; i++) _tools[i] = (tools[i], embed(tools[i].Description));
    }

    /// <param name="query">The current turn's raw question.</param>
    /// <param name="contextHint">
    /// Optional prior-conversation text (see <see cref="RouterBrain.DecideAsync"/>) folded into the SAME
    /// text used both to embed the routing decision and, if a string-arg tool is picked, as its arg.
    /// This is NOT semantic pronoun resolution — the router has no language model and does not know
    /// "that" refers to anything — it is the one honest, mechanical thing a cosine-similarity router can
    /// do with history: give the embedding more real words to work with. Null/empty (the default)
    /// reproduces the exact pre-memory behavior (routes on <paramref name="query"/> alone).
    /// </param>
    public ToolCall? Route(string query, string? contextHint = null)
    {
        var routedText = string.IsNullOrEmpty(contextHint) ? query : $"{contextHint}\n{query}";
        var q = _embed(routedText);
        float best = float.NegativeInfinity; ToolDefinition? pick = null;
        foreach (var (tool, vec) in _tools)
        {
            var sim = TensorPrimitives.CosineSimilarity(q, vec);
            if (sim > best) { best = sim; pick = tool; }
        }
        if (pick is null || best < _threshold) return null;
        // string-arg tools get the routed text (query, or query+context when a hint was folded in);
        // no-arg tools get {}
        var args = pick.Params.Count > 0
            ? $"{{\"{pick.Params[0].Name}\":{JsonStringLiteral(routedText)}}}"
            : "{}";
        return new ToolCall(pick.Name, args);
    }

    // Hand-rolled JSON string escaping: JsonSerializer.Serialize<string>(...) carries
    // RequiresUnreferencedCode/RequiresDynamicCode (IL2026/IL3050) since it is the generic
    // reflection-based overload — not source-gen — so it is off-limits in this AOT-safe engine.
    // The query is plain user text, never JSON control structure, so a minimal escaper suffices.
    private static string JsonStringLiteral(string s)
    {
        var sb = new System.Text.StringBuilder(s.Length + 2).Append('"');
        foreach (var c in s)
        {
            switch (c)
            {
                case '"': sb.Append("\\\""); break;
                case '\\': sb.Append("\\\\"); break;
                case '\n': sb.Append("\\n"); break;
                case '\r': sb.Append("\\r"); break;
                case '\t': sb.Append("\\t"); break;
                default:
                    if (c < ' ') sb.Append("\\u").Append(((int)c).ToString("x4", CultureInfo.InvariantCulture));
                    else sb.Append(c);
                    break;
            }
        }
        return sb.Append('"').ToString();
    }
}
