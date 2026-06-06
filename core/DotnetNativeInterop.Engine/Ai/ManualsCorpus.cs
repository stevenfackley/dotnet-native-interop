namespace DotnetNativeInterop.Engine;

/// <summary>
/// Loads the bundled maintenance manuals (markdown) and splits each into retrievable passage chunks
/// for <see cref="SemanticSearch"/>. These same files are the grounding corpus for the "Ask the
/// Manuals" RAG feature; Phase 4's EVS module consumes them as a prebuilt Swift-side index, while the
/// engine re-embeds them here at launch with its own encoder.
/// </summary>
public static class ManualsCorpus
{
    /// <summary>
    /// Reads every <c>*.md</c> under <paramref name="assetsDir"/>/manuals and returns one chunk per
    /// "## section", each prefixed with the document title so a retrieved chunk is self-describing.
    /// Returns an empty list when the folder is absent (graceful — search just has no manuals).
    /// </summary>
    public static IReadOnlyList<string> Load(string assetsDir)
    {
        var dir = Path.Combine(assetsDir, "manuals");
        if (!Directory.Exists(dir))
        {
            return [];
        }

        var chunks = new List<string>();
        foreach (var file in Directory.EnumerateFiles(dir, "*.md")
                     .OrderBy(p => p, StringComparer.Ordinal))
        {
            var text = File.ReadAllText(file);
            var title = ExtractTitle(text) ?? Path.GetFileNameWithoutExtension(file);
            foreach (var section in SplitSections(text))
            {
                chunks.Add($"{title} — {section}");
            }
        }

        return chunks;
    }

    // The "title:" line inside the leading YAML frontmatter (--- … ---).
    private static string? ExtractTitle(string markdown)
    {
        var t = markdown.TrimStart();
        if (!t.StartsWith("---", StringComparison.Ordinal))
        {
            return null;
        }

        var end = t.IndexOf("\n---", 3, StringComparison.Ordinal);
        if (end < 0)
        {
            return null;
        }

        foreach (var raw in t[3..end].Split('\n'))
        {
            var line = raw.Trim();
            if (line.StartsWith("title:", StringComparison.OrdinalIgnoreCase))
            {
                return line["title:".Length..].Trim();
            }
        }

        return null;
    }

    // One chunk per "## " heading; frontmatter dropped; each section flattened to a single line.
    private static IEnumerable<string> SplitSections(string markdown)
    {
        foreach (var part in StripFrontmatter(markdown).Split("\n## "))
        {
            var section = part.Trim().TrimStart('#', ' ').Trim();
            if (section.Length == 0)
            {
                continue;
            }

            yield return string.Join(' ', section.Split(
                '\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries));
        }
    }

    private static string StripFrontmatter(string markdown)
    {
        var t = markdown.TrimStart();
        if (!t.StartsWith("---", StringComparison.Ordinal))
        {
            return markdown;
        }

        var end = t.IndexOf("\n---", 3, StringComparison.Ordinal);
        if (end < 0)
        {
            return markdown;
        }

        var afterFence = t.IndexOf('\n', end + 1);
        return afterFence >= 0 ? t[(afterFence + 1)..] : string.Empty;
    }
}
