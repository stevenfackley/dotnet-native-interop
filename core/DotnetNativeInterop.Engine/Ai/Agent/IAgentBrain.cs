namespace DotnetNativeInterop.Engine.Ai.Agent;

/// <summary>Running state of a turn passed to the brain each step.</summary>
public sealed class AgentContext(string query, List<AgentStep> steps, IReadOnlyList<ConversationTurn>? history = null)
{
    public string Query { get; } = query;
    public List<AgentStep> Steps { get; } = steps;

    /// <summary>
    /// Completed turns from earlier in this conversation, oldest-to-newest — empty for a fresh
    /// conversation or a single-shot turn (the default when no history is supplied, so every existing
    /// <c>new AgentContext(query, steps)</c> call site is unaffected). Populated from
    /// <see cref="ConversationSession.Snapshot"/> by <see cref="ForemanAgent.RunTurnAsync"/>. A brain MAY
    /// use it — see <see cref="RouterBrain"/> and <see cref="ForemanHost"/>'s prompt builders for how
    /// each brain honestly does (or, for the router, can only partly do) so.
    /// </summary>
    public IReadOnlyList<ConversationTurn> History { get; } = history ?? Array.Empty<ConversationTurn>();
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
