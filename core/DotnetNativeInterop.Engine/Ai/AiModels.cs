using System.Text.Json;
using System.Text.Json.Serialization;

namespace DotnetNativeInterop.Engine;

/// <summary>One ranked corpus entry: the text and its cosine similarity to the query (0..1).</summary>
public sealed record SearchResult(string Text, double Score);

/// <summary>A complete grounded RAG answer (the SQLCipher round-trip payload).</summary>
public sealed record RagAnswer(string Answer);

/// <summary>A text → fixed-width embedding encoder. The ONNX and pure-.NET impls are interchangeable.</summary>
public interface ITextEncoder
{
    /// <summary>Returns an L2-normalized sentence embedding for <paramref name="text"/>.</summary>
    float[] Encode(string text);
}

/// <summary>Source-generated JSON metadata for the search results (AOT-safe).</summary>
[JsonSourceGenerationOptions(PropertyNamingPolicy = JsonKnownNamingPolicy.CamelCase)]
[JsonSerializable(typeof(SearchResult[]))]
[JsonSerializable(typeof(RagAnswer))]
internal sealed partial class AiJsonContext : JsonSerializerContext;

/// <summary>Serializes ranked results to camelCase JSON via the source-gen context.</summary>
public static class AiJson
{
    public static string Serialize(SearchResult[] results) =>
        JsonSerializer.Serialize(results, AiJsonContext.Default.SearchResultArray);

    public static string Serialize(RagAnswer answer) =>
        JsonSerializer.Serialize(answer, AiJsonContext.Default.RagAnswer);
}
