// Gate: does Microsoft.Extensions.AI.Abstractions (a hand-implemented IChatClient + a
// DelegatingChatClient middleware) publish + run under NativeAOT?
// Purpose: gates exposing the engine's ILanguageModel as IChatClient for a function-calling agent.
// See docs/meai-nativeaot-findings.md.
using Microsoft.Extensions.AI;

IChatClient inner = new EchoChatClient();
IChatClient client = new UpperCaseDelegatingChatClient(inner);

ChatResponse response = await client.GetResponseAsync("hello from dni");
Console.WriteLine($"GetResponseAsync: \"{response.Text}\"");
bool responseOk = response.Text == "ECHO: HELLO FROM DNI";

var streamedText = new System.Text.StringBuilder();
int updateCount = 0;
await foreach (ChatResponseUpdate update in client.GetStreamingResponseAsync("stream this back"))
{
    streamedText.Append(update.Text);
    updateCount++;
}
string streamed = streamedText.ToString();
Console.WriteLine($"GetStreamingResponseAsync: {updateCount} updates, joined=\"{streamed}\"");
bool streamOk = streamed.Trim() == "ECHO: STREAM THIS BACK" && updateCount > 1;

object? serviceProbe = client.GetService(typeof(EchoChatClient));
Console.WriteLine($"GetService(typeof(EchoChatClient)) resolved through middleware = {serviceProbe is not null}");

client.Dispose();

bool allOk = responseOk && streamOk && serviceProbe is not null;
Console.WriteLine(allOk ? "PASS: IChatClient + DelegatingChatClient middleware ran end to end" : "FAIL: see above");

/// <summary>A trivial in-process IChatClient that echoes the last user message, uppercased is done by the middleware.</summary>
internal sealed class EchoChatClient : IChatClient
{
    public Task<ChatResponse> GetResponseAsync(
        IEnumerable<ChatMessage> messages, ChatOptions? options = null, CancellationToken cancellationToken = default)
    {
        string prompt = messages.LastOrDefault()?.Text ?? "";
        var message = new ChatMessage(ChatRole.Assistant, $"echo: {prompt}");
        return Task.FromResult(new ChatResponse(message));
    }

    public async IAsyncEnumerable<ChatResponseUpdate> GetStreamingResponseAsync(
        IEnumerable<ChatMessage> messages,
        ChatOptions? options = null,
        [System.Runtime.CompilerServices.EnumeratorCancellation] CancellationToken cancellationToken = default)
    {
        string prompt = messages.LastOrDefault()?.Text ?? "";
        foreach (string word in $"echo: {prompt}".Split(' '))
        {
            await Task.Yield();
            yield return new ChatResponseUpdate(ChatRole.Assistant, word + " ");
        }
    }

    public object? GetService(Type serviceType, object? serviceKey = null) =>
        serviceType == typeof(EchoChatClient) ? this : null;

    public void Dispose()
    {
    }
}

/// <summary>Delegating middleware: uppercases whatever the inner client produces (both response modes).</summary>
internal sealed class UpperCaseDelegatingChatClient(IChatClient innerClient) : DelegatingChatClient(innerClient)
{
    public override async Task<ChatResponse> GetResponseAsync(
        IEnumerable<ChatMessage> messages, ChatOptions? options = null, CancellationToken cancellationToken = default)
    {
        ChatResponse response = await base.GetResponseAsync(messages, options, cancellationToken);
        response.Messages[^1] = new ChatMessage(ChatRole.Assistant, response.Text.ToUpperInvariant());
        return response;
    }

    public override async IAsyncEnumerable<ChatResponseUpdate> GetStreamingResponseAsync(
        IEnumerable<ChatMessage> messages,
        ChatOptions? options = null,
        [System.Runtime.CompilerServices.EnumeratorCancellation] CancellationToken cancellationToken = default)
    {
        await foreach (ChatResponseUpdate update in base.GetStreamingResponseAsync(messages, options, cancellationToken))
        {
            yield return new ChatResponseUpdate(update.Role, update.Text.ToUpperInvariant());
        }
    }
}
