using System.Diagnostics;

namespace DotnetNativeInterop.Engine.Ai.Agent;

/// <summary>
/// The Foreman turn loop. Bounded, brain-agnostic. Emits an <c>agent.turn</c> span and an
/// <c>agent.tool.&lt;name&gt;</c> span per tool call so every step shows in the Wave B trace waterfall.
/// </summary>
public sealed class ForemanAgent
{
    public const int MaxToolSteps = 5;
    // Reuse the Wave B engine trace source so agent spans land in the same drain/waterfall.
    private static readonly ActivitySource Trace = new("Dni.Engine");

    private readonly IReadOnlyList<ToolDefinition> _tools;
    private readonly IAgentBrain _brain;

    public ForemanAgent(IReadOnlyList<ToolDefinition> tools, IAgentBrain brain)
    { _tools = tools; _brain = brain; }

    public string BackendBadge => _brain.BackendBadge;

    public async Task<ForemanTurnResult> RunTurnAsync(string query, Action<string> answerSink, CancellationToken ct)
    {
        using var turn = Trace.StartActivity("agent.turn");
        turn?.SetTag("dni.agent.query_len", query.Length);
        var ctx = new AgentContext(query, new List<AgentStep>());
        int toolSteps = 0;

        while (true)
        {
            AgentDecision decision;
            try { decision = await _brain.DecideAsync(ctx, ct); }
            catch (Exception ex) when (ex is not OperationCanceledException)
            { answerSink($"(agent error: {ex.GetType().Name})"); return new ForemanTurnResult("", ForemanStopReason.Error, toolSteps); }

            if (decision.IsAnswer || decision.Call is null)
            {
                await _brain.StreamAnswerAsync(ctx, answerSink, ct);
                return new ForemanTurnResult("", ForemanStopReason.Answered, toolSteps);
            }

            if (toolSteps >= MaxToolSteps)
            {
                // Honest cap: never a silent loop. Ask the brain to answer with what it has.
                await _brain.StreamAnswerAsync(ctx, answerSink, ct);
                return new ForemanTurnResult("", ForemanStopReason.StepCapReached, toolSteps);
            }

            var call = decision.Call.Value;
            var tool = FindTool(call.Tool);
            string result;
            using (var span = Trace.StartActivity($"agent.tool.{call.Tool}"))
            {
                if (tool is null) { result = "{\"error\":\"unknown tool\"}"; span?.SetTag("dni.agent.tool_known", false); }
                else { try { result = await tool.Invoke(call.ArgsJson, ct); } catch (Exception ex) when (ex is not OperationCanceledException) { result = $"{{\"error\":\"{ex.GetType().Name}\"}}"; } }
            }
            ctx.Steps.Add(new AgentStep("tool_call", call.Tool, call.ArgsJson));
            ctx.Steps.Add(new AgentStep("tool_result", call.Tool, result));
            toolSteps++;
        }
    }

    private ToolDefinition? FindTool(string name)
    {
        foreach (var t in _tools) if (t.Name == name) return t;
        return null;
    }
}
