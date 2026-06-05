using System.Text.Json.Serialization;

namespace OnDeviceLlm.NativeBridge.Http;

// ---------------------------------------------------------------------------
// Request DTO — mirrors POST /v1/infer body from INTEROP_CONTRACT.md
// ---------------------------------------------------------------------------

/// <summary>Deserialized body of <c>POST /v1/infer</c>.</summary>
internal sealed class InferRequest
{
    public required string Prompt { get; init; }
    public int MaxTokens { get; init; } = 256;
    public float Temperature { get; init; } = 0.8f;
}

// ---------------------------------------------------------------------------
// SSE token DTO — each `data:` line in the event-stream response
// ---------------------------------------------------------------------------

/// <summary>One SSE payload frame: <c>{"index":N,"text":"…","final":false}</c>.</summary>
internal sealed class SseToken
{
    public required int Index { get; init; }
    public required string Text { get; init; }

    // JSON key is "final" per contract; C# reserved word → explicit name.
    [JsonPropertyName("final")]
    public required bool Final { get; init; }
}

// ---------------------------------------------------------------------------
// AOT-safe source-generated JsonSerializerContext
// NativeAOT forbids runtime reflection-based serialization; every type that
// crosses the JSON boundary must be registered here.
// ---------------------------------------------------------------------------

[JsonSerializable(typeof(InferRequest))]
[JsonSerializable(typeof(SseToken))]
[JsonSourceGenerationOptions(
    PropertyNamingPolicy = JsonKnownNamingPolicy.CamelCase,
    DefaultIgnoreCondition = JsonIgnoreCondition.Never)]
internal sealed partial class HttpJsonContext : JsonSerializerContext { }
