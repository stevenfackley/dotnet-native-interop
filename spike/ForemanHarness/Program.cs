using System.Diagnostics;
using System.Text.Json;
using DotnetNativeInterop.Engine.Ai.Agent;

int passed = 0, failed = 0;
void Check(string name, bool ok) { if (ok) { passed++; Console.WriteLine($"[PASS] {name}"); } else { failed++; Console.WriteLine($"[FAIL] {name}"); } }

// Task 1: models round-trip through source-gen (no reflection)
var call = new ToolCall("engine_stats", "{}");
var json = JsonSerializer.Serialize(call, ForemanJsonContext.Default.ToolCall);
var back = JsonSerializer.Deserialize(json, ForemanJsonContext.Default.ToolCall)!;
Check("ToolCall round-trips via source-gen", back.Tool == "engine_stats" && back.ArgsJson == "{}");

var result = new ForemanTurnResult("done", ForemanStopReason.Answered, 2);
var rjson = JsonSerializer.Serialize(result, ForemanJsonContext.Default.ForemanTurnResult);
Check("ForemanTurnResult serializes StopReason", rjson.Contains("Answered"));

// Task 2: a tool definition invokes its delegate
var t = new ToolDefinition("engine_stats", "runtime memory + GC stats",
    Array.Empty<ToolParam>(), (_, _) => Task.FromResult("{\"heapMB\":12}"));
Check("ToolDefinition invokes delegate", (await t.Invoke("{}", default)).Contains("heapMB"));

// Task 3: grammar lists exactly the tool names + an answer alternative
var tools3 = new[] {
    new ToolDefinition("search_manuals", "d", new[]{ new ToolParam("query","string",true) }, (_,_)=>Task.FromResult("")),
    new ToolDefinition("engine_stats", "d", Array.Empty<ToolParam>(), (_,_)=>Task.FromResult("")),
};
var g = GbnfGrammar.Build(tools3);
// The tool name is a JSON string VALUE inside a GBNF string terminal, so its surrounding quotes are
// GBNF-escaped (\") in the raw grammar source — that's correct GBNF, not bare JSON text.
Check("grammar names each tool", g.Contains("\\\"search_manuals\\\"") && g.Contains("\\\"engine_stats\\\""));
Check("grammar has an answer alternative", g.Contains("answer"));
Check("grammar has a root rule", g.Contains("root ::="));

// Task 4: parse grammar-shaped emissions
var d1 = ToolCallParser.Parse("{\"tool\":\"engine_stats\",\"args\":{}}");
Check("parses a tool call", d1.Call is { } c1 && c1.Tool == "engine_stats" && !d1.IsAnswer);
var d2 = ToolCallParser.Parse("{\"answer\":\"the filter code is E3\"}");
Check("parses a final answer", d2.IsAnswer && d2.Call is null);
var d3 = ToolCallParser.Parse("not json");
Check("malformed -> answer (never throws)", d3.IsAnswer);

// Task 5: a scripted brain returns a tool call, then an answer once a tool result exists
var scripted = new ScriptedBrain(ctx =>
    ctx.Steps.Any(s => s.Kind == "tool_result")
        ? AgentDecision.Answer
        : AgentDecision.Tool(new ToolCall("engine_stats", "{}")));
var ctx0 = new AgentContext("q", new List<AgentStep>());
Check("brain: first decision is a tool call", scripted.DecideAsync(ctx0, default).Result.Call is not null);
ctx0.Steps.Add(new AgentStep("tool_result", "engine_stats", "{}"));
Check("brain: answers after a tool result", scripted.DecideAsync(ctx0, default).Result.IsAnswer);

// Task 6: loop dispatches a tool, streams an answer, and emits agent.* spans
// listen for agent spans
var seen = new List<string>();
var listener = new ActivityListener {
    ShouldListenTo = s => s.Name == "Dni.Engine",
    Sample = (ref ActivityCreationOptions<ActivityContext> _) => ActivitySamplingResult.AllData,
    ActivityStopped = a => seen.Add(a.OperationName),
};
ActivitySource.AddActivityListener(listener);

bool toolRan = false;
var tools6 = new[] { new ToolDefinition("engine_stats","d",Array.Empty<ToolParam>(),
    (_,_) => { toolRan = true; return Task.FromResult("{\"heapMB\":12}"); }) };
var brain6 = new ScriptedBrain(ctx => ctx.Steps.Any(s=>s.Kind=="tool_result") ? AgentDecision.Answer
    : AgentDecision.Tool(new ToolCall("engine_stats","{}")));
var agent6 = new ForemanAgent(tools6, brain6);
var sb6 = new System.Text.StringBuilder();
var res6 = await agent6.RunTurnAsync("how much memory?", s => sb6.Append(s), default);
Check("loop dispatched the tool", toolRan);
Check("loop streamed an answer", sb6.Length > 0);
Check("turn ended Answered with 1 tool step", res6.StopReason == ForemanStopReason.Answered && res6.ToolSteps == 1);
Check("emitted agent.turn span", seen.Contains("agent.turn"));
Check("emitted agent.tool.engine_stats span", seen.Contains("agent.tool.engine_stats"));

// Task 7: runaway tool-calling stops at the cap with an honest StopReason
int stats7 = 0;
var tools7 = new[] { new ToolDefinition("engine_stats","d",Array.Empty<ToolParam>(),(_,_)=>{stats7++;return Task.FromResult("{}");}) };
var greedy = new ScriptedBrain(_ => AgentDecision.Tool(new ToolCall("engine_stats","{}")));
var res7 = await new ForemanAgent(tools7, greedy).RunTurnAsync("q", _=>{}, default);
Check("stops at MaxToolSteps", stats7 == ForemanAgent.MaxToolSteps);
Check("reports StepCapReached", res7.StopReason == ForemanStopReason.StepCapReached);

Console.WriteLine($"== {passed}/{passed + failed} checks passed ==");
return failed == 0 ? 0 : 1;

sealed class ScriptedBrain(Func<AgentContext, AgentDecision> decide) : IAgentBrain
{
    public Task<AgentDecision> DecideAsync(AgentContext ctx, CancellationToken ct) => Task.FromResult(decide(ctx));
    public Task StreamAnswerAsync(AgentContext ctx, Action<string> sink, CancellationToken ct)
    { sink("scripted answer"); return Task.CompletedTask; }
    public string BackendBadge => "scripted (test)";
}
