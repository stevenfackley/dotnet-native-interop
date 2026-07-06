# Foreman Agent — Plan A (Engine Core) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Windows-verifiable managed core of the Foreman on-device tool-calling agent — the hand-rolled agent loop, three tools, GBNF grammar generation, the deterministic-router fallback brain, and `agent.*` trace spans — all provable against `MockLanguageModel` with no device and no llama.

**Architecture:** Engine-hosted, brain-abstracted. `ForemanAgent` runs a bounded loop over an `IAgentBrain` that yields a decision (tool call or final answer) each step; two brains implement it — `GrammarBrain` (DniChatClient + GBNF + parser, the llama path) and `RouterBrain` (deterministic embedding router + `ExtractiveLanguageModel`, the no-GGUF fallback). Tool calls dispatch to three `ToolDefinition` delegates bound to existing engine ops, each emitting an `agent.tool.*` `ActivitySource` span. No `FunctionInvokingChatClient` (reflection-JSON, AOT-unproven) — the grammar makes tool calls parseable directly. Source-gen JSON throughout.

**Tech Stack:** .NET 10, C#, `System.Diagnostics.ActivitySource` (the Wave B `EngineTrace` source), `System.Text.Json` source-gen, `Microsoft.Extensions.AI.Abstractions` (via the already-built `DniChatClient`), `System.Numerics.Tensors` (router cosine). Tests via a `spike/ForemanHarness` console (repo has no .NET test project — matches `spike/DniChatClientHarness`/`WaveBHarness`).

**Spec:** `docs/superpowers/specs/2026-07-06-foreman-ondevice-agent-design.md`. This plan is **Plan A only** — the engine core. Plan B (llama.cpp GBNF shim gate, device), Plan C (Android UI), Plan D (iOS UI) follow as separate plans.

**Depends on:** `feat/meai-ichatclient` (`core/DotnetNativeInterop.Engine/Ai/Meai/DniChatClient.cs`) merged to the base branch.

---

## File Structure

All new engine files under `core/DotnetNativeInterop.Engine/Ai/Agent/`:

- `ForemanModels.cs` — value types (`ToolCall`, `AgentDecision`, `AgentStep`, `ForemanTurnResult`, `ForemanStopReason`) + `ForemanJsonContext` source-gen context.
- `ToolDefinition.cs` — `ToolDefinition`, `ToolParam` (schema + invoke delegate).
- `GbnfGrammar.cs` — `GbnfGrammar.Build(tools)` → GBNF string.
- `ToolCallParser.cs` — `ToolCallParser.Parse(text)` → `AgentDecision`.
- `IAgentBrain.cs` — brain abstraction + `AgentContext`.
- `DeterministicRouter.cs` — embedding-similarity query→tool.
- `RouterBrain.cs`, `GrammarBrain.cs` — the two `IAgentBrain` impls.
- `ForemanTools.cs` — the three real `ToolDefinition`s bound to engine ops.
- `ForemanAgent.cs` — the loop.

New harness: `spike/ForemanHarness/ForemanHarness.csproj`, `spike/ForemanHarness/Program.cs`.

Conventions (match the repo): source-gen JSON only (no reflection), `CultureInfo.InvariantCulture`, XML-doc density like `Showcase/*.cs`, exception containment, **Conventional Commits, never any AI attribution**.

---

## Task 1: Foreman value types + source-gen JSON context

**Files:**
- Create: `core/DotnetNativeInterop.Engine/Ai/Agent/ForemanModels.cs`
- Test: `spike/ForemanHarness/Program.cs` (created here; grows each task)
- Create: `spike/ForemanHarness/ForemanHarness.csproj`

- [ ] **Step 1: Write the harness project + failing test**

`spike/ForemanHarness/ForemanHarness.csproj` (mirror `spike/DniChatClientHarness/DniChatClientHarness.csproj` — same TFM, references `core/DotnetNativeInterop.Engine/DotnetNativeInterop.Engine.csproj`, `<Nullable>enable</Nullable>`, `OutputType=Exe`).

`spike/ForemanHarness/Program.cs`:
```csharp
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

Console.WriteLine($"== {passed}/{passed + failed} checks passed ==");
return failed == 0 ? 0 : 1;
```

- [ ] **Step 2: Run to verify it fails**

Run: `dotnet run --project spike/ForemanHarness -c Release`
Expected: compile error — `ToolCall`/`ForemanJsonContext` not defined.

- [ ] **Step 3: Implement `ForemanModels.cs`**

```csharp
using System.Text.Json.Serialization;

namespace DotnetNativeInterop.Engine.Ai.Agent;

/// <summary>A model-selected tool invocation. <paramref name="ArgsJson"/> is a JSON object string.</summary>
public readonly record struct ToolCall(string Tool, string ArgsJson);

/// <summary>Why a turn ended.</summary>
public enum ForemanStopReason { Answered, StepCapReached, Error }

/// <summary>One recorded step in a turn (a tool call + its result, or the final answer).</summary>
public readonly record struct AgentStep(string Kind, string Detail, string? Result);

/// <summary>The outcome of a single Foreman turn.</summary>
public readonly record struct ForemanTurnResult(string Answer, ForemanStopReason StopReason, int ToolSteps);

/// <summary>A decision produced by an <see cref="IAgentBrain"/>: either a tool call or a final answer.</summary>
public readonly record struct AgentDecision(ToolCall? Call, bool IsAnswer)
{
    public static AgentDecision Tool(ToolCall c) => new(c, false);
    public static readonly AgentDecision Answer = new(null, true);
}

[JsonSourceGenerationOptions(PropertyNamingPolicy = JsonKnownNamingPolicy.CamelCase)]
[JsonSerializable(typeof(ToolCall))]
[JsonSerializable(typeof(AgentStep))]
[JsonSerializable(typeof(ForemanTurnResult))]
[JsonSerializable(typeof(ForemanStopReason))]
public partial class ForemanJsonContext : System.Text.Json.Serialization.JsonSerializerContext { }
```

- [ ] **Step 4: Run to verify it passes**

Run: `dotnet run --project spike/ForemanHarness -c Release`
Expected: `[PASS] ToolCall round-trips…`, `[PASS] ForemanTurnResult…`, `== 2/2 checks passed ==`.

- [ ] **Step 5: Commit**

```
git add core/DotnetNativeInterop.Engine/Ai/Agent/ForemanModels.cs spike/ForemanHarness
git commit -m "feat(engine): Foreman value types + source-gen JSON context"
```

---

## Task 2: ToolDefinition + ToolParam

**Files:**
- Create: `core/DotnetNativeInterop.Engine/Ai/Agent/ToolDefinition.cs`
- Test: `spike/ForemanHarness/Program.cs`

- [ ] **Step 1: Add failing test** (append before the summary line in `Program.cs`):
```csharp
// Task 2: a tool definition invokes its delegate
var t = new ToolDefinition("engine_stats", "runtime memory + GC stats",
    new[] { }, (_, _) => Task.FromResult("{\"heapMB\":12}"));
Check("ToolDefinition invokes delegate", (await t.Invoke("{}", default)).Contains("heapMB"));
```
(Change `Program.cs`'s top-level to allow `await`: it already is a top-level program; ensure `<LangVersion>` allows top-level await — it does on net10.)

- [ ] **Step 2: Run — fails** (`ToolDefinition` undefined). `dotnet run --project spike/ForemanHarness -c Release`.

- [ ] **Step 3: Implement `ToolDefinition.cs`**
```csharp
namespace DotnetNativeInterop.Engine.Ai.Agent;

/// <summary>One argument of a tool, used to build the JSON schema + the GBNF grammar.</summary>
public readonly record struct ToolParam(string Name, string JsonType, bool Required);

/// <summary>
/// A tool the agent may call. <see cref="Invoke"/> receives the JSON args object (grammar-guaranteed
/// well-formed) and returns a JSON result string. The delegate is expected to open its own engine span.
/// </summary>
public sealed class ToolDefinition
{
    public string Name { get; }
    public string Description { get; }
    public IReadOnlyList<ToolParam> Params { get; }
    public Func<string, CancellationToken, Task<string>> Invoke { get; }

    public ToolDefinition(string name, string description, IReadOnlyList<ToolParam> @params,
        Func<string, CancellationToken, Task<string>> invoke)
    {
        Name = name; Description = description; Params = @params; Invoke = invoke;
    }
}
```

- [ ] **Step 4: Run — passes.** Expected: `[PASS] ToolDefinition invokes delegate`.

- [ ] **Step 5: Commit**
```
git add core/DotnetNativeInterop.Engine/Ai/Agent/ToolDefinition.cs spike/ForemanHarness/Program.cs
git commit -m "feat(engine): Foreman ToolDefinition + ToolParam"
```

---

## Task 3: GBNF grammar generation

**Files:**
- Create: `core/DotnetNativeInterop.Engine/Ai/Agent/GbnfGrammar.cs`
- Test: `spike/ForemanHarness/Program.cs`

The grammar constrains a turn to EITHER a tool-call object `{"tool":"<one of the names>","args":{…}}` OR a final answer `{"answer":"…"}`. Only structural validity is guaranteed (not tool choice).

- [ ] **Step 1: Add failing test**
```csharp
// Task 3: grammar lists exactly the tool names + an answer alternative
var tools3 = new[] {
    new ToolDefinition("search_manuals", "d", new[]{ new ToolParam("query","string",true) }, (_,_)=>Task.FromResult("")),
    new ToolDefinition("engine_stats", "d", Array.Empty<ToolParam>(), (_,_)=>Task.FromResult("")),
};
var g = GbnfGrammar.Build(tools3);
Check("grammar names each tool", g.Contains("\"search_manuals\"") && g.Contains("\"engine_stats\""));
Check("grammar has an answer alternative", g.Contains("answer"));
Check("grammar has a root rule", g.Contains("root ::="));
```

- [ ] **Step 2: Run — fails** (`GbnfGrammar` undefined).

- [ ] **Step 3: Implement `GbnfGrammar.cs`**
```csharp
using System.Text;

namespace DotnetNativeInterop.Engine.Ai.Agent;

/// <summary>
/// Builds a llama.cpp GBNF grammar restricting a turn to a valid tool call or a final answer.
/// The llama path samples under this grammar so the model cannot emit malformed tool syntax.
/// (Older llama.cpp without grammar sampling: fall back to RouterBrain — see spec.)
/// </summary>
public static class GbnfGrammar
{
    public static string Build(IReadOnlyList<ToolDefinition> tools)
    {
        var sb = new StringBuilder();
        // root is either a tool call or an answer object
        sb.Append("root ::= toolcall | answer\n");
        sb.Append("answer ::= \"{\\\"answer\\\":\" string \"}\"\n");
        // toolcall alternates over one rule per tool so the args shape can differ per tool
        sb.Append("toolcall ::= ");
        for (int i = 0; i < tools.Count; i++)
        {
            if (i > 0) sb.Append(" | ");
            sb.Append("tc_").Append(tools[i].Name);
        }
        sb.Append('\n');
        foreach (var t in tools)
        {
            sb.Append("tc_").Append(t.Name).Append(" ::= \"{\\\"tool\\\":\\\"")
              .Append(t.Name).Append("\\\",\\\"args\\\":\" ").Append(ArgsRule(t)).Append(" \"}\"\n");
        }
        sb.Append("string ::= \"\\\"\" ([^\"\\\\] | \"\\\\\" .)* \"\\\"\"\n");
        sb.Append("ws ::= [ \\t\\n]*\n");
        return sb.ToString();
    }

    private static string ArgsRule(ToolDefinition t)
    {
        if (t.Params.Count == 0) return "\"{}\"";
        // single-object with each param as a string value (all current tools take string args)
        var sb = new StringBuilder("\"{\" ");
        for (int i = 0; i < t.Params.Count; i++)
        {
            if (i > 0) sb.Append(" \",\" ");
            sb.Append("\"\\\"").Append(t.Params[i].Name).Append("\\\":\" string");
        }
        sb.Append(" \"}\"");
        return sb.ToString();
    }
}
```

- [ ] **Step 4: Run — passes** (3 new PASS lines).

- [ ] **Step 5: Commit**
```
git add core/DotnetNativeInterop.Engine/Ai/Agent/GbnfGrammar.cs spike/ForemanHarness/Program.cs
git commit -m "feat(engine): GBNF grammar generation from tool set"
```

---

## Task 4: ToolCallParser

**Files:**
- Create: `core/DotnetNativeInterop.Engine/Ai/Agent/ToolCallParser.cs`
- Test: `spike/ForemanHarness/Program.cs`

- [ ] **Step 1: Add failing test**
```csharp
// Task 4: parse grammar-shaped emissions
var d1 = ToolCallParser.Parse("{\"tool\":\"engine_stats\",\"args\":{}}");
Check("parses a tool call", d1.Call is { } c1 && c1.Tool == "engine_stats" && !d1.IsAnswer);
var d2 = ToolCallParser.Parse("{\"answer\":\"the filter code is E3\"}");
Check("parses a final answer", d2.IsAnswer && d2.Call is null);
var d3 = ToolCallParser.Parse("not json");
Check("malformed -> answer (never throws)", d3.IsAnswer);
```

- [ ] **Step 2: Run — fails.**

- [ ] **Step 3: Implement `ToolCallParser.cs`** (source-gen `JsonDocument` parse; never throws — malformed collapses to an empty answer so the loop ends honestly rather than crashing):
```csharp
using System.Text.Json;

namespace DotnetNativeInterop.Engine.Ai.Agent;

/// <summary>Parses a grammar-constrained turn emission into an <see cref="AgentDecision"/>. Never throws.</summary>
public static class ToolCallParser
{
    public static AgentDecision Parse(string text)
    {
        try
        {
            using var doc = JsonDocument.Parse(text);
            var root = doc.RootElement;
            if (root.TryGetProperty("tool", out var tool) && root.TryGetProperty("args", out var args))
                return AgentDecision.Tool(new ToolCall(tool.GetString() ?? "", args.GetRawText()));
            if (root.TryGetProperty("answer", out _))
                return AgentDecision.Answer;
        }
        catch (JsonException) { /* fall through */ }
        return AgentDecision.Answer;
    }
}
```

- [ ] **Step 4: Run — passes** (3 PASS). **Step 5: Commit**
```
git add core/DotnetNativeInterop.Engine/Ai/Agent/ToolCallParser.cs spike/ForemanHarness/Program.cs
git commit -m "feat(engine): tool-call/answer parser (never throws)"
```

---

## Task 5: IAgentBrain abstraction + AgentContext

**Files:**
- Create: `core/DotnetNativeInterop.Engine/Ai/Agent/IAgentBrain.cs`
- Test: `spike/ForemanHarness/Program.cs`

- [ ] **Step 1: Add failing test** (a scripted brain double exercised directly):
```csharp
// Task 5: a scripted brain returns a tool call, then an answer once a tool result exists
var scripted = new ScriptedBrain(ctx =>
    ctx.Steps.Any(s => s.Kind == "tool_result")
        ? AgentDecision.Answer
        : AgentDecision.Tool(new ToolCall("engine_stats", "{}")));
var ctx0 = new AgentContext("q", new List<AgentStep>());
Check("brain: first decision is a tool call", scripted.DecideAsync(ctx0, default).Result.Call is not null);
ctx0.Steps.Add(new AgentStep("tool_result", "engine_stats", "{}"));
Check("brain: answers after a tool result", scripted.DecideAsync(ctx0, default).Result.IsAnswer);
```
Add the `ScriptedBrain` test double at the bottom of `Program.cs`:
```csharp
sealed class ScriptedBrain(Func<AgentContext, AgentDecision> decide) : IAgentBrain
{
    public Task<AgentDecision> DecideAsync(AgentContext ctx, CancellationToken ct) => Task.FromResult(decide(ctx));
    public Task StreamAnswerAsync(AgentContext ctx, Action<string> sink, CancellationToken ct)
    { sink("scripted answer"); return Task.CompletedTask; }
    public string BackendBadge => "scripted (test)";
}
```

- [ ] **Step 2: Run — fails** (`IAgentBrain`/`AgentContext` undefined).

- [ ] **Step 3: Implement `IAgentBrain.cs`**
```csharp
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
```

- [ ] **Step 4: Run — passes.** **Step 5: Commit**
```
git add core/DotnetNativeInterop.Engine/Ai/Agent/IAgentBrain.cs spike/ForemanHarness/Program.cs
git commit -m "feat(engine): IAgentBrain abstraction + AgentContext"
```

---

## Task 6: ForemanAgent loop + `agent.*` spans

**Files:**
- Create: `core/DotnetNativeInterop.Engine/Ai/Agent/ForemanAgent.cs`
- Test: `spike/ForemanHarness/Program.cs`

The loop: open `agent.turn` span; each step call `DecideAsync`; a tool decision → find the tool, `Invoke`, open `agent.tool.<name>` span, append a `tool_result` step; an answer decision → `StreamAnswerAsync` and return `Answered`. Cap at 5 tool steps.

- [ ] **Step 1: Add failing test** (assert dispatch + spans via a test `ActivityListener`):
```csharp
using System.Diagnostics;
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
```

- [ ] **Step 2: Run — fails** (`ForemanAgent` undefined).

- [ ] **Step 3: Implement `ForemanAgent.cs`**
```csharp
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
```

- [ ] **Step 4: Run — passes** (5 PASS). **Step 5: Commit**
```
git add core/DotnetNativeInterop.Engine/Ai/Agent/ForemanAgent.cs spike/ForemanHarness/Program.cs
git commit -m "feat(engine): ForemanAgent turn loop + agent.* trace spans"
```

---

## Task 7: Max-steps cap is honest

**Files:** Modify: none (behavior already in Task 6). Test: `spike/ForemanHarness/Program.cs`.

- [ ] **Step 1: Add failing test** (a brain that always wants a tool → must stop at the cap):
```csharp
// Task 7: runaway tool-calling stops at the cap with an honest StopReason
int stats7 = 0;
var tools7 = new[] { new ToolDefinition("engine_stats","d",Array.Empty<ToolParam>(),(_,_)=>{stats7++;return Task.FromResult("{}");}) };
var greedy = new ScriptedBrain(_ => AgentDecision.Tool(new ToolCall("engine_stats","{}")));
var res7 = await new ForemanAgent(tools7, greedy).RunTurnAsync("q", _=>{}, default);
Check("stops at MaxToolSteps", stats7 == ForemanAgent.MaxToolSteps);
Check("reports StepCapReached", res7.StopReason == ForemanStopReason.StepCapReached);
```

- [ ] **Step 2: Run.** If Task 6 is correct this PASSES immediately — that is fine (this task is a guard test locking the cap behavior). If it FAILS, fix `ForemanAgent` so the cap check precedes tool dispatch (it does in the Task 6 code).

- [ ] **Step 3: Commit**
```
git add spike/ForemanHarness/Program.cs
git commit -m "test(engine): lock the honest max-steps cap"
```

---

## Task 8: DeterministicRouter (embedding-similarity query→tool)

**Files:**
- Create: `core/DotnetNativeInterop.Engine/Ai/Agent/DeterministicRouter.cs`
- Test: `spike/ForemanHarness/Program.cs`

The router embeds the query and each tool's description, picks the nearest tool if similarity clears a threshold, else returns null (→ answer). Uses an injected embedder `Func<string, float[]>` so the test uses a stub (no ONNX).

- [ ] **Step 1: Add failing test**
```csharp
// Task 8: router picks the nearest tool by cosine; generic query -> none
float[] Emb(string s) => s.Contains("memory") ? new[]{1f,0f,0f}
                        : s.Contains("manual") || s.Contains("fault") ? new[]{0f,1f,0f}
                        : new[]{0.3f,0.3f,0.3f};
var rtools = new[] {
    new ToolDefinition("engine_stats","current memory and gc stats",Array.Empty<ToolParam>(),(_,_)=>Task.FromResult("")),
    new ToolDefinition("search_manuals","search the maintenance manuals for a fault",new[]{new ToolParam("query","string",true)},(_,_)=>Task.FromResult("")),
};
var router = new DeterministicRouter(rtools, Emb, threshold: 0.5f);
Check("routes memory query -> engine_stats", router.Route("how much memory?")?.Tool == "engine_stats");
Check("routes fault query -> search_manuals", router.Route("what is fault code E3?")?.Tool == "search_manuals");
Check("generic query -> no tool", router.Route("hello there") is null);
```

- [ ] **Step 2: Run — fails.**

- [ ] **Step 3: Implement `DeterministicRouter.cs`** (cosine via `TensorPrimitives`):
```csharp
using System.Numerics.Tensors;

namespace DotnetNativeInterop.Engine.Ai.Agent;

/// <summary>
/// The no-LLM fallback selector: embeds the query + each tool description and picks the nearest
/// tool above a threshold, else returns null (the brain then answers). Deterministic + testable.
/// For search-style tools the raw query is passed through as the "query" arg.
/// </summary>
public sealed class DeterministicRouter
{
    private readonly (ToolDefinition Tool, float[] Vec)[] _tools;
    private readonly Func<string, float[]> _embed;
    private readonly float _threshold;

    public DeterministicRouter(IReadOnlyList<ToolDefinition> tools, Func<string, float[]> embed, float threshold)
    {
        _embed = embed; _threshold = threshold;
        _tools = new (ToolDefinition, float[])[tools.Count];
        for (int i = 0; i < tools.Count; i++) _tools[i] = (tools[i], embed(tools[i].Description));
    }

    public ToolCall? Route(string query)
    {
        var q = _embed(query);
        float best = float.NegativeInfinity; ToolDefinition? pick = null;
        foreach (var (tool, vec) in _tools)
        {
            var sim = TensorPrimitives.CosineSimilarity(q, vec);
            if (sim > best) { best = sim; pick = tool; }
        }
        if (pick is null || best < _threshold) return null;
        // string-arg tools get the query; no-arg tools get {}
        var args = pick.Params.Count > 0
            ? $"{{\"{pick.Params[0].Name}\":{System.Text.Json.JsonSerializer.Serialize(query)}}}"
            : "{}";
        return new ToolCall(pick.Name, args);
    }
}
```

- [ ] **Step 4: Run — passes** (3 PASS). **Step 5: Commit**
```
git add core/DotnetNativeInterop.Engine/Ai/Agent/DeterministicRouter.cs spike/ForemanHarness/Program.cs
git commit -m "feat(engine): deterministic embedding router (no-LLM fallback)"
```

---

## Task 9: RouterBrain + GrammarBrain

**Files:**
- Create: `core/DotnetNativeInterop.Engine/Ai/Agent/RouterBrain.cs`, `GrammarBrain.cs`
- Test: `spike/ForemanHarness/Program.cs`

`RouterBrain`: first `DecideAsync` returns the router's tool (if any), thereafter (a `tool_result` present) answers; `StreamAnswerAsync` delegates to an `ILanguageModel` (the extractive model) seeded with query + tool results. `GrammarBrain`: `DecideAsync` calls a completion `Func` (wrapping `DniChatClient` under grammar) and parses via `ToolCallParser`; `StreamAnswerAsync` streams a completion. Both testable with lambdas — no llama.

- [ ] **Step 1: Add failing test**
```csharp
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
```

- [ ] **Step 2: Run — fails.**

- [ ] **Step 3: Implement both.**

`RouterBrain.cs`:
```csharp
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
```

`GrammarBrain.cs`:
```csharp
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
```

- [ ] **Step 4: Run — passes** (4 PASS). **Step 5: Commit**
```
git add core/DotnetNativeInterop.Engine/Ai/Agent/RouterBrain.cs core/DotnetNativeInterop.Engine/Ai/Agent/GrammarBrain.cs spike/ForemanHarness/Program.cs
git commit -m "feat(engine): RouterBrain + GrammarBrain"
```

---

## Task 10: ForemanTools bound to real engine ops

**Files:**
- Create: `core/DotnetNativeInterop.Engine/Ai/Agent/ForemanTools.cs`
- Test: `spike/ForemanHarness/Program.cs`

Wire the three tools to existing engine capabilities. **First read** `Ai/SemanticSearch.cs`, `Ai/RagPrompt.cs`/`RagLanguageModel.cs`, the feature-run entry used by `Ffi/Exports.Features.cs` → `LanguageFeatureCatalog`/`ShowcaseCommand`, and `EngineTelemetry.cs` to get exact method signatures. Bind:
- `search_manuals(query)` → `SemanticSearch.Search(query, "manuals", topK)` + `RagPrompt`-style snippet join → JSON.
- `run_feature(id)` → the same path `dni_feature_run` uses (`LanguageFeatureCatalog.Run(id)` / `ShowcaseCommand.Run`) → its `{result,elapsedMs,ok}` JSON.
- `engine_stats()` → `EngineTelemetry.Snapshot()` → its JSON.

Each delegate opens no new span itself **unless** the underlying op doesn't already (the `agent.tool.*` span in Task 6 wraps the call; if the op has its own span it nests — desired).

- [ ] **Step 1: Add failing test** (uses the real telemetry op; search/feature validated by shape):
```csharp
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
```

- [ ] **Step 2: Run — fails.**

- [ ] **Step 3: Implement `ForemanTools.cs`** (factory takes op delegates so the engine wires real ones and tests wire doubles; the factory parses the `query`/`id` arg out of the JSON args):
```csharp
using System.Text.Json;

namespace DotnetNativeInterop.Engine.Ai.Agent;

/// <summary>Builds the three Foreman tools from injected engine-op delegates.</summary>
public static class ForemanTools
{
    public static IReadOnlyList<ToolDefinition> Build(
        Func<string, Task<string>> searchManuals,
        Func<string, Task<string>> runFeature,
        Func<string> engineStats)
    {
        return new[]
        {
            new ToolDefinition("search_manuals", "search the maintenance manuals for a fault or procedure",
                new[] { new ToolParam("query", "string", true) },
                async (args, _) => await searchManuals(GetString(args, "query"))),
            new ToolDefinition("run_feature", "run a named C#/.NET feature demo and return its result",
                new[] { new ToolParam("id", "string", true) },
                async (args, _) => await runFeature(GetString(args, "id"))),
            new ToolDefinition("engine_stats", "current runtime memory, GC, and thread stats",
                Array.Empty<ToolParam>(),
                (_, _) => Task.FromResult(engineStats())),
        };
    }

    private static string GetString(string argsJson, string name)
    {
        try { using var d = JsonDocument.Parse(argsJson); return d.RootElement.TryGetProperty(name, out var v) ? v.GetString() ?? "" : ""; }
        catch (JsonException) { return ""; }
    }
}
```

- [ ] **Step 4: Run — passes** (3 PASS). **Step 5: Commit**
```
git add core/DotnetNativeInterop.Engine/Ai/Agent/ForemanTools.cs spike/ForemanHarness/Program.cs
git commit -m "feat(engine): ForemanTools factory bound to engine ops"
```

---

## Task 11: End-to-end harness + AOT-clean check

**Files:** Modify: `spike/ForemanHarness/Program.cs`; verify AOT.

- [ ] **Step 1: Add end-to-end test** (router brain + real tool factory + real `EngineTelemetry` + `ExtractiveLanguageModel` prose, driven through `ForemanAgent`; assert a grounded answer + the tool span):
```csharp
// Task 11: full turn through ForemanAgent with the router brain and real telemetry
var e2eTools = ForemanTools.Build(
    q => Task.FromResult("{\"snippets\":[\"clear code E3 by resetting the panel\"]}"),
    id => Task.FromResult("{\"ok\":true}"),
    () => DotnetNativeInterop.Engine.EngineTelemetry.Snapshot());   // confirm exact type/method when implementing
float[] Emb2(string s) => s.Contains("memory") ? new[]{1f,0f} : new[]{0f,1f};
var e2eRouter = new DeterministicRouter(e2eTools, Emb2, 0.5f);
var e2eBrain = new RouterBrain(e2eRouter, prompt => $"Based on the manual: {prompt}");
var e2eAgent = new ForemanAgent(e2eTools, e2eBrain);
var e2eOut = new System.Text.StringBuilder();
var e2eRes = await e2eAgent.RunTurnAsync("how much memory?", s => e2eOut.Append(s), default);
Check("e2e turn answered", e2eRes.StopReason == ForemanStopReason.Answered);
Check("e2e produced an answer", e2eOut.Length > 0);
Check("e2e badge discloses no LLM", e2eAgent.BackendBadge.Contains("no on-device LLM"));
```
(When implementing, replace `EngineTelemetry.Snapshot()` with the real telemetry accessor confirmed by reading `EngineTelemetry.cs`; if it returns a struct, serialize via the existing telemetry source-gen context.)

- [ ] **Step 2: Run — passes.** `dotnet run --project spike/ForemanHarness -c Release` → all checks PASS, `== N/N checks passed ==`.

- [ ] **Step 3: AOT-clean verification** (the whole agent core must stay reflection-free):

Run: `dotnet publish spike/ForemanHarness -r win-x64 -c Release /p:PublishAot=true /p:IlcUseEnvironmentalTools=true`
Expected: 0 IL2xxx/IL3xxx warnings; run the produced exe → same all-pass. (Toolchain: if native link fails on the VS v18 issue, use the hand-built MSVC 14.42 env per `docs/nativeaot-mobile-caveats.md` / the reference-windows-nativeaot-toolchain note.)

- [ ] **Step 4: Commit**
```
git add spike/ForemanHarness/Program.cs
git commit -m "test(engine): end-to-end Foreman turn + AOT-clean check"
```

---

## Self-Review (completed by plan author)

**Spec coverage:** hand-rolled loop ✓ (T6), GBNF grammar ✓ (T3), no `FunctionInvokingChatClient` ✓ (uses `DniChatClient` completion func in `GrammarBrain`), three tools ✓ (T10), two brains ✓ (T9), honest badges ✓ (T9/T11), max-steps cap disclosed ✓ (T7), `agent.*` spans in the `Dni.Engine` source ✓ (T6), Windows-verifiable against Mock/extractive ✓ (T11), source-gen JSON ✓ (T1). **Out of scope by design (later plans):** the GBNF llama-shim gate (Plan B, device), the ABI exposure decision (`dni_agent_session_start` vs reuse the RAG stream — deferred to the exposure task in Plan B/C), the UI (Plans C/D). **Gap noted for the exposure plan:** wiring `GrammarBrain`'s `complete` func to a real `DniChatClient` under the grammar requires the shim's grammar support — hence Plan B precedes any llama-path claim.

**Placeholder scan:** the two spots that say "confirm exact signature when implementing" (feature-run entry in T10, `EngineTelemetry.Snapshot()` in T10/T11) are deliberate reads-before-binding against existing code, not unimplemented steps — the delegate seams are fully specified; only the one-line call into existing ops is to be confirmed. No TBD/TODO in delivered code.

**Type consistency:** `ToolCall(Tool, ArgsJson)`, `AgentDecision.{Call,IsAnswer,Tool(),Answer}`, `AgentStep(Kind, Detail, Result)`, `IAgentBrain.{DecideAsync, StreamAnswerAsync, BackendBadge}`, `AgentContext.{Query, Steps}`, `ForemanAgent.{RunTurnAsync, MaxToolSteps, BackendBadge}`, `ForemanStopReason.{Answered, StepCapReached, Error}` used consistently across T1–T11. `Kind` values `"tool_call"`/`"tool_result"` consistent (T6 writes, T5/T9 read).
