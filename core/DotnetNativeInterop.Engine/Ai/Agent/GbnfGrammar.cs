using System.Text;

namespace DotnetNativeInterop.Engine.Ai.Agent;

/// <summary>
/// Builds a llama.cpp GBNF grammar restricting a turn to a valid tool call or a final answer.
/// The llama path samples UNDER this grammar (hard sampler constraint, not a prompt) so the model
/// cannot emit malformed tool syntax — <c>ForemanHost.BuildGrammarBrain</c> passes this string through
/// <c>DniChatClient.GrammarPropertyKey</c> -> <c>InferenceRequest.Grammar</c> -> the dni_llama shim's
/// <c>llama_sampler_init_grammar</c>. (No on-device model / unloadable GGUF: fall back to RouterBrain.)
/// </summary>
public static class GbnfGrammar
{
    public static string Build(IReadOnlyList<ToolDefinition> tools)
    {
        // Tool + param names are emitted verbatim into GBNF rule names (tc_<name>) and JSON string
        // terminals, so they must be GBNF-identifier-safe [A-Za-z0-9_]. Every tool this engine builds is a
        // compile-time constant that satisfies that (search_manuals / run_feature / engine_stats, args
        // query / id) — there are no runtime-named tools, so no escaping/validation is needed here.
        var sb = new StringBuilder();
        // No tools: the only valid turn is a final answer. Emitting "root ::= toolcall | answer" with an
        // empty "toolcall ::=" alternative would be invalid GBNF, so short-circuit to an answer-only grammar.
        if (tools.Count == 0)
        {
            sb.Append("root ::= answer\n");
            sb.Append("answer ::= \"{\\\"answer\\\":\" string \"}\"\n");
            sb.Append("string ::= \"\\\"\" ([^\"\\\\] | \"\\\\\" .)* \"\\\"\"\n");
            return sb.ToString();
        }
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
        // Compact JSON only for the constrained turn — no whitespace rule (a "ws" rule was previously
        // emitted but never referenced by any other rule, i.e. dead grammar).
        sb.Append("string ::= \"\\\"\" ([^\"\\\\] | \"\\\\\" .)* \"\\\"\"\n");
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
