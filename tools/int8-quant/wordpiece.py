"""Python port of core/DotnetNativeInterop.Engine/Ai/WordPieceTokenizer.cs (uncased BERT WordPiece),
kept byte-for-byte equivalent so the quantization measurement harness tokenizes exactly the way the
app does. Do not diverge from the .NET implementation without updating both.
"""

import unicodedata


class WordPieceTokenizer:
    def __init__(self, vocab_lines: list[str]):
        self._vocab = {tok: i for i, tok in enumerate(vocab_lines)}
        self.cls_id = self._vocab["[CLS]"]
        self.sep_id = self._vocab["[SEP]"]
        self._unk_id = self._vocab["[UNK]"]
        self._pad_id = self._vocab["[PAD]"]

    def encode(self, text: str, max_len: int = 64) -> tuple[list[int], list[int]]:
        pieces = [self.cls_id]
        for word in self._basic_tokenize(text):
            for piece in self._wordpiece(word):
                if len(pieces) >= max_len - 1:
                    break
                pieces.append(piece)
        pieces.append(self.sep_id)

        ids = [self._pad_id] * max_len
        mask = [0] * max_len
        for i in range(max_len):
            if i < len(pieces):
                ids[i] = pieces[i]
                mask[i] = 1
        return ids, mask

    @staticmethod
    def _basic_tokenize(text: str) -> list[str]:
        normalized = unicodedata.normalize("NFD", text.lower())
        words: list[str] = []
        buf: list[str] = []

        def flush():
            if buf:
                words.append("".join(buf))
                buf.clear()

        for ch in normalized:
            if unicodedata.category(ch) == "Mn":
                continue  # drop accents (nonspacing mark), matches CharUnicodeInfo check
            if ch.isspace():
                flush()
            elif unicodedata.category(ch)[0] in ("P", "S"):
                flush()
                words.append(ch)
            else:
                buf.append(ch)
        flush()
        return words

    def _wordpiece(self, word: str) -> list[int]:
        start = 0
        output: list[int] = []
        while start < len(word):
            end = len(word)
            matched = -1
            while start < end:
                sub = ("##" if start > 0 else "") + word[start:end]
                if sub in self._vocab:
                    matched = self._vocab[sub]
                    break
                end -= 1
            if matched < 0:
                return [self._unk_id]
            output.append(matched)
            start = end
        return output
