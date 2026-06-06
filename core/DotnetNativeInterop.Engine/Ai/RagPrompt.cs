using System.Text;

namespace DotnetNativeInterop.Engine;

/// <summary>
/// Assembles the grounded RAG prompt fed to the on-device LLM: a system instruction, the retrieved
/// manual excerpts as context, then the user's question. The wording is a product/quality decision —
/// it controls how strongly the model is held to the sources — so it is intentionally small and
/// human-owned. (Used only by the neural <c>RagLanguageModel</c>; the extractive generator
/// composes its answer differently.)
/// </summary>
public static class RagPrompt
{
    /// <summary>Builds a Llama-3-style instruction prompt grounding <paramref name="question"/> in the
    /// retrieved <paramref name="context"/> passages.</summary>
    public static string Build(string question, SearchResult[] context)
    {
        var sb = new StringBuilder();
        sb.AppendLine("You are a maintenance assistant. Answer the question using ONLY the manual");
        sb.AppendLine("excerpts below. If they do not contain the answer, say you couldn't find it in");
        sb.AppendLine("the manuals. Be concise.");
        sb.AppendLine();
        sb.AppendLine("Manual excerpts:");
        if (context.Length == 0)
        {
            sb.AppendLine("(none found)");
        }
        else
        {
            foreach (var c in context)
            {
                sb.Append("- ").AppendLine(c.Text);
            }
        }

        sb.AppendLine();
        sb.Append("Question: ").AppendLine(question);
        sb.Append("Answer:");
        return sb.ToString();
    }
}
