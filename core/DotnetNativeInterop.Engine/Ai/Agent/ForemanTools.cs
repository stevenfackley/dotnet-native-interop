using System.Text.Json;

namespace DotnetNativeInterop.Engine.Ai.Agent;

/// <summary>
/// Builds the three Foreman tools from injected engine-op delegates. Kept generic (delegates in,
/// <see cref="ToolDefinition"/>s out) so this factory stays decoupled from any one op implementation:
/// a caller wires real engine ops (<c>SemanticSearch.Default.Search</c>, <c>LanguageFeatureCatalog.Run</c>,
/// <c>EngineTelemetry.SnapshotJson</c>) for production use, or light doubles for tests — see the harness.
/// </summary>
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
        try
        {
            using var d = JsonDocument.Parse(argsJson);
            var root = d.RootElement;
            // Same non-object hole ToolCallParser guards against: TryGetProperty throws on a scalar/array
            // and GetString throws on a non-string value. Contained by the agent's tool try/catch, but
            // hardened here so the arg extractor is honestly total.
            return root.ValueKind == JsonValueKind.Object && root.TryGetProperty(name, out var v)
                && v.ValueKind == JsonValueKind.String
                ? v.GetString() ?? ""
                : "";
        }
        catch (JsonException) { return ""; }
    }
}
