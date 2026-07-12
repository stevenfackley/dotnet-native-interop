using System.Text;
using System.Text.Json;
using DotnetNativeInterop.Engine.Llama;
using DotnetNativeInterop.Engine.Meai;
using Microsoft.Extensions.AI;

namespace DotnetNativeInterop.Engine.Ai.Agent;

/// <summary>
/// Host-side wiring for Foreman: binds the three <see cref="ToolDefinition"/>s to the real engine ops
/// (<see cref="SemanticSearch"/>, <see cref="LanguageFeatureCatalog"/>, <see cref="EngineTelemetry"/>)
/// and selects the brain honestly by GGUF presence — the same gguf-or-fallback seam
/// <c>DotnetNativeInterop.NativeBridge.EngineHost.BuildRagModel</c> already uses for the RAG model.
/// A process-wide singleton (mirrors <see cref="SemanticSearch.Default"/>) so every transport that asks
/// for Foreman shares one bound agent instead of re-resolving tools/brain per call.
/// </summary>
public static class ForemanHost
{
    // Matches the exact filename EngineHost.BuildRagModel probes for; keeping the two checks in sync
    // (not sharing a single constant across the assembly boundary) is a deliberate, tiny duplication —
    // NativeBridge already owns that constant privately and Engine must not gain a reference the other
    // direction just for one string.
    private const string GgufFileName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf";

    private static readonly object Gate = new();
    private static ForemanAgent? _agent;
    // Process-wide "one Foreman chat": every dni_agent_session_start call shares this instance (via
    // Agent below), so a client continues a conversation just by calling that export again — zero ABI
    // change. See ResetConversation for how a client starts a fresh one.
    private static ConversationSession? _conversation;
    // The exact tool set the shipped agent runs (real bindings, incl. the SCOPED run_feature guard —
    // see RunFeatureAsync). Captured so a test can invoke the real guarded tool directly rather than a
    // replicated double.
    private static IReadOnlyList<ToolDefinition>? _tools;

    /// <summary>The shared, fully-wired agent. Built lazily on first use.</summary>
    public static ForemanAgent Agent
    {
        get
        {
            if (_agent is not null)
            {
                return _agent;
            }

            lock (Gate)
            {
                return _agent ??= Build();
            }
        }
    }

    /// <summary>
    /// The shipped agent's real tool set (same instances the <see cref="Agent"/> uses), including the
    /// command-grammar-scoped <c>run_feature</c> binding (see <see cref="RunFeatureAsync"/>). Built lazily
    /// alongside the agent. Exposed so callers/tests can invoke a real, fully-wired tool directly.
    /// </summary>
    public static IReadOnlyList<ToolDefinition> Tools
    {
        get { _ = Agent; return _tools!; }
    }

    /// <summary>
    /// Starts a fresh Foreman conversation: clears the process-wide history so the NEXT
    /// <c>dni_agent_session_start</c> turn has no prior-turn context (today's pre-memory, single-shot
    /// behavior). Zero-ABI — reachable via the existing command-grammar seam (<c>agent~reset</c>, served
    /// by <c>dni_feature_run</c> exactly like <c>trust~posture</c>/<c>trace~stats</c>/<c>metrics~snapshot</c>;
    /// see <c>ShowcaseCommand.RunAgent</c>) or directly, by any caller that already holds a reference to
    /// this host. Idempotent: resetting an already-empty conversation is a no-op.
    /// </summary>
    public static void ResetConversation()
    {
        _ = Agent; // force Build() once so _conversation is assigned even on a cold host
        _conversation?.Reset();
    }

    private static ForemanAgent Build()
    {
        var tools = ForemanTools.Build(SearchManualsAsync, RunFeatureAsync, EngineTelemetry.SnapshotJson);
        _tools = tools;
        _conversation = new ConversationSession();
        return new ForemanAgent(tools, BuildBrain(tools), _conversation);
    }

    // ---- tool bindings (the real engine ops) ----

    // Real op: SemanticSearch.Default over the "manuals" corpus, serialized via the existing AiJson
    // source-gen wrapper (same JSON shape the RAG path already produces) — no new JSON context needed.
    private static Task<string> SearchManualsAsync(string query)
    {
        var hits = SemanticSearch.Default.Search(query, "manuals", topK: 3);
        return Task.FromResult(AiJson.Serialize(hits));
    }

    // Real op: LanguageFeatureCatalog.Run, but SCOPED DOWN for the agent's tool — this binding runs
    // actual C#/.NET feature-catalog DEMOS only. LanguageFeatureCatalog.Run routes any id containing '~'
    // (ShowcaseCommand.IsCommand) into the full command grammar — GC storms (gclab~), benchmarks, and
    // operational commands like trust~/trace~/metrics~/agent~reset. A misbehaving or hallucinating brain
    // must NOT be able to reach those through a tool call (e.g. run_feature{"id":"agent~reset"} would
    // wipe its own conversation memory mid-turn; run_feature{"id":"gclab~preset_loh"} would kick off an
    // allocation storm). Reject the command-grammar marker up front and return an honest tool-result the
    // brain can read. The direct dni_feature_run FFI path is deliberately unaffected — only the agent's
    // tool is scoped; the Lab UI still drives command ids straight through that export.
    private static Task<string> RunFeatureAsync(string id)
    {
        if (ShowcaseCommand.IsCommand(id))
        {
            return Task.FromResult("{\"error\":\"run_feature only runs catalog demos, not command-grammar ids\"}");
        }

        var run = LanguageFeatureCatalog.Run(id);
        return Task.FromResult(JsonSerializer.Serialize(run, ForemanJsonContext.Default.FeatureRun));
    }

    // ---- brain selection: honest by GGUF presence ----

    private static IAgentBrain BuildBrain(IReadOnlyList<ToolDefinition> tools)
    {
        try
        {
            var gguf = Path.Combine(SemanticSearch.ResolveAssetsDir(), GgufFileName);
            if (File.Exists(gguf))
            {
                return BuildGrammarBrain(tools, new LlamaLanguageModel(gguf));
            }
        }
        catch (Exception)
        {
            // A present-but-unloadable GGUF must degrade to the router, never break agent construction
            // (mirrors EngineHost.BuildRagModel's own try/fall-through around LlamaLanguageModel).
        }

        return BuildRouterBrain(tools);
    }

    // No-LLM fallback: DeterministicRouter over real MiniLM embeddings (SemanticSearch.Default.Embed —
    // the same encoder/session "manuals" search uses, no second ONNX session). RouterBrain's
    // StreamAnswerAsync composes the answer from the tool result(s) the turn actually produced (see
    // ComposeProse) — NOT a fresh manuals search — so the streamed prose reflects whatever tool ran.
    private static RouterBrain BuildRouterBrain(IReadOnlyList<ToolDefinition> tools)
    {
        // RouterThreshold: calibrated against the harness's real-encoder run (ForemanHarness "calibration"
        // block) so all three positive routes clear it AND a genuinely off-topic query (embedded with the
        // real MiniLM encoder) falls below it — a tunable product value, not a derived constant.
        var router = new DeterministicRouter(tools, SemanticSearch.Default.Embed, RouterThreshold);
        return new RouterBrain(router, ComposeProse);
    }

    /// <summary>The shipped router cosine cut-off. See <see cref="BuildRouterBrain"/> for calibration.</summary>
    public const float RouterThreshold = 0.3f;

    // Composes the router's fallback prose from the tool result(s) the turn produced. RouterBrain hands
    // this func "<query>\n<joined tool-result JSON>"; we answer from the RESULT, not a fresh manuals
    // search — so an engine_stats/run_feature route streams prose about the stats/feature result, not an
    // off-topic HVAC passage, while a search_manuals route still reads as grounded manual prose. Also
    // drops the redundant second manuals search the earlier binding did on every turn.
    private static string ComposeProse(string prompt)
    {
        var newline = prompt.IndexOf('\n');
        var results = (newline >= 0 ? prompt[(newline + 1)..] : prompt).Trim();
        if (results.Length == 0)
        {
            return "I couldn't find anything in the tools to answer that.";
        }

        // search_manuals returns a SearchResult[]; present it as grounded prose the way the extractive
        // generator does. Any other tool (engine_stats / run_feature) returns its own JSON object —
        // surface that directly rather than dressing it up as a manual excerpt.
        return TryReadSnippets(results, out var hits)
            ? ExtractiveLanguageModel.Compose(hits)
            : $"Based on the tools: {results}";
    }

    // Parses a search_manuals tool result (a SearchResult[] JSON array) back into typed hits without
    // reflection — JsonDocument only, so it stays AOT-clean. Returns false for any non-array/other-tool
    // result, which ComposeProse then surfaces verbatim.
    private static bool TryReadSnippets(string json, out SearchResult[] hits)
    {
        hits = [];
        try
        {
            using var doc = JsonDocument.Parse(json);
            if (doc.RootElement.ValueKind != JsonValueKind.Array)
            {
                return false;
            }

            var list = new List<SearchResult>();
            foreach (var element in doc.RootElement.EnumerateArray())
            {
                if (element.ValueKind != JsonValueKind.Object
                    || !element.TryGetProperty("text", out var text)
                    || text.ValueKind != JsonValueKind.String)
                {
                    return false;
                }

                var score = element.TryGetProperty("score", out var s) && s.ValueKind == JsonValueKind.Number
                    ? s.GetDouble()
                    : 0d;
                list.Add(new SearchResult(text.GetString() ?? string.Empty, score));
            }

            hits = list.ToArray();
            return hits.Length > 0;
        }
        catch (JsonException)
        {
            return false;
        }
    }

    // GrammarBrain seam: a real DniChatClient wrapping a real on-device LlamaLanguageModel.
    //
    // The decision turn is HARD grammar-constrained. GbnfGrammar.Build's output rides to the llama
    // sampler through the M.E.AI seam — ChatOptions.AdditionalProperties[DniChatClient.GrammarPropertyKey]
    // -> InferenceRequest.Grammar -> dni_llama_generate's grammar arg -> llama_sampler_init_grammar — so
    // the model literally cannot emit a token that would make the tool-call/answer JSON malformed (the
    // shim masks grammar-violating tokens before top-k/temp). The raw GBNF is therefore NOT put in the
    // prompt anymore; the prompt just states the JSON shape in prose (BuildTurnPrompt) and the sampler
    // enforces it. ToolCallParser's "malformed JSON -> answer, never throw" fallback stays as
    // defense-in-depth (a truncated turn or a future unconstrained backend), but a grammar-constrained
    // turn should never exercise it. Only the DECISION turn is constrained — the free-form answer turn
    // (StreamAnswer) passes no grammar, so prose streams unconstrained.
    private static GrammarBrain BuildGrammarBrain(IReadOnlyList<ToolDefinition> tools, ILanguageModel model)
    {
        var chat = new DniChatClient(model, modelId: "Llama-3.2-1B-Instruct");
        // Built once (the tool set is fixed) and reused for every decision turn. Read-only after
        // construction, so sharing it across turns is safe — DniChatClient only reads the grammar out.
        var turnOptions = new ChatOptions
        {
            AdditionalProperties = new AdditionalPropertiesDictionary
            {
                [DniChatClient.GrammarPropertyKey] = GbnfGrammar.Build(tools),
            },
        };

        async Task<string> Complete(AgentContext ctx, CancellationToken ct)
        {
            var response = await chat.GetResponseAsync(BuildTurnPrompt(tools, ctx), turnOptions, cancellationToken: ct)
                .ConfigureAwait(false);
            return response.Text;
        }

        async Task StreamAnswer(AgentContext ctx, Action<string> sink, CancellationToken ct)
        {
            await foreach (var update in chat.GetStreamingResponseAsync(BuildAnswerPrompt(ctx), cancellationToken: ct)
                .ConfigureAwait(false))
            {
                if (!string.IsNullOrEmpty(update.Text))
                {
                    sink(update.Text);
                }
            }
        }

        return new GrammarBrain(Complete, StreamAnswer,
            badge: "on-device LLM (Llama-3.2-1B, grammar-constrained)");
    }

    // The reply's JSON shape is stated in prose here and HARD-enforced by the llama grammar sampler (see
    // BuildGrammarBrain) — so the raw GBNF is deliberately NOT appended to the prompt (it would be noise a
    // 1B model reads as content). The prose instruction keeps a non-grammar backend (router fallback)
    // honest and gives the constrained model a reason for the shape it's being forced into.
    private static string BuildTurnPrompt(IReadOnlyList<ToolDefinition> tools, AgentContext ctx)
    {
        var sb = new StringBuilder();
        sb.AppendLine("You are Foreman, an on-device maintenance assistant. Decide the single next step.");
        sb.AppendLine("Reply with EXACTLY one JSON object: {\"tool\":\"<name>\",\"args\":{...}} to call a tool,");
        sb.AppendLine("or {\"answer\":\"...\"} once you can answer. No other text before or after it.");
        sb.AppendLine("Tools:");
        foreach (var t in tools)
        {
            sb.Append("- ").Append(t.Name).Append(": ").AppendLine(t.Description);
        }

        AppendHistory(sb, ctx);

        if (ctx.Steps.Count > 0)
        {
            sb.AppendLine("Steps so far:");
            foreach (var step in ctx.Steps)
            {
                sb.Append("- ").Append(step.Kind).Append(' ').Append(step.Detail);
                if (step.Result is { } result)
                {
                    sb.Append(" -> ").Append(result);
                }

                sb.AppendLine();
            }
        }

        sb.Append("Question: ").AppendLine(ctx.Query);
        return sb.ToString();
    }

    private static string BuildAnswerPrompt(AgentContext ctx)
    {
        var sb = new StringBuilder();
        sb.AppendLine("Using only the tool results below, answer the question concisely.");
        AppendHistory(sb, ctx);
        foreach (var step in ctx.Steps)
        {
            if (step.Kind == AgentStep.KindToolResult)
            {
                sb.Append("- ").AppendLine(step.Result);
            }
        }

        sb.Append("Question: ").AppendLine(ctx.Query);
        return sb.ToString();
    }

    // Real, prompt-level memory for the grammar (LLM) brain: prior turns are read verbatim by the model,
    // unlike the router brain's mechanical embedding-only "memory" (see RouterBrain.DecideAsync). Emits
    // nothing for a fresh/single-shot turn (ctx.History empty) so today's prompts are byte-identical
    // when no conversation memory is wired.
    private static void AppendHistory(StringBuilder sb, AgentContext ctx)
    {
        if (ctx.History.Count == 0)
        {
            return;
        }

        sb.AppendLine("Conversation so far (oldest first):");
        foreach (var turn in ctx.History)
        {
            sb.Append("User: ").AppendLine(turn.Query);
            sb.Append("Foreman: ").AppendLine(turn.Answer);
        }
    }
}
