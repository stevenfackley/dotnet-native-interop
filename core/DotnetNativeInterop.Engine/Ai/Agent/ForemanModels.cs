using System.Text.Json.Serialization;

namespace DotnetNativeInterop.Engine.Ai.Agent;

/// <summary>A model-selected tool invocation. <paramref name="ArgsJson"/> is a JSON object string.</summary>
public readonly record struct ToolCall(string Tool, string ArgsJson);

/// <summary>Why a turn ended.</summary>
public enum ForemanStopReason { Answered, StepCapReached, Error }

/// <summary>One recorded step in a turn (a tool call + its result, or the final answer).</summary>
public readonly record struct AgentStep(string Kind, string Detail, string? Result)
{
    // Shared <see cref="Kind"/> values: written by ForemanAgent, read by the brains for routing —
    // constants so a typo is a compile error, not a silently-broken "did a tool run yet?" check.
    /// <summary><see cref="Kind"/> value for a recorded tool invocation.</summary>
    public const string KindToolCall = "tool_call";
    /// <summary><see cref="Kind"/> value for a recorded tool result.</summary>
    public const string KindToolResult = "tool_result";
}

/// <summary>The outcome of a single Foreman turn.</summary>
public readonly record struct ForemanTurnResult(string Answer, ForemanStopReason StopReason, int ToolSteps);

/// <summary>A decision produced by an <c>IAgentBrain</c>: either a tool call or a final answer.</summary>
public readonly record struct AgentDecision(ToolCall? Call, bool IsAnswer)
{
    public static AgentDecision Tool(ToolCall c) => new(c, false);
    public static readonly AgentDecision Answer = new(null, true);
}

// UseStringEnumConverter: ForemanStopReason must serialize as its name (e.g. "Answered"), not an int,
// so the native UI / logs can read it directly. Source-gen-safe (no reflection).
[JsonSourceGenerationOptions(PropertyNamingPolicy = JsonKnownNamingPolicy.CamelCase, UseStringEnumConverter = true)]
[JsonSerializable(typeof(ToolCall))]
[JsonSerializable(typeof(AgentStep))]
[JsonSerializable(typeof(ForemanTurnResult))]
[JsonSerializable(typeof(ForemanStopReason))]
public partial class ForemanJsonContext : System.Text.Json.Serialization.JsonSerializerContext { }
