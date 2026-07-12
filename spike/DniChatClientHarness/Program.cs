// DniChatClient proof harness. Exercises the Microsoft.Extensions.AI IChatClient adapter
// (core/DotnetNativeInterop.Engine/Ai/Meai/DniChatClient.cs) directly against MockLanguageModel —
// no NativeBridge, no transport. Every check prints PASS/FAIL; a non-zero exit code means at least
// one failed. Also the win-x64 NativeAOT gate for this code path (see the .csproj comment).
using System.Runtime.CompilerServices;
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
            await RunGrammarThreadingAsync().ConfigureAwait(false);
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
        // Now a MEANINGFUL check (was hardcoded Stop before the honest-finish-reason fix): the mock's
        // ~30-word reply is well under the default 256-token cap, so it completed naturally → Stop.
        Check("non-streaming: a naturally-completed response (under MaxTokens) reports FinishReason.Stop",
            response.FinishReason == ChatFinishReason.Stop);
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
        // The response was cut off at the 3-token cap (the mock's canned reply is ~30 words), so the finish
        // reason must be Length, not a false Stop — the hardened honest-finish-reason behavior.
        Check("truncated response (hit MaxTokens) reports FinishReason.Length, not a false Stop",
            response.FinishReason == ChatFinishReason.Length);
    }

    // Proves the grammar seam Foreman's constrained turn relies on: a GBNF string placed in
    // ChatOptions.AdditionalProperties[DniChatClient.GrammarPropertyKey] must arrive verbatim on
    // InferenceRequest.Grammar (which LlamaLanguageModel forwards to dni_llama_generate's grammar arg),
    // an absent option must leave Grammar null (RAG/answer turns stay unconstrained), and a non-string
    // value must be ignored rather than crash the decode. Uses a capturing backend so this runs with no
    // GGUF — the native sampler enforcement itself is gated separately on the Mac build host.
    private static async Task RunGrammarThreadingAsync()
    {
        const string grammar = "root ::= \"{\\\"answer\\\":\" string \"}\"\nstring ::= \"\\\"\" [a-z]+ \"\\\"\"";

        var capture = new CapturingLanguageModel();
        var options = new ChatOptions
        {
            AdditionalProperties = new AdditionalPropertiesDictionary { [DniChatClient.GrammarPropertyKey] = grammar },
        };
        _ = await new DniChatClient(capture).GetResponseAsync("decide the next step", options).ConfigureAwait(false);
        Console.WriteLine($"grammar threaded: Grammar={(capture.Last?.Grammar is null ? "null" : "<set>")}");
        Check("grammar: AdditionalProperties[dni.grammar] threads to InferenceRequest.Grammar verbatim",
            capture.Last?.Grammar == grammar);

        var capturePlain = new CapturingLanguageModel();
        _ = await new DniChatClient(capturePlain).GetResponseAsync("a plain, unconstrained turn").ConfigureAwait(false);
        Check("grammar: absent option leaves InferenceRequest.Grammar null (unconstrained decode)",
            capturePlain.Last is { Grammar: null });

        var captureBad = new CapturingLanguageModel();
        var badOptions = new ChatOptions
        {
            AdditionalProperties = new AdditionalPropertiesDictionary { [DniChatClient.GrammarPropertyKey] = 42 },
        };
        _ = await new DniChatClient(captureBad).GetResponseAsync("weird value", badOptions).ConfigureAwait(false);
        Check("grammar: a non-string grammar property is ignored (Grammar stays null, never throws)",
            captureBad.Last is { Grammar: null });
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

    // Records the last InferenceRequest the adapter built, so a check can assert what DniChatClient
    // actually handed the backend (here: whether the grammar was threaded through). Yields one trivial
    // fragment so GetResponseAsync completes normally.
    private sealed class CapturingLanguageModel : ILanguageModel
    {
        public InferenceRequest? Last { get; private set; }

        public async IAsyncEnumerable<string> GenerateAsync(
            InferenceRequest request,
            [EnumeratorCancellation] CancellationToken cancellationToken = default)
        {
            Last = request;
            await Task.Yield();
            yield return "{\"answer\":\"ok\"}";
        }
    }
}
