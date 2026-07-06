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
