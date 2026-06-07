package io.dotnetnativeinterop.evs

import java.text.Normalizer

/**
 * Minimal BERT WordPiece tokenizer (uncased) — a faithful Kotlin port of the engine's C#
 * WordPieceTokenizer so the embedding ids match the .NET-published index exactly.
 */
public class WordPieceTokenizer(vocabLines: List<String>) {

    private val vocab: Map<String, Long> = HashMap<String, Long>(vocabLines.size).apply {
        vocabLines.forEachIndexed { i, line -> put(line, i.toLong()) }
    }
    private val clsId = vocab.getValue("[CLS]")
    private val sepId = vocab.getValue("[SEP]")
    private val unkId = vocab.getValue("[UNK]")
    private val padId = vocab.getValue("[PAD]")

    /** Encodes text into padded ids + attention mask of length [maxLen]. */
    public fun encode(text: String, maxLen: Int = 64): Pair<LongArray, LongArray> {
        val pieces = ArrayList<Long>()
        pieces.add(clsId)
        outer@ for (word in basicTokenize(text)) {
            for (piece in wordPiece(word)) {
                if (pieces.size >= maxLen - 1) break@outer
                pieces.add(piece)
            }
        }
        pieces.add(sepId)

        val ids = LongArray(maxLen)
        val mask = LongArray(maxLen)
        for (i in 0 until maxLen) {
            if (i < pieces.size) { ids[i] = pieces[i]; mask[i] = 1 } else { ids[i] = padId; mask[i] = 0 }
        }
        return ids to mask
    }

    private fun basicTokenize(text: String): List<String> {
        val normalized = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        val words = ArrayList<String>()
        val sb = StringBuilder()
        fun flush() { if (sb.isNotEmpty()) { words.add(sb.toString()); sb.setLength(0) } }
        for (ch in normalized) {
            when {
                Character.getType(ch) == Character.NON_SPACING_MARK.toInt() -> {}
                ch.isWhitespace() -> flush()
                isPunctuationOrSymbol(ch) -> { flush(); words.add(ch.toString()) }
                else -> sb.append(ch)
            }
        }
        flush()
        return words
    }

    private fun wordPiece(word: String): List<Long> {
        var start = 0
        val out = ArrayList<Long>()
        while (start < word.length) {
            var end = word.length
            var matched = -1L
            while (start < end) {
                val sub = (if (start > 0) "##" else "") + word.substring(start, end)
                val id = vocab[sub]
                if (id != null) { matched = id; break }
                end--
            }
            if (matched < 0) return listOf(unkId)
            out.add(matched)
            start = end
        }
        return out
    }

    private fun isPunctuationOrSymbol(ch: Char): Boolean = when (Character.getType(ch).toByte()) {
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
        Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION,
        Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL -> true
        else -> false
    }
}
