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

Console.WriteLine($"== {passed}/{passed + failed} checks passed ==");
return failed == 0 ? 0 : 1;
