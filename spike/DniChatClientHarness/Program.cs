// DniChatClient proof harness. Exercises the Microsoft.Extensions.AI IChatClient adapter
// (core/DotnetNativeInterop.Engine/Ai/Meai/DniChatClient.cs) directly against MockLanguageModel —
// no NativeBridge, no transport. Every check prints PASS/FAIL; a non-zero exit code means at least
// one failed. Also the win-x64 NativeAOT gate for this code path (see the .csproj comment).
using DotnetNativeInterop.Engine;
using DotnetNativeInterop.Engine.Meai;
using Microsoft.Extensions.AI;

internal static class Program
{
    private static readonly List<(string Name, bool Ok)> Results = [];

    private static async Task<int> Main()
    {
        Console.WriteLine("== DniChatClient proof harness ==");

        try
        {
            await RunNonStreamingAsync().ConfigureAwait(false);
            await RunMultiTurnPromptFlatteningAsync().ConfigureAwait(false);
            await RunStreamingOrderAsync().ConfigureAwait(false);
            await RunChatOptionsMappingAsync().ConfigureAwait(false);
            RunGetServiceAndDispose();
        }
        catch (Exception ex)
        {
            Console.WriteLine($"[FAIL] harness aborted: {ex.GetType().Name}: {ex.Message}");
            Results.Add(("harness completed", false));
        }

        Console.WriteLine();
        var passed = Results.Count(r => r.Ok);
        Console.WriteLine($"== {passed}/{Results.Count} checks passed ==");
        return Results.All(r => r.Ok) ? 0 : 1;
    }

    // Zero delay everywhere below: this harness checks mapping/ordering correctness, not timing —
    // MockLanguageModel's default 30ms/word delay would just make the harness slow for no signal.
    private static IChatClient NewClient() => new DniChatClient(new MockLanguageModel(TimeSpan.Zero));

    private static async Task RunNonStreamingAsync()
    {
        var client = NewClient();
        var response = await client.GetResponseAsync("hello from dni").ConfigureAwait(false);

        Console.WriteLine($"GetResponseAsync: \"{response.Text}\"");
        Check("non-streaming: echoes the flattened (role-prefixed) prompt",
            response.Text.Contains("user: hello from dni", StringComparison.Ordinal));
        Check("non-streaming: MockLanguageModel actually ran",
            response.Text.StartsWith("You asked:", StringComparison.Ordinal));
        Check("non-streaming: FinishReason is Stop", response.FinishReason == ChatFinishReason.Stop);
        Check("non-streaming: ModelId defaults to the backend's type name",
            response.ModelId == nameof(MockLanguageModel));
    }

    private static async Task RunMultiTurnPromptFlatteningAsync()
    {
        var client = NewClient();
        ChatMessage[] history =
        [
            new(ChatRole.System, "You are terse."),
            new(ChatRole.User, "hi"),
            new(ChatRole.Assistant, "hello"),
            new(ChatRole.User, "again"),
        ];

        var response = await client.GetResponseAsync(history).ConfigureAwait(false);
        const string expectedPrompt = "system: You are terse.\nuser: hi\nassistant: hello\nuser: again";

        Check("multi-turn: history flattens to ordered \"role: text\" lines",
            response.Text.Contains(expectedPrompt, StringComparison.Ordinal));
    }

    private static async Task RunStreamingOrderAsync()
    {
        var client = NewClient();
        ChatMessage[] prompt = [new ChatMessage(ChatRole.User, "stream this back")];

        var nonStreamed = await client.GetResponseAsync(prompt).ConfigureAwait(false);

        var updates = new List<ChatResponseUpdate>();
        await foreach (var update in client.GetStreamingResponseAsync(prompt))
        {
            updates.Add(update);
        }

        var streamedText = string.Concat(updates.Select(u => u.Text));
        Console.WriteLine($"GetStreamingResponseAsync: {updates.Count} updates, joined=\"{streamedText}\"");

        Check("streaming: yields more than one update (genuine token-by-token streaming)", updates.Count > 1);
        Check("streaming: every update carries the Assistant role",
            updates.All(u => u.Role == ChatRole.Assistant));
        Check("streaming: every update carries the adapter's ModelId",
            updates.All(u => u.ModelId == nameof(MockLanguageModel)));
        Check("streaming: concatenated updates, IN ORDER, exactly equal the non-streaming response " +
              "(the same GenerateAsync sequence surfaced through both paths)",
            streamedText == nonStreamed.Text);
    }

    private static async Task RunChatOptionsMappingAsync()
    {
        var client = NewClient();
        ChatMessage[] prompt = [new ChatMessage(ChatRole.User, "hello")];
        var options = new ChatOptions { MaxOutputTokens = 3 };

        var response = await client.GetResponseAsync(prompt, options).ConfigureAwait(false);
        var wordCount = response.Text.Split(' ', StringSplitOptions.RemoveEmptyEntries).Length;

        Console.WriteLine($"ChatOptions.MaxOutputTokens=3 -> \"{response.Text}\" ({wordCount} words)");
        Check("ChatOptions.MaxOutputTokens maps to InferenceRequest.MaxTokens (caps generated words)",
            wordCount == 3);
    }

    private static void RunGetServiceAndDispose()
    {
        var model = new MockLanguageModel(TimeSpan.Zero);
        var client = new DniChatClient(model);

        Check("GetService: resolves the adapter itself", client.GetService(typeof(DniChatClient)) is not null);
        Check("GetService: resolves the wrapped concrete model", client.GetService(typeof(MockLanguageModel)) is not null);
        Check("GetService: resolves the wrapped model via ILanguageModel", client.GetService(typeof(ILanguageModel)) is not null);
        Check("GetService: unrelated type resolves to null", client.GetService(typeof(string)) is null);
        Check("GetService: a non-null serviceKey resolves to null (no keyed services supported)",
            client.GetService(typeof(DniChatClient), serviceKey: "any") is null);

        client.Dispose();
        Check("Dispose: does not throw (adapter owns no disposable resources of its own)", true);
    }

    private static void Check(string name, bool ok)
    {
        Results.Add((name, ok));
        Console.WriteLine($"[{(ok ? "PASS" : "FAIL")}] {name}");
    }
}
