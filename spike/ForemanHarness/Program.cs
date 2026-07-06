using System.Diagnostics;
using System.Numerics.Tensors;
using System.Text.Json;
using DotnetNativeInterop.Engine;
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
// Empty tool set: answer-only grammar, never a bare "toolcall ::=" (invalid GBNF).
var gEmpty = GbnfGrammar.Build(Array.Empty<ToolDefinition>());
Check("empty tool set -> answer-only grammar", gEmpty.Contains("root ::= answer") && !gEmpty.Contains("toolcall"));

// Task 4: parse grammar-shaped emissions
var d1 = ToolCallParser.Parse("{\"tool\":\"engine_stats\",\"args\":{}}");
Check("parses a tool call", d1.Call is { } c1 && c1.Tool == "engine_stats" && !d1.IsAnswer);
var d2 = ToolCallParser.Parse("{\"answer\":\"the filter code is E3\"}");
Check("parses a final answer", d2.IsAnswer && d2.Call is null);
var d3 = ToolCallParser.Parse("not json");
Check("malformed -> answer (never throws)", d3.IsAnswer);
// Untrusted-model-output safety net: valid JSON that is not a tool-object must collapse to an answer,
// never throw. JsonElement.TryGetProperty throws InvalidOperationException on a scalar/array (NOT a
// JsonException), and GetString throws on a non-string tool value — both must be handled.
Check("scalar string JSON -> answer (no throw)", ToolCallParser.Parse("\"hello there\"").IsAnswer);
Check("number JSON -> answer (no throw)", ToolCallParser.Parse("42").IsAnswer);
Check("array JSON -> answer (no throw)", ToolCallParser.Parse("[1,2,3]").IsAnswer);
Check("non-string tool value -> answer (no throw)", ToolCallParser.Parse("{\"tool\":42,\"args\":{}}").IsAnswer);

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
// #5: the serialized result carries the streamed answer (not a misleading empty string).
Check("turn result carries the streamed answer", res6.Answer.Length > 0 && res6.Answer == sb6.ToString());

// Task 7: runaway tool-calling stops at the cap with an honest StopReason
int stats7 = 0;
var tools7 = new[] { new ToolDefinition("engine_stats","d",Array.Empty<ToolParam>(),(_,_)=>{stats7++;return Task.FromResult("{}");}) };
var greedy = new ScriptedBrain(_ => AgentDecision.Tool(new ToolCall("engine_stats","{}")));
var res7 = await new ForemanAgent(tools7, greedy).RunTurnAsync("q", _=>{}, default);
Check("stops at MaxToolSteps", stats7 == ForemanAgent.MaxToolSteps);
Check("reports StepCapReached", res7.StopReason == ForemanStopReason.StepCapReached);

// Task 7b: a brain whose StreamAnswerAsync throws must resolve the turn as Error, never propagate.
var throwingBrain = new ThrowingAnswerBrain();
var res7b = await new ForemanAgent(Array.Empty<ToolDefinition>(), throwingBrain).RunTurnAsync("q", _=>{}, default);
Check("StreamAnswerAsync throw -> StopReason.Error (turn never throws)", res7b.StopReason == ForemanStopReason.Error);

// Task 8: router picks the nearest tool by cosine; generic query -> none
// Dim 2 is a deliberately unrelated axis for generic queries: cosine against either tool's
// one-hot vector (dims 0/1) is exactly 0, unlike an equal-weight vector which is ~0.577 against
// any one-hot axis (1/sqrt(3)) and would wrongly clear a 0.5 threshold.
float[] Emb(string s) => s.Contains("memory") ? new[]{1f,0f,0f}
                        : s.Contains("manual") || s.Contains("fault") ? new[]{0f,1f,0f}
                        : new[]{0f,0f,1f};
var rtools = new[] {
    new ToolDefinition("engine_stats","current memory and gc stats",Array.Empty<ToolParam>(),(_,_)=>Task.FromResult("")),
    new ToolDefinition("search_manuals","search the maintenance manuals for a fault",new[]{new ToolParam("query","string",true)},(_,_)=>Task.FromResult("")),
};
var router = new DeterministicRouter(rtools, Emb, threshold: 0.5f);
Check("routes memory query -> engine_stats", router.Route("how much memory?")?.Tool == "engine_stats");
Check("routes fault query -> search_manuals", router.Route("what is fault code E3?")?.Tool == "search_manuals");
Check("generic query -> no tool", router.Route("hello there") is null);

// Task 9a: RouterBrain drives one tool then answers
var rb = new RouterBrain(router, prompt => $"answer for [{prompt}]");
var rbCtx = new AgentContext("how much memory?", new List<AgentStep>());
var rbD1 = await rb.DecideAsync(rbCtx, default);
Check("RouterBrain first decides a tool", rbD1.Call?.Tool == "engine_stats");
rbCtx.Steps.Add(new AgentStep("tool_result","engine_stats","{\"heapMB\":12}"));
Check("RouterBrain then answers", (await rb.DecideAsync(rbCtx, default)).IsAnswer);
Check("RouterBrain badge is honest", rb.BackendBadge.Contains("scripted") || rb.BackendBadge.Contains("routing"));

// Task 9b: GrammarBrain parses a constrained completion
var gb = new GrammarBrain(
    complete: (_, _) => Task.FromResult("{\"tool\":\"engine_stats\",\"args\":{}}"),
    streamAnswer: (_, sink, _) => { sink("grounded"); return Task.CompletedTask; },
    badge: "on-device LLM (grammar-constrained)");
Check("GrammarBrain parses a tool call", (await gb.DecideAsync(new AgentContext("q",new()), default)).Call?.Tool == "engine_stats");

// Task 10: engine_stats returns real telemetry JSON
var realTools = ForemanTools.Build(
    searchManuals: q => Task.FromResult("{\"snippets\":[]}"),   // inject light doubles for the two heavy ops
    runFeature:    id => Task.FromResult("{\"ok\":true}"),
    engineStats:   () => "{\"heapMB\":7}");
Check("Build yields 3 tools", realTools.Count == 3);
var statsTool = realTools.First(t => t.Name == "engine_stats");
Check("engine_stats returns telemetry json", (await statsTool.Invoke("{}", default)).Contains("heapMB"));
var searchTool = realTools.First(t => t.Name == "search_manuals");
Check("search_manuals passes the query arg", (await searchTool.Invoke("{\"query\":\"E3\"}", default)).Contains("snippets"));

// Task 11: full turn through ForemanAgent with the router brain and real telemetry
// EngineTelemetry.Snapshot() returns the EngineStats record, not a JSON string — ForemanTools.Build's
// engineStats delegate is Func<string>, so the real binding is EngineTelemetry.SnapshotJson(), the
// existing public wrapper that serializes Snapshot() via the engine's own TelemetryJsonContext
// source-gen context. (Divergence from the plan's placeholder call, confirmed by reading EngineTelemetry.cs.)
var e2eTools = ForemanTools.Build(
    q => Task.FromResult("{\"snippets\":[\"clear code E3 by resetting the panel\"]}"),
    id => Task.FromResult("{\"ok\":true}"),
    DotnetNativeInterop.Engine.EngineTelemetry.SnapshotJson);
float[] Emb2(string s) => s.Contains("memory") ? new[]{1f,0f} : new[]{0f,1f};
var e2eRouter = new DeterministicRouter(e2eTools, Emb2, 0.5f);
var e2eBrain = new RouterBrain(e2eRouter, prompt => $"Based on the manual: {prompt}");
var e2eAgent = new ForemanAgent(e2eTools, e2eBrain);
var e2eOut = new System.Text.StringBuilder();
var e2eRes = await e2eAgent.RunTurnAsync("how much memory?", s => e2eOut.Append(s), default);
Check("e2e turn answered", e2eRes.StopReason == ForemanStopReason.Answered);
Check("e2e produced an answer", e2eOut.Length > 0);
Check("e2e badge discloses no LLM", e2eAgent.BackendBadge.Contains("no on-device LLM"));

// Task 11b: ForemanTools genuinely bound to the real engine_stats accessor end-to-end (not just via the
// factory test's light double) — proves the real EngineTelemetry.SnapshotJson binding produces usable
// tool-result JSON when actually invoked through a ToolDefinition.
var realStatsTool = e2eTools.First(tl => tl.Name == "engine_stats");
var realStatsJson = await realStatsTool.Invoke("{}", default);
Check("real engine_stats tool returns live telemetry JSON", realStatsJson.Contains("gcGen0") && realStatsJson.Contains("uptimeMs"));

// ============================================================================================
// Host wiring (ForemanHost): the REAL search_manuals + run_feature bindings, not light doubles.
// ============================================================================================

// Task 12: search_manuals' real binding — SemanticSearch.Default over the bundled "manuals" corpus
// (the exact call ForemanHost.SearchManualsAsync makes) returns genuine ONNX-encoded snippets.
var manualsHits = SemanticSearch.Default.Search(
    "the compressor does not energize and the rooftop unit stays idle", "manuals", topK: 3);
Check("real manuals search returns hits", manualsHits.Length > 0);
Check("real manuals search hits carry non-empty text", manualsHits.All(h => h.Text.Length > 0));
var manualsJson = AiJson.Serialize(manualsHits);
Check("real manuals search JSON carries text+score", manualsJson.Contains("\"text\"") && manualsJson.Contains("\"score\""));

// Task 13: run_feature's real binding — LanguageFeatureCatalog.Run(id) + FeatureRun serialized via
// ForemanJsonContext (the exact call + serializer ForemanHost.RunFeatureAsync makes) round-trips a
// genuine, known feature run (no reflection: ForemanJsonContext.FeatureRun is the Engine-side
// source-gen registration added alongside this binding).
var pingRun = LanguageFeatureCatalog.Run("ping");
Check("run_feature real op executes a known feature", pingRun is { Ok: true, Result: "pong" });
var pingJson = JsonSerializer.Serialize(pingRun, ForemanJsonContext.Default.FeatureRun);
Check("run_feature JSON round-trips via ForemanJsonContext.FeatureRun",
    pingJson.Contains("\"result\":\"pong\"") && pingJson.Contains("\"ok\":true"));

// Task 14: DeterministicRouter calibrated against REAL MiniLM embeddings (SemanticSearch.Default.Embed)
// instead of the fake one-hot vectors Task 8 uses — proves ForemanHost's SHIPPED threshold
// (ForemanHost.RouterThreshold) actually separates real tool descriptions from real queries: all three
// positives route AND a genuinely off-topic query routes to NOTHING. calibrationRouter mirrors
// ForemanHost's own router construction exactly (same tools, same encoder, same threshold constant).
var calibrationTools = ForemanTools.Build(
    q => Task.FromResult(AiJson.Serialize(SemanticSearch.Default.Search(q, "manuals", 3))),
    id => Task.FromResult(JsonSerializer.Serialize(LanguageFeatureCatalog.Run(id), ForemanJsonContext.Default.FeatureRun)),
    EngineTelemetry.SnapshotJson);
var calibrationRouter = new DeterministicRouter(calibrationTools, SemanticSearch.Default.Embed, ForemanHost.RouterThreshold);

// Diagnostic: the max cosine any tool description scores against each query — the exact quantity the
// router thresholds on — printed so the shipped ForemanHost.RouterThreshold is evidence-based, not a
// magic number. Uses the REAL encoder end-to-end.
var toolDescVecs = calibrationTools.Select(t => (t.Name, Vec: SemanticSearch.Default.Embed(t.Description))).ToArray();
(string Tool, float Sim) BestTool(string q)
{
    var qv = SemanticSearch.Default.Embed(q);
    return toolDescVecs
        .Select(t => (t.Name, Sim: TensorPrimitives.CosineSimilarity(qv, t.Vec)))
        .OrderByDescending(x => x.Sim)
        .First();
}

Console.WriteLine($"[calibration] shipped RouterThreshold = {ForemanHost.RouterThreshold:0.###}");
(string Query, string ExpectTool)[] calibrationCases =
[
    ("the compressor is overheating and keeps tripping the rooftop unit, what should I check?", "search_manuals"),
    ("how much heap memory and how many GC collections has the runtime done?", "engine_stats"),
    ("run the ping feature demo and show me its live result", "run_feature"),
];
foreach (var (query, expectTool) in calibrationCases)
{
    var (bestTool, bestSim) = BestTool(query);
    var pick = calibrationRouter.Route(query);
    Console.WriteLine($"[calibration] +  best={bestTool} sim={bestSim:0.###} route={pick?.Tool ?? "(none)"}  <- \"{query}\"");
    Check($"real router routes '{expectTool}'-style query to {expectTool}", pick?.Tool == expectTool);
}

// The NEGATIVE case (the half Task 8 only proved with fake one-hot vectors): a genuinely off-topic
// query, embedded with the REAL encoder, must fall below the shipped threshold and route to NO tool —
// else the router would false-route unrelated questions into HVAC search. Two independent off-topic
// probes so the guarantee isn't a single lucky sample.
string[] offTopicQueries =
[
    "what's the weather in Tokyo tomorrow afternoon",
    "who won the world cup in 1998 and what was the final score",
];
foreach (var offTopic in offTopicQueries)
{
    var (bestTool, bestSim) = BestTool(offTopic);
    var pick = calibrationRouter.Route(offTopic);
    Console.WriteLine($"[calibration] -  best={bestTool} sim={bestSim:0.###} route={pick?.Tool ?? "(none)"}  <- \"{offTopic}\"");
    Check("real router rejects an off-topic query (no tool at shipped threshold)", pick is null);
}

// Task 15: ForemanHost end-to-end — the fully-wired agent the host hands out. No GGUF is bundled on
// this Windows dev box, so brain selection honestly falls back to RouterBrain (the GrammarBrain seam
// is exercised separately below by construction, not by a live model here).
var hostAgent = ForemanHost.Agent;
Check("ForemanHost badge is honest (no GGUF present on this host)",
    hostAgent.BackendBadge.Contains("no on-device LLM"));

var hostSpans = new List<string>();
var hostListener = new ActivityListener
{
    ShouldListenTo = s => s.Name == "Dni.Engine",
    Sample = (ref ActivityCreationOptions<ActivityContext> _) => ActivitySamplingResult.AllData,
    ActivityStopped = a => hostSpans.Add(a.OperationName),
};
ActivitySource.AddActivityListener(hostListener);

var hostOut = new System.Text.StringBuilder();
var hostRes = await hostAgent.RunTurnAsync(
    "the compressor is overheating and keeps tripping the rooftop unit, what should I check?",
    s => hostOut.Append(s), default);
Check("ForemanHost turn answered", hostRes.StopReason == ForemanStopReason.Answered);
Check("ForemanHost turn used exactly one real tool", hostRes.ToolSteps == 1);
Check("ForemanHost turn produced a grounded answer", hostOut.Length > 0 && hostOut.ToString().Contains("manual"));
Check("ForemanHost emitted agent.turn span", hostSpans.Contains("agent.turn"));
Check("ForemanHost emitted agent.tool.search_manuals span", hostSpans.Contains("agent.tool.search_manuals"));

var hostOut2 = new System.Text.StringBuilder();
var hostRes2 = await hostAgent.RunTurnAsync(
    "run the ping feature demo and show me its live result", s => hostOut2.Append(s), default);
Check("ForemanHost run_feature turn answered", hostRes2.StopReason == ForemanStopReason.Answered);
Check("ForemanHost emitted agent.tool.run_feature span", hostSpans.Contains("agent.tool.run_feature"));

Console.WriteLine($"== {passed}/{passed + failed} checks passed ==");
return failed == 0 ? 0 : 1;

sealed class ScriptedBrain(Func<AgentContext, AgentDecision> decide) : IAgentBrain
{
    public Task<AgentDecision> DecideAsync(AgentContext ctx, CancellationToken ct) => Task.FromResult(decide(ctx));
    public Task StreamAnswerAsync(AgentContext ctx, Action<string> sink, CancellationToken ct)
    { sink("scripted answer"); return Task.CompletedTask; }
    public string BackendBadge => "scripted (test)";
}

// Decides to answer immediately, but its answer stream throws — models a llama token stream / prose
// func blowing up mid-answer. RunTurnAsync must contain this and resolve the turn as Error.
sealed class ThrowingAnswerBrain : IAgentBrain
{
    public Task<AgentDecision> DecideAsync(AgentContext ctx, CancellationToken ct) => Task.FromResult(AgentDecision.Answer);
    public Task StreamAnswerAsync(AgentContext ctx, Action<string> sink, CancellationToken ct)
        => throw new InvalidOperationException("answer stream failed");
    public string BackendBadge => "throwing (test)";
}
