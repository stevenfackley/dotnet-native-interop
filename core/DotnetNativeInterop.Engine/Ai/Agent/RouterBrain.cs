namespace DotnetNativeInterop.Engine.Ai.Agent;

/// <summary>
/// No-LLM brain: the deterministic router picks a tool on the first step; once a tool result
/// exists it answers via the provided prose function (the ExtractiveLanguageModel path).
/// Honestly badged so the UI shows there is no on-device LLM.
/// </summary>
public sealed class RouterBrain(DeterministicRouter router, Func<string, string> prose) : IAgentBrain
{
    public string BackendBadge => "scripted routing — no on-device LLM present";

    public Task<AgentDecision> DecideAsync(AgentContext ctx, CancellationToken ct)
    {
        var used = ctx.Steps.Any(s => s.Kind == AgentStep.KindToolResult);
        if (!used)
        {
            // Honest "memory" for a brain with no language model: DeterministicRouter cannot resolve a
            // pronoun like "that" — it only measures cosine similarity between embedded text and tool
            // descriptions. The one thing it CAN honestly do with conversation history is embed MORE
            // text: folding in the immediately-prior turn (query + answer, see LastTurnHint) means a
            // follow-up like "how do I clear THAT?" is actually routed on the combined text
            // "search the manuals for fault E3 ... how do I clear THAT?", which contains real
            // tool-relevant words the bare follow-up alone does not. Only the single most recent turn is
            // folded in (not the whole bounded history) so an older, unrelated turn can't drag routing
            // off the CURRENT topic.
            var hint = ctx.History.Count > 0 ? LastTurnHint(ctx.History[^1]) : null;
            if (router.Route(ctx.Query, hint) is { } call)
            {
                return Task.FromResult(AgentDecision.Tool(call));
            }
        }

        return Task.FromResult(AgentDecision.Answer);
    }

    public Task StreamAnswerAsync(AgentContext ctx, Action<string> sink, CancellationToken ct)
    {
        var results = string.Join(" ", ctx.Steps.Where(s => s.Kind == AgentStep.KindToolResult).Select(s => s.Result));
        sink(prose($"{ctx.Query}\n{results}"));
        return Task.CompletedTask;
    }

    private static string LastTurnHint(ConversationTurn t) => $"{t.Query} {t.Answer}";
}
