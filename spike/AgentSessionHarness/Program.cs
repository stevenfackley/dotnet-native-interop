// Foreman-over-FFI proof harness. UnmanagedCallersOnly exports can't be called from managed code, so
// this drives the INTERNAL session-start logic dni_agent_session_start wraps directly: builds the
// same InferenceOrchestrator(ForemanLanguageModel(...)) + InferenceSession + SessionRegistry the real
// export constructs, and reads InferenceSession.Reader itself as a stand-in for the native
// dni_token_cb callback (a "fake C-callback-equivalent sink"). It acts as the CLIENT would: start,
// drain fragments, inspect the trailing status fragment, cancel, and free — exactly the sequence a
// native caller drives via dni_agent_session_start / dni_session_cancel / dni_session_free.
using System.Text.Json;
using DotnetNativeInterop.Engine;
using DotnetNativeInterop.Engine.Ai.Agent;
using DotnetNativeInterop.NativeBridge;

int passed = 0, failed = 0;
void Check(string name, bool ok) { if (ok) { passed++; Console.WriteLine($"[PASS] {name}"); } else { failed++; Console.WriteLine($"[FAIL] {name}"); } }

Console.WriteLine("== Foreman-over-FFI (dni_agent_session_start) proof harness ==");
EngineHost.Initialize();

// Local helper: drains a session's InferenceToken stream into a plain (index, text, isFinal) list —
// the exact shape dni_token_cb delivers to a native caller — using the session's own Reader in place
// of the real callback (which cannot be invoked without a native function pointer).
async Task<List<(int Index, string Text, bool IsFinal)>> DrainAsync(InferenceSession session)
{
    var got = new List<(int, string, bool)>();
    await foreach (var token in session.Reader.ReadAllAsync().ConfigureAwait(false))
    {
        got.Add((token.Index, token.Text, token.IsFinal));
    }

    return got;
}

(ForemanStopReason StopReason, int ToolSteps) ReadStatus(IReadOnlyList<(int Index, string Text, bool IsFinal)> tokens)
{
    var statusFragment = tokens[^2].Text; // last is always the empty is_final=1 marker; status precedes it
    var json = statusFragment[ForemanLanguageModel.StatusMarker.Length..];
    var status = JsonSerializer.Deserialize(json, ForemanJsonContext.Default.AgentSessionStatus);
    return (status.StopReason, status.ToolSteps);
}

// ============================================================================================
// Task 1: the REAL ForemanHost.Agent (router brain — no GGUF on this box) end-to-end through the
// exact session/registry path dni_agent_session_start wraps.
// ============================================================================================
{
    var request = new InferenceRequest("the compressor is overheating and keeps tripping the rooftop unit, what should I check?");
    var orchestrator = new InferenceOrchestrator(new ForemanLanguageModel(ForemanHost.Agent));
    var session = InferenceSession.Start(orchestrator, request);
    var id = SessionRegistry.Add(session);
    Check("session id is a valid positive handle", id > 0);

    var tokens = await DrainAsync(session);

    Check("tokens arrive in strictly increasing index order",
        tokens.Select(t => t.Index).SequenceEqual(Enumerable.Range(0, tokens.Count)));
    Check("true final marker (is_final=1) is the last token and is empty",
        tokens[^1].IsFinal && tokens[^1].Text.Length == 0);
    Check("the status fragment itself is NOT the is_final=1 marker",
        !tokens[^2].IsFinal);
    Check("the status fragment carries the StatusMarker prefix",
        tokens[^2].Text.StartsWith(ForemanLanguageModel.StatusMarker, StringComparison.Ordinal));

    var answerFragments = tokens.Take(tokens.Count - 2).ToArray();
    Check("at least one real answer fragment streamed before the status", answerFragments.Length > 0);
    Check("no answer fragment is mistaken for a status fragment",
        answerFragments.All(t => !t.Text.StartsWith(ForemanLanguageModel.StatusMarker, StringComparison.Ordinal)));

    var answer = string.Concat(answerFragments.Select(t => t.Text));
    Check("streamed answer is grounded manual prose", answer.Length > 0 && answer.Contains("manual"));

    var (stopReason, toolSteps) = ReadStatus(tokens);
    Check("real turn status is Answered", stopReason == ForemanStopReason.Answered);
    Check("real turn used exactly one real tool", toolSteps == 1);

    var removed = await SessionRegistry.RemoveAsync(id);
    Check("dni_session_free-equivalent removes the completed session", removed);

    // Wave B's existing dni_trace_drain already drains this same ring — proving agent.* spans are in
    // it here proves a real client would see them for free, with zero new tracing plumbing.
    var drain = EngineTrace.Drain();
    var spanNames = drain.Spans.Select(s => s.Name).ToHashSet(StringComparer.Ordinal);
    Console.WriteLine($"   drained {drain.Spans.Count} spans; agent-related: {string.Join(", ", spanNames.Where(n => n.StartsWith("agent.", StringComparison.Ordinal)).OrderBy(n => n, StringComparer.Ordinal))}");
    Check("dni_trace_drain's ring carries an agent.turn span", spanNames.Contains("agent.turn"));
    Check("dni_trace_drain's ring carries an agent.tool.search_manuals span", spanNames.Contains("agent.tool.search_manuals"));
}

// ============================================================================================
// Task 2: a runaway tool-calling brain must report StepCapReached honestly, not Answered — proves the
// status fragment distinguishes a capped turn from a clean one (same ForemanLanguageModel adapter,
// a differently-wired ForemanAgent — mirrors how ForemanHarness itself exercises ForemanAgent's cap
// with a ScriptedBrain rather than needing to make the real GGUF-less router brain misbehave).
// ============================================================================================
{
    var callCount = 0;
    var greedyTool = new ToolDefinition("count", "increments a counter", Array.Empty<ToolParam>(),
        (_, _) => { callCount++; return Task.FromResult("{}"); });
    var greedyBrain = new ScriptedBrain(_ => AgentDecision.Tool(new ToolCall("count", "{}")));
    var cappedAgent = new ForemanAgent([greedyTool], greedyBrain);

    var orchestrator = new InferenceOrchestrator(new ForemanLanguageModel(cappedAgent));
    var session = InferenceSession.Start(orchestrator, new InferenceRequest("loop forever"));
    var id = SessionRegistry.Add(session);

    var tokens = await DrainAsync(session);
    var (stopReason, toolSteps) = ReadStatus(tokens);
    Check("runaway tool loop reports StepCapReached (never silently Answered)", stopReason == ForemanStopReason.StepCapReached);
    Check("runaway tool loop stopped at exactly MaxToolSteps", toolSteps == ForemanAgent.MaxToolSteps && callCount == ForemanAgent.MaxToolSteps);

    await SessionRegistry.RemoveAsync(id);
}

// ============================================================================================
// Task 3: a brain whose answer stream throws must report Error honestly, not Answered.
// ============================================================================================
{
    var throwingAgent = new ForemanAgent(Array.Empty<ToolDefinition>(), new ThrowingAnswerBrain());
    var orchestrator = new InferenceOrchestrator(new ForemanLanguageModel(throwingAgent));
    var session = InferenceSession.Start(orchestrator, new InferenceRequest("q"));
    var id = SessionRegistry.Add(session);

    var tokens = await DrainAsync(session);
    var (stopReason, _) = ReadStatus(tokens);
    Check("a brain whose answer stream throws reports Error (never silently Answered)", stopReason == ForemanStopReason.Error);

    await SessionRegistry.RemoveAsync(id);
}

// ============================================================================================
// Task 4: dni_session_cancel-equivalent — cancelling mid-turn stops the stream cleanly (no exception
// surfaced to the consumer, no further fragments after the cancel point), mirroring how
// InferenceSession/PumpAsync already treat cancellation for dni_session_start/dni_rag_session_start.
// ============================================================================================
{
    var slowAgent = new ForemanAgent(Array.Empty<ToolDefinition>(), new SlowBrain());
    var orchestrator = new InferenceOrchestrator(new ForemanLanguageModel(slowAgent));
    var session = InferenceSession.Start(orchestrator, new InferenceRequest("q"));
    var id = SessionRegistry.Add(session);

    var reader = session.Reader;
    var first = await reader.ReadAsync();
    Check("cancel test: first fragment observed before cancelling", first.Text == "partial");

    SessionRegistry.TryGet(id, out var live);
    live!.Cancel(); // dni_session_cancel-equivalent

    var sawPostCancelFragment = false;
    var cancelledCleanly = true;
    try
    {
        await foreach (var token in reader.ReadAllAsync())
        {
            if (token.Text == "more")
            {
                sawPostCancelFragment = true;
            }
        }
    }
    catch (Exception)
    {
        cancelledCleanly = false;
    }

    Check("cancel: reader completes without surfacing an exception to the consumer", cancelledCleanly);
    Check("cancel: the post-cancel fragment never arrives", !sawPostCancelFragment);

    var removed = await SessionRegistry.RemoveAsync(id);
    Check("dni_session_free-equivalent removes a cancelled session", removed);
}

Console.WriteLine($"== {passed}/{passed + failed} checks passed ==");
return failed == 0 ? 0 : 1;

// -----------------------------------------------------------------------------------------------
// Test-only brains (mirror spike/ForemanHarness's own ScriptedBrain/ThrowingAnswerBrain doubles).
// -----------------------------------------------------------------------------------------------

sealed class ScriptedBrain(Func<AgentContext, AgentDecision> decide) : IAgentBrain
{
    public Task<AgentDecision> DecideAsync(AgentContext ctx, CancellationToken ct) => Task.FromResult(decide(ctx));
    public Task StreamAnswerAsync(AgentContext ctx, Action<string> sink, CancellationToken ct)
    { sink("capped answer"); return Task.CompletedTask; }
    public string BackendBadge => "scripted (harness)";
}

sealed class ThrowingAnswerBrain : IAgentBrain
{
    public Task<AgentDecision> DecideAsync(AgentContext ctx, CancellationToken ct) => Task.FromResult(AgentDecision.Answer);
    public Task StreamAnswerAsync(AgentContext ctx, Action<string> sink, CancellationToken ct)
        => throw new InvalidOperationException("answer stream failed");
    public string BackendBadge => "throwing (harness)";
}

sealed class SlowBrain : IAgentBrain
{
    public Task<AgentDecision> DecideAsync(AgentContext ctx, CancellationToken ct) => Task.FromResult(AgentDecision.Answer);
    public async Task StreamAnswerAsync(AgentContext ctx, Action<string> sink, CancellationToken ct)
    {
        sink("partial");
        await Task.Delay(TimeSpan.FromSeconds(5), ct).ConfigureAwait(false);
        sink("more"); // must never be observed by a caller that cancelled after "partial"
    }
    public string BackendBadge => "slow (harness)";
}
