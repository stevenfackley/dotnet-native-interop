namespace DotnetNativeInterop.Engine.Ai.Agent;

/// <summary>Running state of a turn passed to the brain each step.</summary>
public sealed class AgentContext(string query, List<AgentStep> steps)
{
    public string Query { get; } = query;
    public List<AgentStep> Steps { get; } = steps;
}

/// <summary>
/// The pluggable "brain". <see cref="DecideAsync"/> chooses a tool call or to answer;
/// <see cref="StreamAnswerAsync"/> produces the final grounded answer tokens.
/// Impls: <c>GrammarBrain</c> (llama + GBNF) and <c>RouterBrain</c> (deterministic fallback).
/// </summary>
public interface IAgentBrain
{
    Task<AgentDecision> DecideAsync(AgentContext ctx, CancellationToken ct);
    Task StreamAnswerAsync(AgentContext ctx, Action<string> sink, CancellationToken ct);
    /// <summary>Honest UI badge, e.g. "on-device LLM (grammar-constrained)" or "scripted routing".</summary>
    string BackendBadge { get; }
}
