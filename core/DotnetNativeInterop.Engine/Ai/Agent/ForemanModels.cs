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

/// <summary>
/// The status <see cref="ForemanLanguageModel"/> serializes into the final fragment of an agent
/// session's stream (see <see cref="ForemanLanguageModel.StatusMarker"/>) — the FFI-facing surface a
/// client reads to tell an <see cref="ForemanStopReason.Answered"/> turn apart from a
/// <see cref="ForemanStopReason.StepCapReached"/> or <see cref="ForemanStopReason.Error"/> one.
/// </summary>
/// <param name="StopReason">Why the turn ended (Answered / StepCapReached / Error).</param>
/// <param name="ToolSteps">Number of tool calls the turn made before ending.</param>
/// <param name="Backend">
/// The honest UI badge for the brain that actually ran the turn (<see cref="IAgentBrain.BackendBadge"/>,
/// e.g. <c>"scripted routing — no on-device LLM present"</c> or an on-device-LLM badge) — additive: a
/// new JSON property on an already-parsed payload, not an ABI export signature change.
/// </param>
public readonly record struct AgentSessionStatus(ForemanStopReason StopReason, int ToolSteps, string Backend);

/// <summary>A decision produced by an <c>IAgentBrain</c>: either a tool call or a final answer.</summary>
public readonly record struct AgentDecision(ToolCall? Call, bool IsAnswer)
{
    public static AgentDecision Tool(ToolCall c) => new(c, false);
    public static readonly AgentDecision Answer = new(null, true);
}

// UseStringEnumConverter: ForemanStopReason must serialize as its name (e.g. "Answered"), not an int,
// so the native UI / logs can read it directly. Source-gen-safe (no reflection).
//
// FeatureRun (DotnetNativeInterop.Engine.LanguageFeatures.cs) is included here so the real run_feature
// tool binding (ForemanHost) can serialize LanguageFeatureCatalog.Run's result without reflection.
// FeaturesJsonContext already source-gens FeatureRun, but it is `internal sealed partial class
// FeaturesJsonContext` in the NativeBridge assembly (core/DotnetNativeInterop.NativeBridge/
// FeaturesJsonContext.cs) — unreachable from Engine, which NativeBridge references, not the reverse.
// FeatureRun the type IS already Engine-visible (declared in the Engine assembly), so no type move is
// needed; only a second, Engine-side source-gen registration for it.
[JsonSourceGenerationOptions(PropertyNamingPolicy = JsonKnownNamingPolicy.CamelCase, UseStringEnumConverter = true)]
[JsonSerializable(typeof(ToolCall))]
[JsonSerializable(typeof(AgentStep))]
[JsonSerializable(typeof(ForemanTurnResult))]
[JsonSerializable(typeof(ForemanStopReason))]
[JsonSerializable(typeof(FeatureRun))]
[JsonSerializable(typeof(AgentSessionStatus))]
public partial class ForemanJsonContext : System.Text.Json.Serialization.JsonSerializerContext { }
