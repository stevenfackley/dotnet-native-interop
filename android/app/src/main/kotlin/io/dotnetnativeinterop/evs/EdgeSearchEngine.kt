package io.dotnetnativeinterop.evs

import io.dotnetnativeinterop.model.EdgeChunk
import io.dotnetnativeinterop.model.EdgeHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Loads the encoder + index once from the extracted assets dir, then ranks the index by cosine similarity
 * to a query (vectors are L2-normalized, so cosine == dot). App-layer only — no engine calls.
 */
public class EdgeSearchEngine(private val assetsDir: File) {

    private val gate = Mutex()
    private var tokenizer: WordPieceTokenizer? = null
    private var encoder: EvsEncoder? = null
    private var chunks: List<EdgeChunk> = emptyList()

    private suspend fun ensureLoaded() {
        if (encoder != null) return
        gate.withLock {
            if (encoder == null) {
                tokenizer = WordPieceTokenizer(File(assetsDir, "vocab.txt").readLines())
                chunks = EdgeIndex.load(File(assetsDir, "edge-index.db").absolutePath)
                encoder = EvsEncoder(File(assetsDir, "model.onnx").absolutePath)
            }
        }
    }

    public suspend fun facets(): Pair<List<String>, List<String>> {
        ensureLoaded()
        val codes = chunks.flatMap { it.errorCodes }.distinct().sorted()
        val tools = chunks.flatMap { it.toolsRequired }.distinct().sorted()
        return codes to tools
    }

    public suspend fun search(
        query: String,
        minScore: Float = 0.15f, // low NOISE FLOOR, not a relevance gate: show top-K, the score shows confidence (mirrors iOS + dni_search)
        topK: Int = 20,
        errorCodes: Set<String> = emptySet(),
        tools: Set<String> = emptySet(),
    ): List<EdgeHit> = withContext(Dispatchers.Default) {
        ensureLoaded()
        val enc = encoder ?: error("Edge search engine is not loaded (encoder missing)")
        val tok = tokenizer ?: error("Edge search engine is not loaded (tokenizer missing)")
        val (ids, mask) = tok.encode(query)
        val q = gate.withLock { enc.embed(ids, mask) }
        chunks.asSequence()
            .filter { c -> errorCodes.isEmpty() || c.errorCodes.any { it in errorCodes } }
            .filter { c -> tools.isEmpty() || c.toolsRequired.any { it in tools } }
            .map { c -> EdgeHit(c, cosine(q, c.embedding)) }
            .filter { it.score >= minScore }
            .sortedByDescending { it.score }
            .take(topK)
            .toList()
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        val n = minOf(a.size, b.size)
        for (i in 0 until n) dot += a[i] * b[i]
        return dot
    }
}
