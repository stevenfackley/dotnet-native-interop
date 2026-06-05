import Foundation

/// Swift port of the engine's BERT WordPiece tokenizer (uncased). MUST produce ids identical to the C#
/// `WordPieceTokenizer` so the Swift query vector is comparable to the publisher's corpus vectors.
/// Verified on device by EdgeSearchAgreementTests.
struct WordPieceTokenizer {
    private let vocab: [String: Int]
    let clsId: Int
    let sepId: Int
    private let unkId: Int
    private let padId: Int

    init(vocabLines: [String]) {
        var v = [String: Int](minimumCapacity: vocabLines.count)
        for (i, line) in vocabLines.enumerated() where !line.isEmpty { v[line] = i }
        vocab = v
        clsId = v["[CLS]"] ?? 101
        sepId = v["[SEP]"] ?? 102
        unkId = v["[UNK]"] ?? 100
        padId = v["[PAD]"] ?? 0
    }

    /// Encodes into padded ids + mask of length `maxLen` (default 64), matching the engine.
    func encode(_ text: String, maxLen: Int = 64) -> (ids: [Int64], mask: [Int64]) {
        var pieces: [Int] = [clsId]
        for word in basicTokenize(text) {
            for piece in wordPiece(word) {
                if pieces.count >= maxLen - 1 { break }
                pieces.append(piece)
            }
        }
        pieces.append(sepId)

        var ids = [Int64](repeating: Int64(padId), count: maxLen)
        var mask = [Int64](repeating: 0, count: maxLen)
        for i in 0..<maxLen where i < pieces.count {
            ids[i] = Int64(pieces[i])
            mask[i] = 1
        }
        return (ids, mask)
    }

    // Lower-case, NFD, drop combining marks, split on whitespace, peel punctuation/symbols into own tokens.
    private func basicTokenize(_ text: String) -> [String] {
        let normalized = text.lowercased().decomposedStringWithCanonicalMapping
        var words: [String] = []
        var sb = String.UnicodeScalarView()
        func flush() { if !sb.isEmpty { words.append(String(sb)); sb = String.UnicodeScalarView() } }

        for scalar in normalized.unicodeScalars {
            let cat = scalar.properties.generalCategory
            if cat == .nonspacingMark { continue }
            if scalar.properties.isWhitespace {
                flush()
            } else if Self.isPunctuationOrSymbol(cat) {
                flush()
                words.append(String(scalar))
            } else {
                sb.append(scalar)
            }
        }
        flush()
        return words
    }

    // Mirrors C# char.IsPunctuation || char.IsSymbol.
    private static func isPunctuationOrSymbol(_ c: Unicode.GeneralCategory) -> Bool {
        switch c {
        case .connectorPunctuation, .dashPunctuation, .openPunctuation, .closePunctuation,
             .initialPunctuation, .finalPunctuation, .otherPunctuation,
             .mathSymbol, .currencySymbol, .modifierSymbol, .otherSymbol:
            return true
        default:
            return false
        }
    }

    // Greedy longest-match WordPiece; unknown words -> [UNK].
    private func wordPiece(_ word: String) -> [Int] {
        let chars = Array(word.unicodeScalars)
        var start = 0
        var output: [Int] = []
        while start < chars.count {
            var end = chars.count
            var matched = -1
            while start < end {
                let sub = (start > 0 ? "##" : "") + String(String.UnicodeScalarView(chars[start..<end]))
                if let id = vocab[sub] { matched = id; break }
                end -= 1
            }
            if matched < 0 { return [unkId] }
            output.append(matched)
            start = end
        }
        return output
    }
}
