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
