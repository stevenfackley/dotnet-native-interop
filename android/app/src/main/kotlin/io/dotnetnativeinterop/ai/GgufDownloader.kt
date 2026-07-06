package io.dotnetnativeinterop.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * The RAG GGUF filename. Matches EngineHost.BuildRagModel's probe (NativeBridge, C#) exactly — that
 * is where the file must land for the engine to pick it up. ForemanHost.BuildBrain (Engine, C#)
 * keeps its own copy of this same literal for the same reason (see that file's comment on the
 * deliberate, tiny duplication across the ABI boundary); this is the Kotlin side of that pattern.
 */
public const val GGUF_FILE_NAME: String = "Llama-3.2-1B-Instruct-Q4_K_M.gguf"

private const val GGUF_MAGIC = "GGUF"

/** Download lifecycle for the on-device neural RAG model. */
public sealed interface GgufDownloadState {
    /** No download attempted yet this process (or the model was already present at construction). */
    public data object NotStarted : GgufDownloadState

    /** Streaming; [bytesDownloaded]/[totalBytes] drive the progress bar. [totalBytes] may be 0 if the server didn't disclose a length. */
    public data class Downloading(val bytesDownloaded: Long, val totalBytes: Long) : GgufDownloadState {
        public val percent: Int get() = if (totalBytes > 0) ((bytesDownloaded * 100L) / totalBytes).toInt() else 0
    }

    /** Transfer complete; checking the GGUF magic header (+ optional SHA-256) before the atomic rename. */
    public data object Verifying : GgufDownloadState

    /** Renamed into place; the engine can now find it at the assets-dir path. */
    public data object Completed : GgufDownloadState

    /** User- or caller-cancelled. The `.part` file (if any bytes landed) is left in place so a later [GgufDownloader.download] call resumes instead of restarting. */
    public data object Cancelled : GgufDownloadState

    /** Network error, non-2xx/206 response, bad GGUF magic, or a SHA-256 mismatch. [message] is surfaced verbatim in the UI. */
    public data class Failed(val message: String) : GgufDownloadState
}

/**
 * Streams the RAG GGUF into [assetsDir] — the SAME directory the engine's `dni_set_assets_dir`
 * points at (see `AssetExtractor.dir` / `DotnetNativeInteropApp.loadNativeLibraries`) — with:
 *  - HTTP Range resume: a partially-downloaded `.part` file is continued from its current length;
 *    if the server ignores the Range header (200 instead of 206) the `.part` file is truncated and
 *    the download restarts from zero rather than corrupting the file by blindly appending.
 *  - Atomic rename from `<name>.gguf.part` to `<name>.gguf` only after integrity checks pass.
 *  - A GGUF-magic ("GGUF" byte header) integrity gate, plus an OPTIONAL SHA-256 check
 *    ([expectedSha256] empty = skip). The engine's own `LlamaLanguageModel` load success is the
 *    ultimate integrity gate — this class only guards against an obviously wrong/truncated/corrupt
 *    file before ever handing it to the engine; a present-but-unloadable GGUF still degrades
 *    gracefully to the extractive fallback (EngineHost.BuildRagModel's own try/catch).
 *  - Cooperative cancellation via [cancel], which calls `Call.cancel()` on the in-flight OkHttp
 *    call so a blocking read unblocks immediately with an IOException, rather than relying on
 *    Kotlin coroutine cancellation (which cannot interrupt a synchronous, blocking network read).
 *
 * [bufferSize] defaults to 64 KiB; tests shrink it to force multiple read-loop iterations (and
 * therefore multiple progress emissions / a wider cancel window) over a small fake body.
 */
public class GgufDownloader(
    private val assetsDir: File,
    private val client: OkHttpClient = defaultClient,
    private val expectedSha256: String = "",
    private val bufferSize: Int = 64 * 1024,
) {
    private val _state = MutableStateFlow<GgufDownloadState>(
        if (isDownloadedAt(assetsDir)) GgufDownloadState.Completed else GgufDownloadState.NotStarted,
    )
    public val state: StateFlow<GgufDownloadState> = _state.asStateFlow()

    @Volatile private var cancelRequested = false
    @Volatile private var currentCall: okhttp3.Call? = null

    public val targetFile: File get() = File(assetsDir, GGUF_FILE_NAME)
    private val partFile: File get() = File(assetsDir, "$GGUF_FILE_NAME.part")

    public fun isDownloaded(): Boolean = isDownloadedAt(assetsDir)

    /** Requests cancellation of an in-flight [download]. Idempotent; a no-op if nothing is running. */
    public fun cancel() {
        cancelRequested = true
        currentCall?.cancel()
    }

    /**
     * Downloads [url] into [assetsDir]. Never throws except for genuine coroutine
     * [kotlinx.coroutines.CancellationException] (structured-concurrency scope cancellation, e.g.
     * the owning ViewModel being cleared mid-download) — every other outcome (network failure, bad
     * magic, hash mismatch, user [cancel]) is reflected as a terminal [state] value instead, so
     * callers can drive the UI purely off [state] without a try/catch.
     */
    public suspend fun download(url: String): Unit = withContext(Dispatchers.IO) {
        cancelRequested = false
        assetsDir.mkdirs()
        try {
            runDownload(url)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = GgufDownloadState.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            currentCall = null
        }
    }

    private fun runDownload(url: String) {
        val existing = if (partFile.exists()) partFile.length() else 0L
        // Placeholder until the response headers reveal the real total — totalBytes<=0 renders as
        // 0% rather than a misleading "100%" flash from existing/existing.
        _state.value = GgufDownloadState.Downloading(existing, 0)

        val requestBuilder = Request.Builder().url(url)
        if (existing > 0) {
            requestBuilder.header("Range", "bytes=$existing-")
        }

        val call = client.newCall(requestBuilder.build())
        currentCall = call

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("download failed: HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("download failed: empty response body")

                val resumed = response.code == 206
                val startOffset = if (resumed) existing else 0L
                val total = when {
                    resumed -> parseContentRangeTotal(response.header("Content-Range")) ?: (existing + body.contentLength())
                    else -> body.contentLength()
                }
                _state.value = GgufDownloadState.Downloading(startOffset, total)

                RandomAccessFile(partFile, "rw").use { raf ->
                    if (!resumed) raf.setLength(0)
                    raf.seek(startOffset)
                    body.byteStream().use { input ->
                        val buffer = ByteArray(bufferSize)
                        var downloaded = startOffset
                        while (true) {
                            if (cancelRequested) {
                                _state.value = GgufDownloadState.Cancelled
                                return
                            }
                            val read = input.read(buffer)
                            if (read == -1) break
                            raf.write(buffer, 0, read)
                            downloaded += read
                            _state.value = GgufDownloadState.Downloading(downloaded, if (total > 0) total else downloaded)
                        }
                    }
                }
            }
        } catch (e: IOException) {
            if (cancelRequested) {
                _state.value = GgufDownloadState.Cancelled
                return
            }
            throw e
        }

        _state.value = GgufDownloadState.Verifying

        if (!hasGgufMagic(partFile)) {
            partFile.delete()
            throw IOException("downloaded file failed the GGUF integrity check (bad magic header)")
        }

        if (expectedSha256.isNotBlank()) {
            val actual = sha256Hex(partFile)
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                partFile.delete()
                throw IOException("downloaded file failed the SHA-256 integrity check")
            }
        }

        if (targetFile.exists()) targetFile.delete()
        if (!partFile.renameTo(targetFile)) {
            throw IOException("failed to finalize the downloaded model (rename failed)")
        }
        _state.value = GgufDownloadState.Completed
    }

    private fun hasGgufMagic(file: File): Boolean {
        if (file.length() < 4) return false
        val header = ByteArray(4)
        file.inputStream().use { stream ->
            var offset = 0
            while (offset < 4) {
                val read = stream.read(header, offset, 4 - offset)
                if (read == -1) return false
                offset += read
            }
        }
        return String(header, Charsets.US_ASCII) == GGUF_MAGIC
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(bufferSize)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Parses "bytes 500-999/1234" -> 1234L. Returns null if absent/unparseable (caller falls back to existing + Content-Length). */
    private fun parseContentRangeTotal(contentRange: String?): Long? {
        if (contentRange == null) return null
        val idx = contentRange.lastIndexOf('/')
        if (idx < 0 || idx == contentRange.length - 1) return null
        return contentRange.substring(idx + 1).toLongOrNull()
    }

    private companion object {
        fun isDownloadedAt(dir: File): Boolean {
            val f = File(dir, GGUF_FILE_NAME)
            return f.exists() && f.length() > 0L
        }

        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build()
        }
    }
}
