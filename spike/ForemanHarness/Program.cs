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

Console.WriteLine($"== {passed}/{passed + failed} checks passed ==");
return failed == 0 ? 0 : 1;
