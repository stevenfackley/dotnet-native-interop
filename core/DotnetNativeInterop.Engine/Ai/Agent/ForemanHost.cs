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

    private static ForemanAgent Build()
    {
        var tools = ForemanTools.Build(SearchManualsAsync, RunFeatureAsync, EngineTelemetry.SnapshotJson);
        return new ForemanAgent(tools, BuildBrain(tools));
    }

    // ---- tool bindings (the real engine ops) ----

    // Real op: SemanticSearch.Default over the "manuals" corpus, serialized via the existing AiJson
    // source-gen wrapper (same JSON shape the RAG path already produces) — no new JSON context needed.
    private static Task<string> SearchManualsAsync(string query)
    {
        var hits = SemanticSearch.Default.Search(query, "manuals", topK: 3);
        return Task.FromResult(AiJson.Serialize(hits));
    }

    // Real op: the exact path `dni_feature_run` calls (LanguageFeatureCatalog.Run, which itself
    // branches into ShowcaseCommand.Run for command-grammar ids) — serialized via ForemanJsonContext's
    // FeatureRun registration (see ForemanModels.cs) so this stays reflection-free in Engine.
    private static Task<string> RunFeatureAsync(string id)
    {
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
    // the same encoder/session "manuals" search uses, no second ONNX session) + ExtractiveLanguageModel's
    // synchronous Compose(...) as the prose step. RouterBrain's StreamAnswerAsync re-searches the manuals
    // with "query + tool results" as the retrieval query (the same thing ExtractiveLanguageModel.
    // GenerateAsync would do internally) rather than piping the tool's raw JSON straight to the user —
    // Compose is used directly instead of the full async GenerateAsync because RouterBrain's `prose` seam
    // is a synchronous Func<string,string> (StreamAnswerAsync sinks one composed string, not a token
    // stream); GenerateAsync's per-word delay is a UX affectation for the model-streaming path and buys
    // nothing here.
    private static RouterBrain BuildRouterBrain(IReadOnlyList<ToolDefinition> tools)
    {
        // 0.3f: calibrated against the harness's real-encoder run (see ForemanHarness Task R2) — MiniLM
        // cosine similarity between a short user query and a one-sentence tool description clusters
        // lower than the fake one-hot vectors the unit tests use, so the 0.5 test threshold would starve
        // the router. A tunable product value, not a derived constant.
        var router = new DeterministicRouter(tools, SemanticSearch.Default.Embed, threshold: 0.3f);
        return new RouterBrain(router, ComposeProse);
    }

    private static string ComposeProse(string prompt) =>
        ExtractiveLanguageModel.Compose(SemanticSearch.Default.Search(prompt, "manuals", topK: 3));

    // GrammarBrain seam: a real DniChatClient wrapping a real on-device LlamaLanguageModel.
    //
    // VERSION-GATED FALLBACK (device-deferred): the dni_llama shim (native/llama-shim, see
    // Llama/LlamaNative.cs — dni_llama_generate takes handle/prompt/maxTokens/temp/callback, no grammar
    // parameter) has no grammar-sampling entry point yet. Per docs/superpowers/specs/
    // 2026-07-06-foreman-ondevice-agent-design.md ("Native work required" / "Gates required"), adding
    // real GBNF next-token constraining to the shim is its own device-gated spike, not part of this host
    // wiring. So GbnfGrammar.Build's output rides along here as a *prompted* instruction (appended to the
    // turn prompt) rather than a hard sampler constraint — this compiles and runs against the real model,
    // it just isn't grammar-*enforced* yet. ToolCallParser's "malformed JSON -> answer, never throw"
    // fallback (already exhaustively tested) is exactly what keeps an unconstrained 1B model's output
    // safe in the meantime — a wrong/malformed emission degrades to a plain answer, never a crash or a
    // hung turn. When the shim gate lands, only this method's `Complete` body needs to change (pass the
    // grammar to the native call instead of the prompt); the brain/tool/turn contract does not.
    private static GrammarBrain BuildGrammarBrain(IReadOnlyList<ToolDefinition> tools, ILanguageModel model)
    {
        var chat = new DniChatClient(model, modelId: "Llama-3.2-1B-Instruct");
        var grammar = GbnfGrammar.Build(tools);

        async Task<string> Complete(AgentContext ctx, CancellationToken ct)
        {
            var response = await chat.GetResponseAsync(BuildTurnPrompt(tools, grammar, ctx), cancellationToken: ct)
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
            badge: "on-device LLM (Llama-3.2-1B) — grammar seam wired, native grammar sampling pending shim work");
    }

    private static string BuildTurnPrompt(IReadOnlyList<ToolDefinition> tools, string grammar, AgentContext ctx)
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
        sb.AppendLine("Reference grammar for your reply (informational only — not yet enforced by the native sampler):");
        sb.Append(grammar);
        return sb.ToString();
    }

    private static string BuildAnswerPrompt(AgentContext ctx)
    {
        var sb = new StringBuilder();
        sb.AppendLine("Using only the tool results below, answer the question concisely.");
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
}
