using System.Globalization;
using System.Text;

namespace DotnetNativeInterop.Engine;

/// <summary>
/// A minimal BERT WordPiece tokenizer (uncased) — AOT-safe, no external dependency. Lower-cases, strips
/// accents, splits on whitespace/punctuation, then greedy longest-match WordPiece against the vocab.
/// Produces padded <c>input_ids</c> + <c>attention_mask</c> wrapped with [CLS]/[SEP].
/// </summary>
public sealed class WordPieceTokenizer
{
    private readonly Dictionary<string, long> _vocab;
    public long ClsId { get; }
    public long SepId { get; }
    private readonly long _unkId;
    private readonly long _padId;

    public WordPieceTokenizer(IReadOnlyList<string> vocabLines)
    {
        _vocab = new Dictionary<string, long>(vocabLines.Count, StringComparer.Ordinal);
        for (var i = 0; i < vocabLines.Count; i++)
        {
            _vocab[vocabLines[i]] = i;
        }

        ClsId = _vocab["[CLS]"];
        SepId = _vocab["[SEP]"];
        _unkId = _vocab["[UNK]"];
        _padId = _vocab["[PAD]"];
    }

    /// <summary>Encodes one string into padded ids + mask of length <paramref name="maxLen"/>.</summary>
    public (long[] Ids, long[] Mask) Encode(string text, int maxLen = 64)
    {
        var pieces = new List<long> { ClsId };
        foreach (var word in BasicTokenize(text))
        {
            foreach (var piece in WordPiece(word))
            {
                if (pieces.Count >= maxLen - 1)
                {
                    break;
                }

                pieces.Add(piece);
            }
        }

        pieces.Add(SepId);

        var ids = new long[maxLen];
        var mask = new long[maxLen];
        for (var i = 0; i < maxLen; i++)
        {
            if (i < pieces.Count)
            {
                ids[i] = pieces[i];
                mask[i] = 1;
            }
            else
            {
                ids[i] = _padId;
                mask[i] = 0;
            }
        }

        return (ids, mask);
    }

    // Lower-case, strip accents, split on whitespace, peel punctuation into its own tokens.
    private static List<string> BasicTokenize(string text)
    {
        var normalized = text.ToLowerInvariant().Normalize(NormalizationForm.FormD);
        var sb = new StringBuilder();
        var words = new List<string>();
        void Flush()
        {
            if (sb.Length > 0) { words.Add(sb.ToString()); sb.Clear(); }
        }

        foreach (var ch in normalized)
        {
            if (CharUnicodeInfo.GetUnicodeCategory(ch) == UnicodeCategory.NonSpacingMark)
            {
                continue; // drop accents
            }

            if (char.IsWhiteSpace(ch))
            {
                Flush();
            }
            else if (char.IsPunctuation(ch) || char.IsSymbol(ch))
            {
                Flush();
                words.Add(ch.ToString());
            }
            else
            {
                sb.Append(ch);
            }
        }

        Flush();
        return words;
    }

    // Greedy longest-match WordPiece; unknown words → [UNK].
    private List<long> WordPiece(string word)
    {
        var start = 0;
        var output = new List<long>();
        while (start < word.Length)
        {
            var end = word.Length;
            long matched = -1;
            while (start < end)
            {
                var sub = (start > 0 ? "##" : string.Empty) + word[start..end];
                if (_vocab.TryGetValue(sub, out var id))
                {
                    matched = id;
                    break;
                }

                end--;
            }

            if (matched < 0)
            {
                return [_unkId];
            }

            output.Add(matched);
            start = end;
        }

        return output;
    }
}
