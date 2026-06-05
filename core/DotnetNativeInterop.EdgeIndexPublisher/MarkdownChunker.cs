namespace DotnetNativeInterop.EdgeIndexPublisher;

/// One indexed section: the `##` heading text and the body beneath it (including deeper `###` content).
public sealed record DocChunk(string SectionTitle, string Body);

/// Splits a Markdown body into one chunk per `##` section. Text before the first `##` is dropped; `###`
/// and deeper headings stay within their enclosing `##` chunk.
public static class MarkdownChunker
{
    public static IReadOnlyList<DocChunk> Chunk(string body)
    {
        var chunks = new List<DocChunk>();
        string? title = null;
        var sb = new System.Text.StringBuilder();

        void Flush()
        {
            if (title is not null)
            {
                chunks.Add(new DocChunk(title, sb.ToString().Trim()));
            }

            sb.Clear();
        }

        foreach (var raw in body.Replace("\r\n", "\n").Split('\n'))
        {
            var line = raw;
            if (line.StartsWith("## ") && !line.StartsWith("### "))
            {
                Flush();
                title = line[3..].Trim();
            }
            else if (title is not null)
            {
                sb.Append(line).Append('\n');
            }
        }

        Flush();
        return chunks;
    }
}
