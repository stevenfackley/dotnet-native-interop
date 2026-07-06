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
        var used = ctx.Steps.Any(s => s.Kind == "tool_result");
        if (!used && router.Route(ctx.Query) is { } call) return Task.FromResult(AgentDecision.Tool(call));
        return Task.FromResult(AgentDecision.Answer);
    }

    public Task StreamAnswerAsync(AgentContext ctx, Action<string> sink, CancellationToken ct)
    {
        var results = string.Join(" ", ctx.Steps.Where(s => s.Kind == "tool_result").Select(s => s.Result));
        sink(prose($"{ctx.Query}\n{results}"));
        return Task.CompletedTask;
    }
}
