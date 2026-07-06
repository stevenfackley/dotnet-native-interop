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
            // TryGetProperty throws InvalidOperationException (NOT a JsonException) on any non-object
            // JSON — a scalar ("hello there", 42) or an array ([1,2,3]) — so guard the kind first. This
            // is the safety net for untrusted model output: any valid-but-wrong-shape emission must
            // collapse to a final answer, never surface as an error or throw.
            if (root.ValueKind != JsonValueKind.Object)
                return AgentDecision.Answer;
            // Require a string "tool" (GetString throws on a non-string element) plus an "args" object.
            if (root.TryGetProperty("tool", out var tool) && tool.ValueKind == JsonValueKind.String
                && root.TryGetProperty("args", out var args))
                return AgentDecision.Tool(new ToolCall(tool.GetString() ?? "", args.GetRawText()));
            if (root.TryGetProperty("answer", out _))
                return AgentDecision.Answer;
        }
        catch (JsonException) { /* fall through */ }
        return AgentDecision.Answer;
    }
}
