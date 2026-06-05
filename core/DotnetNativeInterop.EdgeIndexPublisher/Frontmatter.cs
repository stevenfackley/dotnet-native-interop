namespace DotnetNativeInterop.EdgeIndexPublisher;

/// Promoted frontmatter fields for one maintenance doc.
public sealed record DocFrontmatter(string DocumentId, string Title, string[] ErrorCodes, string[] ToolsRequired);

/// Minimal `---`-fenced frontmatter reader. Supports `key: value`, inline lists `key: [a, b]`, and
/// block lists (`key:` then `  - item` lines). No external YAML dependency. Unknown keys are ignored.
public static class Frontmatter
{
    public static (DocFrontmatter Front, string Body) Parse(string markdown)
    {
        var text = markdown.Replace("\r\n", "\n").TrimStart('\n');
        if (!text.StartsWith("---\n"))
        {
            return (new DocFrontmatter("", "", [], []), markdown);
        }

        var end = text.IndexOf("\n---", 4, StringComparison.Ordinal);
        if (end < 0)
        {
            return (new DocFrontmatter("", "", [], []), markdown);
        }

        var header = text[4..end];
        var body = text[(end + 4)..].TrimStart('\n');

        string id = "", title = "";
        string[] codes = [], tools = [];
        var lines = header.Split('\n');
        for (var i = 0; i < lines.Length; i++)
        {
            var line = lines[i];
            if (line.Length == 0 || line[0] is ' ' or '-')
            {
                continue; // block-list continuation handled when its key is seen
            }

            var colon = line.IndexOf(':');
            if (colon < 0)
            {
                continue;
            }

            var key = line[..colon].Trim();
            var value = line[(colon + 1)..].Trim();
            switch (key)
            {
                case "document_id": id = Unquote(value); break;
                case "title": title = Unquote(value); break;
                case "error_codes": codes = ReadList(value, lines, i); break;
                case "tools_required": tools = ReadList(value, lines, i); break;
            }
        }

        return (new DocFrontmatter(id, title, codes, tools), body);
    }

    // Inline (`[a, b]`) or block list (`  - item` on the following lines).
    private static string[] ReadList(string inlineValue, string[] lines, int keyIndex)
    {
        if (inlineValue.StartsWith('[') && inlineValue.EndsWith(']'))
        {
            return inlineValue[1..^1].Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
                .Select(Unquote).ToArray();
        }

        var items = new List<string>();
        for (var j = keyIndex + 1; j < lines.Length; j++)
        {
            var t = lines[j].TrimStart();
            if (!t.StartsWith("- "))
            {
                break;
            }

            items.Add(Unquote(t[2..].Trim()));
        }

        return items.ToArray();
    }

    private static string Unquote(string s) =>
        s.Length >= 2 && (s[0] == '"' && s[^1] == '"' || s[0] == '\'' && s[^1] == '\'') ? s[1..^1] : s;
}
