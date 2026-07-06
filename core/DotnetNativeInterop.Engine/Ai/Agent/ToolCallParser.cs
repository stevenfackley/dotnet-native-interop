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
