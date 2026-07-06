using System.Runtime.CompilerServices;
using System.Text;
using Microsoft.Extensions.AI;

namespace DotnetNativeInterop.Engine.Meai;

/// <summary>
/// Adapts the engine's <see cref="ILanguageModel"/> seam to Microsoft.Extensions.AI's
/// <see cref="IChatClient"/>, so the M.E.AI ecosystem — <c>ChatClientBuilder</c> middleware
/// (logging, caching, rate limiting) and, eventually, <c>FunctionInvokingChatClient</c> for an
/// on-device function-calling agent — can compose over any of the engine's backends
/// (<see cref="MockLanguageModel"/>, <see cref="ExtractiveLanguageModel"/>, <see cref="RagLanguageModel"/>,
/// <see cref="Llama.LlamaLanguageModel"/>) without those backends knowing M.E.AI exists.
/// See docs/meai-nativeaot-findings.md for the spike that proved this package AOT-clean.
///
/// Wraps <see cref="ILanguageModel"/> directly rather than <see cref="InferenceOrchestrator"/> or
/// <see cref="InferenceSession"/>: both of those exist to prepare a token stream for an external
/// interop boundary (indexed <see cref="InferenceToken"/> plus a synthetic final marker, a bounded
/// <see cref="System.Threading.Channels.Channel{T}"/> for a native-callback consumer) — concerns this
/// in-process adapter doesn't have. <see cref="IAsyncEnumerable{T}"/> already gives
/// <see cref="GetStreamingResponseAsync"/> a natural completion signal, so this class talks to
/// <see cref="ILanguageModel.GenerateAsync"/> at the same layer <see cref="RagLanguageModel"/> itself
/// wraps, one below the orchestrator.
///
/// Function-calling plug-in point: a future agent layer wraps an instance of this class in
/// <c>Microsoft.Extensions.AI.FunctionInvokingChatClient</c> (the heavier, non-Abstractions
/// <c>Microsoft.Extensions.AI</c> package — see the findings doc's caveat that it deserves its own
/// AOT spike before adoption) via <c>ChatOptions.Tools</c>/<c>ToolMode</c>. This adapter never reads
/// those properties itself: the engine's backends are plain text completion, so tool-call parsing
/// belongs to that future middleware, not here.
/// </summary>
public sealed class DniChatClient(ILanguageModel model, string? modelId = null) : IChatClient
{
    /// <inheritdoc/>
    public async Task<ChatResponse> GetResponseAsync(
        IEnumerable<ChatMessage> messages, ChatOptions? options = null, CancellationToken cancellationToken = default)
    {
        var request = BuildRequest(messages, options);

        var text = new StringBuilder();
        await foreach (var fragment in model.GenerateAsync(request, cancellationToken).ConfigureAwait(false))
        {
            text.Append(fragment);
        }

        var message = new ChatMessage(ChatRole.Assistant, text.ToString());
        return new ChatResponse(message)
        {
            ModelId = modelId ?? model.GetType().Name,
            FinishReason = ChatFinishReason.Stop,
        };
    }

    /// <inheritdoc/>
    public async IAsyncEnumerable<ChatResponseUpdate> GetStreamingResponseAsync(
        IEnumerable<ChatMessage> messages,
        ChatOptions? options = null,
        [EnumeratorCancellation] CancellationToken cancellationToken = default)
    {
        var request = BuildRequest(messages, options);
        var id = modelId ?? model.GetType().Name;

        await foreach (var fragment in model.GenerateAsync(request, cancellationToken).ConfigureAwait(false))
        {
            yield return new ChatResponseUpdate(ChatRole.Assistant, fragment) { ModelId = id };
        }
    }

    /// <inheritdoc/>
    // Mirrors the forwarding DelegatingChatClient does in spike/MeaiGate, generalized: resolves to
    // this adapter itself, or to the wrapped model when a caller asks for it (or a concrete model
    // type) by Type — e.g. GetService(typeof(ILanguageModel)) hands back the inner backend.
    public object? GetService(Type serviceType, object? serviceKey = null) =>
        serviceKey is not null ? null
        : serviceType.IsInstanceOfType(this) ? this
        : serviceType.IsInstanceOfType(model) ? model
        : null;

    /// <inheritdoc/>
    // No-op: the wrapped model's lifecycle belongs to whoever constructed it (e.g. EngineHost holds
    // LlamaLanguageModel as a process-wide singleton) — the same non-ownership RagLanguageModel
    // already exercises by wrapping ILanguageModel without disposing it.
    public void Dispose()
    {
    }

    // Flattens chat history into the single prompt string ILanguageModel expects — it has no
    // multi-turn concept of its own (every existing backend takes one prompt). Rendered as
    // "role: text" lines per message; a deliberate, simple default a real backend integration
    // (e.g. a proper chat template for LlamaLanguageModel) would replace.
    private static InferenceRequest BuildRequest(IEnumerable<ChatMessage> messages, ChatOptions? options)
    {
        var prompt = new StringBuilder();
        foreach (var message in messages)
        {
            if (prompt.Length > 0)
            {
                prompt.Append('\n');
            }

            prompt.Append(message.Role.Value).Append(": ").Append(message.Text);
        }

        return new InferenceRequest(
            Prompt: prompt.ToString(),
            MaxTokens: options?.MaxOutputTokens ?? 256,
            Temperature: options?.Temperature ?? 0.8f);
    }
}
