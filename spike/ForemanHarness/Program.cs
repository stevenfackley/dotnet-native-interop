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

// ForemanHost.Agent now carries process-wide conversation memory (see Task 16+ below) — the prior
// compressor/manuals turn would otherwise get folded into THIS query's routing text and drag it off
// topic (proven deliberately in Task 18). This query is meant to stand alone, so reset first, exactly
// as a real client would if it fired two genuinely unrelated one-off questions back to back.
LanguageFeatureCatalog.Run("agent~reset");
var hostOut2 = new System.Text.StringBuilder();
var hostRes2 = await hostAgent.RunTurnAsync(
    "run the ping feature demo and show me its live result", s => hostOut2.Append(s), default);
Check("ForemanHost run_feature turn answered", hostRes2.StopReason == ForemanStopReason.Answered);
Check("ForemanHost emitted agent.tool.run_feature span", hostSpans.Contains("agent.tool.run_feature"));

// ============================================================================================
// Conversation memory (multi-turn follow-on): ConversationSession bounds, ForemanAgent plumbing,
// history-aware routing, and the agent~reset command-grammar seam.
// ============================================================================================

// Task 16: ConversationSession bounds — growth, FIFO eviction at MaxTurns, per-turn char clamping, Reset.
{
    var convo = new ConversationSession();
    Check("fresh session has no history", convo.Snapshot().Count == 0);

    convo.Append("q1", "a1");
    convo.Append("q2", "a2");
    var snap = convo.Snapshot();
    Check("history grows with each completed turn", snap.Count == 2);
    Check("history is oldest-first", snap[0].Query == "q1" && snap[1].Query == "q2");

    var evictConvo = new ConversationSession();
    var totalAppends = ConversationSession.MaxTurns + 3;
    for (var i = 0; i < totalAppends; i++) evictConvo.Append($"q{i}", $"a{i}");
    var evictSnap = evictConvo.Snapshot();
    Check($"history caps at MaxTurns ({ConversationSession.MaxTurns})", evictSnap.Count == ConversationSession.MaxTurns);
    Check("FIFO: oldest evicted first, newest retained",
        evictSnap[0].Query == $"q{totalAppends - ConversationSession.MaxTurns}"
        && evictSnap[^1].Query == $"q{totalAppends - 1}");

    var clampConvo = new ConversationSession();
    var longQuery = new string('x', ConversationSession.MaxQueryChars + 50);
    var longAnswer = new string('y', ConversationSession.MaxAnswerChars + 50);
    clampConvo.Append(longQuery, longAnswer);
    var clamped = clampConvo.Snapshot()[0];
    Check("over-long query is clamped", clamped.Query.Length < longQuery.Length && clamped.Query.Contains("truncated"));
    Check("over-long answer is clamped", clamped.Answer.Length < longAnswer.Length && clamped.Answer.Contains("truncated"));

    clampConvo.Reset();
    Check("Reset clears history", clampConvo.Snapshot().Count == 0);
}

// Task 17: ForemanAgent threads ConversationSession into AgentContext.History across turns — a
// recording brain observes exactly what ForemanAgent hands it, so this proves real plumbing, not a
// tautology: each turn streams a DIFFERENT, test-controlled answer, and we assert the NEXT turn's
// ctx.History carries THAT exact prior query/answer, then that Reset makes it disappear again.
{
    var seenContexts = new List<AgentContext>();
    var recordingBrain = new RecordingBrain(seenContexts);
    var memConvo = new ConversationSession();
    var memAgent = new ForemanAgent(Array.Empty<ToolDefinition>(), recordingBrain, memConvo);

    var t1 = new System.Text.StringBuilder();
    await memAgent.RunTurnAsync("search the manuals for fault E3", s => t1.Append(s), default);
    Check("turn 1 starts with empty history (fresh conversation)", seenContexts[0].History.Count == 0);
    Check("turn 1 streamed its own distinct answer", t1.ToString() == "turn-1-answer");

    var t2 = new System.Text.StringBuilder();
    await memAgent.RunTurnAsync("how do I clear THAT?", s => t2.Append(s), default);
    Check("turn 2 sees exactly turn 1 in history", seenContexts[1].History.Count == 1);
    Check("turn 2's history carries turn 1's EXACT query",
        seenContexts[1].History[0].Query == "search the manuals for fault E3");
    Check("turn 2's history carries turn 1's EXACT streamed answer",
        seenContexts[1].History[0].Answer == "turn-1-answer");

    memConvo.Reset();
    var t3 = new System.Text.StringBuilder();
    await memAgent.RunTurnAsync("how do I clear THAT?", s => t3.Append(s), default);
    Check("after Reset, the next turn has empty history again", seenContexts[2].History.Count == 0);
}

// Task 18: DeterministicRouter's history-aware routing with the REAL MiniLM encoder (same tools/
// threshold ForemanHost ships — calibrationTools/ForemanHost.RouterThreshold from Task 14 above).
// "how do I clear THAT?" alone shares no real words with any tool description, so it must NOT route on
// its own; only WITH the prior turn folded in must it clear the shipped threshold for search_manuals.
// This proves the "router memory" claim actually moves a real routing decision, not just that history
// is present in ctx.
{
    var bareFollowUp = "how do I clear THAT?";
    var withoutHistory = calibrationRouter.Route(bareFollowUp);
    var (bestToolBare, bestSimBare) = BestTool(bareFollowUp);
    Console.WriteLine($"[history] bare follow-up: best={bestToolBare} sim={bestSimBare:0.###} route={withoutHistory?.Tool ?? "(none)"}");
    Check("bare ambiguous follow-up does not route on its own (no keyword overlap)", withoutHistory is null);

    var priorTurnHint = "search the manuals for fault E3 " +
        "the manual says reset the panel breaker and re-energize the compressor contactor";
    var withHistory = calibrationRouter.Route(bareFollowUp, priorTurnHint);
    var (bestToolHint, bestSimHint) = BestTool($"{priorTurnHint}\n{bareFollowUp}");
    Console.WriteLine($"[history] with prior-turn hint: best={bestToolHint} sim={bestSimHint:0.###} route={withHistory?.Tool ?? "(none)"}");
    Check("folding the prior turn into routing text sends the SAME follow-up to search_manuals",
        withHistory?.Tool == "search_manuals");
}

// Task 18b: the off-topic-after-manuals guard. The router used to fold the ENTIRE prior turn (query +
// answer, up to ~800 chars of manual prose) into the routing text UNCONDITIONALLY, so after any manuals
// turn a decisively off-topic query was dragged above the threshold and false-routed to search_manuals
// (verified: "who won the world cup in 1998" scored 0.425 combined). The relevance gate (cosine(query,
// hint) >= 0.08) folds the hint in ONLY for a genuine continuation — the follow-up above (sim ~0.16 to the
// hint) still routes; these off-topic pivots (sim ~0.01 / negative) must now route to NOTHING.
{
    var priorManualsHint = "search the manuals for fault E3 " +
        "the manual says reset the panel breaker and re-energize the compressor contactor";
    string[] offTopicFollowUps =
    [
        "what's the weather in Tokyo tomorrow afternoon",
        "who won the world cup in 1998 and what was the final score",
    ];
    foreach (var offTopic in offTopicFollowUps)
    {
        var routed = calibrationRouter.Route(offTopic, priorManualsHint);
        Console.WriteLine($"[history] off-topic WITH manuals hint: route={routed?.Tool ?? "(none)"}  <- \"{offTopic}\"");
        Check("off-topic query does NOT route even with a prior manuals turn folded in (relevance-gated)",
            routed is null);
    }
}

// Task 19: full ForemanHost + agent~reset end-to-end — the REAL router brain (no GGUF on this box), the
// REAL ConversationSession wired via ForemanHost.Build, and the REAL command-grammar reset path
// (LanguageFeatureCatalog.Run("agent~reset"), the exact call dni_feature_run makes — see
// ShowcaseCommand.RunAgent). ToolSteps is the honest observable: a routed follow-up spends exactly 1
// tool step, an unrouted one goes straight to Answered with 0.
{
    LanguageFeatureCatalog.Run("agent~reset"); // clean slate regardless of what Task 15 above ran on hostAgent
    var ambiguousFollowUp = "how do I clear THAT?";

    var freshRes = await hostAgent.RunTurnAsync(ambiguousFollowUp, _ => { }, default);
    Check("fresh conversation: ambiguous follow-up with no history does not route to a tool",
        freshRes.ToolSteps == 0);

    await hostAgent.RunTurnAsync(
        "the compressor is overheating and keeps tripping the rooftop unit, what should I check?",
        _ => { }, default);

    var followUpRes = await hostAgent.RunTurnAsync(ambiguousFollowUp, _ => { }, default);
    Check("same ambiguous follow-up, now WITH real prior-turn context, routes to search_manuals",
        followUpRes.ToolSteps == 1);

    var resetRun = LanguageFeatureCatalog.Run("agent~reset");
    Check("agent~reset command reports ok", resetRun is { Ok: true } && resetRun.Result.Contains("reset"));

    var afterResetRes = await hostAgent.RunTurnAsync(ambiguousFollowUp, _ => { }, default);
    Check("after agent~reset, the same follow-up again has no history and does not route",
        afterResetRes.ToolSteps == 0);

    // Off-topic-after-manuals, end-to-end: a manuals turn then a decisively off-topic question must NOT
    // route (the Task 18b relevance gate, through the full ForemanHost.Agent + ConversationSession path).
    await hostAgent.RunTurnAsync(
        "the compressor is overheating and keeps tripping the rooftop unit, what should I check?", _ => { }, default);
    var offTopicRes = await hostAgent.RunTurnAsync(
        "who won the world cup in 1998 and what was the final score", _ => { }, default);
    Check("end-to-end: an off-topic question after a manuals turn does not route to a tool",
        offTopicRes.ToolSteps == 0);
    LanguageFeatureCatalog.Run("agent~reset"); // leave a clean slate for later tasks
}

// Task 20: the agent's run_feature tool is SCOPED to catalog demos — it must NOT be a back door into the
// command grammar (gclab storms, agent~reset, trust~/trace~/metrics~). Invokes the REAL, fully-wired
// run_feature tool ForemanHost ships (ForemanHost.Tools, guard and all — not a replicated double) with a
// command-grammar id, and proves BOTH halves: it returns the honest error, AND the operational command
// never actually ran. agent~reset is the sharpest probe — if the guard were absent the tool call would
// clear the process-wide conversation as a side effect; we prime real history, fire the guarded tool,
// and confirm the history SURVIVES (a follow-up still routes), so the reset genuinely did not execute.
{
    var runFeatureTool = ForemanHost.Tools.First(t => t.Name == "run_feature");

    // Guard returns the honest error, not a FeatureRun, for a command-grammar id.
    var guardedReset = await runFeatureTool.Invoke("{\"id\":\"agent~reset\"}", default);
    Check("run_feature tool rejects agent~reset with an honest error",
        guardedReset.Contains("error") && guardedReset.Contains("catalog demos"));
    var guardedStorm = await runFeatureTool.Invoke("{\"id\":\"gclab~preset_loh\"}", default);
    Check("run_feature tool rejects gclab~ (GC storm) with the same error", guardedStorm.Contains("catalog demos"));

    // Side-effect proof: prime real history, fire the guarded agent~reset through the tool, and confirm
    // the reset did NOT run — history survives, so a follow-up still routes.
    LanguageFeatureCatalog.Run("agent~reset"); // known-empty starting point
    await hostAgent.RunTurnAsync(
        "the compressor is overheating and keeps tripping the rooftop unit, what should I check?",
        _ => { }, default);
    var guardedResetAgain = await runFeatureTool.Invoke("{\"id\":\"agent~reset\"}", default);
    Check("run_feature{agent~reset} via the tool still returns the error even with live history",
        guardedResetAgain.Contains("catalog demos"));
    var afterGuardedReset = await hostAgent.RunTurnAsync("how do I clear THAT?", _ => { }, default);
    Check("the guarded run_feature{agent~reset} did NOT clear history (follow-up still routes)",
        afterGuardedReset.ToolSteps == 1);

    // A genuine catalog demo id still runs normally through the very same guarded tool.
    var realDemo = await runFeatureTool.Invoke("{\"id\":\"ping\"}", default);
    Check("run_feature tool still runs a real catalog demo (ping -> pong)",
        realDemo.Contains("pong") && realDemo.Contains("\"ok\":true"));

    LanguageFeatureCatalog.Run("agent~reset"); // leave the process-wide conversation clean for anything after
}

// ============================================================================================
// Task 21: agent.tool.<name> spans carry bounded dni.agent.tool_args/dni.agent.tool_result tags — the
// actual deliverable of this task. Before this, the drained span proved only name+timing; now it must
// prove what the tool call actually did (args in, result out), bounded+truncated, and honest on failure.
// Each sub-case drains the ring first so it only sees the spans IT produces (dni_trace_drain is a
// destructive read, same isolation pattern AgentSessionHarness uses around EngineTrace.Drain()).
// ============================================================================================
{
    EngineTrace.Drain(); // clean slate — Tasks 1-20 above left plenty of agent.* spans in the ring

    // 21a: a normal call well within both caps — tags carry the args/result VERBATIM, no truncation.
    var smallTool = new ToolDefinition("engine_stats", "d", Array.Empty<ToolParam>(),
        (_, _) => Task.FromResult("{\"heapMB\":12}"));
    var smallBrain = new ScriptedBrain(ctx => ctx.Steps.Any(s => s.Kind == "tool_result")
        ? AgentDecision.Answer : AgentDecision.Tool(new ToolCall("engine_stats", "{\"x\":1}")));
    await new ForemanAgent(new[] { smallTool }, smallBrain).RunTurnAsync("q", _ => { }, default);

    var drain21a = EngineTrace.Drain();
    var toolSpan21a = drain21a.Spans.First(s => s.Name == "agent.tool.engine_stats");
    Check("small tool call: tool_args tag carries the exact args", toolSpan21a.ToolArgs == "{\"x\":1}");
    Check("small tool call: tool_result tag carries the exact result", toolSpan21a.ToolResult == "{\"heapMB\":12}");
    Check("small tool call: no truncation marker present", !toolSpan21a.ToolResult!.Contains("truncated"));

    // 21b: a result LARGER than MaxToolResultChars (models a big search_manuals snippet dump) must be
    // truncated with the visible "…(truncated)" marker and bounded to exactly cap+markerLen — never the
    // full untruncated size — proving the drain payload really is bounded, not just hoped to be.
    var bigResult = new string('z', ForemanAgent.MaxToolResultChars + 200);
    var bigTool = new ToolDefinition("search_manuals", "d", new[] { new ToolParam("query", "string", true) },
        (_, _) => Task.FromResult(bigResult));
    var bigBrain = new ScriptedBrain(ctx => ctx.Steps.Any(s => s.Kind == "tool_result")
        ? AgentDecision.Answer : AgentDecision.Tool(new ToolCall("search_manuals", "{\"query\":\"E3\"}")));
    await new ForemanAgent(new[] { bigTool }, bigBrain).RunTurnAsync("q", _ => { }, default);

    var drain21b = EngineTrace.Drain();
    var toolSpan21b = drain21b.Spans.First(s => s.Name == "agent.tool.search_manuals");
    Check("oversized tool_result is truncated with the visible marker",
        toolSpan21b.ToolResult!.EndsWith("…(truncated)", StringComparison.Ordinal));
    Check("truncated tool_result is bounded to cap + marker length (never the full untruncated size)",
        toolSpan21b.ToolResult!.Length == ForemanAgent.MaxToolResultChars + "…(truncated)".Length);
    Check("truncated tool_result body is a genuine prefix of the real result (not garbage)",
        toolSpan21b.ToolResult!.StartsWith(bigResult[..ForemanAgent.MaxToolResultChars], StringComparison.Ordinal));

    // 21c: oversized ARGS are truncated the same way — an independent cap from the result's.
    var bigArgs = "{\"query\":\"" + new string('a', ForemanAgent.MaxToolArgsChars + 100) + "\"}";
    var argsTool = new ToolDefinition("search_manuals", "d", new[] { new ToolParam("query", "string", true) },
        (_, _) => Task.FromResult("{\"snippets\":[]}"));
    var argsBrain = new ScriptedBrain(ctx => ctx.Steps.Any(s => s.Kind == "tool_result")
        ? AgentDecision.Answer : AgentDecision.Tool(new ToolCall("search_manuals", bigArgs)));
    await new ForemanAgent(new[] { argsTool }, argsBrain).RunTurnAsync("q", _ => { }, default);

    var drain21c = EngineTrace.Drain();
    var toolSpan21c = drain21c.Spans.First(s => s.Name == "agent.tool.search_manuals");
    Check("oversized tool_args is truncated with the visible marker",
        toolSpan21c.ToolArgs!.EndsWith("…(truncated)", StringComparison.Ordinal));
    Check("truncated tool_args is bounded to cap + marker length",
        toolSpan21c.ToolArgs!.Length == ForemanAgent.MaxToolArgsChars + "…(truncated)".Length);

    // 21d: honesty — an UNKNOWN tool call's tool_result tag carries the real JSON error, never blank.
    var unknownBrain = new ScriptedBrain(_ => AgentDecision.Tool(new ToolCall("does_not_exist", "{}")));
    await new ForemanAgent(Array.Empty<ToolDefinition>(), unknownBrain).RunTurnAsync("q", _ => { }, default);
    var drain21d = EngineTrace.Drain();
    var toolSpan21d = drain21d.Spans.First(s => s.Name == "agent.tool.does_not_exist");
    Check("unknown tool: tool_args tag is still present (the attempted call, not blank)", toolSpan21d.ToolArgs == "{}");
    Check("unknown tool: tool_result tag carries the honest JSON error, never blank",
        toolSpan21d.ToolResult == "{\"error\":\"unknown tool\"}");

    // 21e: honesty — a tool that THROWS still tags tool_result with the error, never blank/omitted — the
    // strip must show a failing tool call as a failure, not silently drop its result.
    var throwingTool = new ToolDefinition("boom", "d", Array.Empty<ToolParam>(),
        (_, _) => throw new InvalidOperationException("kaboom"));
    var throwingToolBrain = new ScriptedBrain(ctx => ctx.Steps.Any(s => s.Kind == "tool_result")
        ? AgentDecision.Answer : AgentDecision.Tool(new ToolCall("boom", "{}")));
    await new ForemanAgent(new[] { throwingTool }, throwingToolBrain).RunTurnAsync("q", _ => { }, default);
    var drain21e = EngineTrace.Drain();
    var toolSpan21e = drain21e.Spans.First(s => s.Name == "agent.tool.boom");
    Check("throwing tool: tool_result tag carries the error type, never blank",
        toolSpan21e.ToolResult == "{\"error\":\"InvalidOperationException\"}");

    // 21f: a span for a DIFFERENT (non agent.tool.*) operation never carries these tags — proves the new
    // fields are opt-in per-span, not accidentally populated engine-wide.
    using (EngineTrace.StartSpan("some.other.op")) { }
    var drain21f = EngineTrace.Drain();
    var otherSpan = drain21f.Spans.First(s => s.Name == "some.other.op");
    Check("a non-agent.tool span never carries tool_args/tool_result",
        otherSpan.ToolArgs is null && otherSpan.ToolResult is null);
}

Console.WriteLine($"== {passed}/{passed + failed} checks passed ==");
return failed == 0 ? 0 : 1;

// Records the AgentContext ForemanAgent hands DecideAsync each turn (so a test can inspect ctx.History)
// and streams a distinct, turn-numbered answer each time (so history content-equality checks are
// non-tautological — see Task 17).
sealed class RecordingBrain(List<AgentContext> seen) : IAgentBrain
{
    private int _n;
    public Task<AgentDecision> DecideAsync(AgentContext ctx, CancellationToken ct)
    { seen.Add(ctx); return Task.FromResult(AgentDecision.Answer); }
    public Task StreamAnswerAsync(AgentContext ctx, Action<string> sink, CancellationToken ct)
    { sink($"turn-{++_n}-answer"); return Task.CompletedTask; }
    public string BackendBadge => "recording (test)";
}

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
