namespace DotnetNativeInterop.Engine.Ai.Agent;

/// <summary>
/// LLM brain: <paramref name="complete"/> runs a grammar-constrained completion (DniChatClient under
/// the GBNF grammar) whose output is parsed by <see cref="ToolCallParser"/>; <paramref name="streamAnswer"/>
/// streams the final grounded answer. The completion/stream funcs isolate the llama dependency so this
/// brain is fully testable without a model.
/// </summary>
public sealed class GrammarBrain(
    Func<AgentContext, CancellationToken, Task<string>> complete,
    Func<AgentContext, Action<string>, CancellationToken, Task> streamAnswer,
    string badge) : IAgentBrain
{
    public string BackendBadge => badge;
    public async Task<AgentDecision> DecideAsync(AgentContext ctx, CancellationToken ct)
        => ToolCallParser.Parse(await complete(ctx, ct));
    public Task StreamAnswerAsync(AgentContext ctx, Action<string> sink, CancellationToken ct)
        => streamAnswer(ctx, sink, ct);
}
