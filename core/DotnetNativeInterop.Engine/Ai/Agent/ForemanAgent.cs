using System.Diagnostics;
using System.Text;

namespace DotnetNativeInterop.Engine.Ai.Agent;

/// <summary>
/// The Foreman turn loop. Bounded, brain-agnostic. Emits an <c>agent.turn</c> span and an
/// <c>agent.tool.&lt;name&gt;</c> span per tool call so every step shows in the Wave B trace waterfall.
/// </summary>
public sealed class ForemanAgent
{
    public const int MaxToolSteps = 5;
    // Reuse the Wave B engine trace source (the SAME ActivitySource EngineTrace records from — not a
    // second one that merely shares the name) so agent spans land in the very ring/drain/waterfall the
    // native UI already reads.
    private static readonly ActivitySource Trace = EngineTrace.Source;

    private readonly IReadOnlyList<ToolDefinition> _tools;
    private readonly IAgentBrain _brain;
    private readonly ConversationSession? _conversation;

    /// <param name="tools">The tools this agent's turns may call.</param>
    /// <param name="brain">The decision/answer brain driving the turn loop (router or grammar).</param>
    /// <param name="conversation">
    /// Optional shared conversation memory (see <see cref="ConversationSession"/>). Null (the default)
    /// keeps today's single-shot behavior: every turn starts with empty history and nothing is recorded
    /// afterward, so existing single-turn callers/tests are unaffected. <see cref="ForemanHost"/> passes
    /// its process-wide instance so a client's repeated turns accumulate into one running conversation —
    /// zero ABI change (see <see cref="ForemanHost.ResetConversation"/> for how a client starts fresh).
    /// </param>
    public ForemanAgent(IReadOnlyList<ToolDefinition> tools, IAgentBrain brain, ConversationSession? conversation = null)
    { _tools = tools; _brain = brain; _conversation = conversation; }

    public string BackendBadge => _brain.BackendBadge;

    public async Task<ForemanTurnResult> RunTurnAsync(string query, Action<string> answerSink, CancellationToken ct)
    {
        using var turn = Trace.StartActivity("agent.turn");
        turn?.SetTag("dni.agent.query_len", query.Length);
        EngineTrace.RecordAgentTurn();
        var history = _conversation?.Snapshot() ?? Array.Empty<ConversationTurn>();
        var ctx = new AgentContext(query, new List<AgentStep>(), history);
        // Mirror every sinked token into the recorded answer so the returned ForemanTurnResult.Answer is
        // honest (the sink is the live streaming path; this is the durable copy a JSON consumer reads).
        var answer = new StringBuilder();
        void Sink(string s) { answer.Append(s); answerSink(s); }
        int toolSteps = 0;

        // Every return point funnels through here so a completed turn is recorded into shared
        // conversation memory EXACTLY once (a no-op when no ConversationSession is wired). Recorded
        // verbatim from whatever text actually reached the sink — including a contained-error
        // placeholder — because history is meant to mirror what the user actually saw, not a
        // filtered "clean answers only" view (see ConversationTurn's remarks).
        ForemanTurnResult Finish(ForemanStopReason reason)
        {
            var result = new ForemanTurnResult(answer.ToString(), reason, toolSteps);
            _conversation?.Append(query, result.Answer);
            return result;
        }

        // Both answer-stream sites can throw (llama token stream / injected prose func); contain them the
        // same way DecideAsync is contained so the turn always resolves and never propagates an exception.
        async Task<bool> TryStreamAnswerAsync()
        {
            try { await _brain.StreamAnswerAsync(ctx, Sink, ct); return true; }
            catch (Exception ex) when (ex is not OperationCanceledException)
            { Sink($"(agent error: {ex.GetType().Name})"); return false; }
        }

        while (true)
        {
            AgentDecision decision;
            try { decision = await _brain.DecideAsync(ctx, ct); }
            catch (Exception ex) when (ex is not OperationCanceledException)
            { Sink($"(agent error: {ex.GetType().Name})"); return Finish(ForemanStopReason.Error); }

            if (decision.IsAnswer || decision.Call is null)
            {
                if (!await TryStreamAnswerAsync()) return Finish(ForemanStopReason.Error);
                return Finish(ForemanStopReason.Answered);
            }

            if (toolSteps >= MaxToolSteps)
            {
                // Honest cap: never a silent loop. Ask the brain to answer with what it has.
                if (!await TryStreamAnswerAsync()) return Finish(ForemanStopReason.Error);
                return Finish(ForemanStopReason.StepCapReached);
            }

            var call = decision.Call.Value;
            var tool = FindTool(call.Tool);
            string result;
            using (var span = Trace.StartActivity($"agent.tool.{call.Tool}"))
            {
                EngineTrace.RecordAgentToolCall(call.Tool);
                if (tool is null) { result = "{\"error\":\"unknown tool\"}"; span?.SetTag("dni.agent.tool_known", false); }
                else { try { result = await tool.Invoke(call.ArgsJson, ct); } catch (Exception ex) when (ex is not OperationCanceledException) { result = $"{{\"error\":\"{ex.GetType().Name}\"}}"; } }
            }
            ctx.Steps.Add(new AgentStep(AgentStep.KindToolCall, call.Tool, call.ArgsJson));
            ctx.Steps.Add(new AgentStep(AgentStep.KindToolResult, call.Tool, result));
            toolSteps++;
        }
    }

    private ToolDefinition? FindTool(string name)
    {
        foreach (var t in _tools) if (t.Name == name) return t;
        return null;
    }
}
