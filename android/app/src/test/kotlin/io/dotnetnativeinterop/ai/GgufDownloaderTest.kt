package io.dotnetnativeinterop.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Exercises [GgufDownloader] against a real (fake) HTTP server — no device/emulator needed. Covers
 * everything that IS verifiable on Windows without the fresh Wave-B libdni.so: resume via Range,
 * restart when a server ignores Range, truncation handling, GGUF-magic + optional SHA-256 integrity,
 * progress emission, and cancellation. Real neural generation (llama.cpp actually loading the
 * downloaded GGUF) is device-gated and out of scope here.
 */
public class GgufDownloaderTest {

    private lateinit var tempDir: File
    private lateinit var server: MockWebServer

    @Before
    public fun setUp() {
        tempDir = Files.createTempDirectory("gguf-downloader-test").toFile()
        server = MockWebServer()
        server.start()
    }

    @After
    public fun tearDown() {
        server.shutdown()
        tempDir.deleteRecursively()
    }

    /** A fake "GGUF" file: real magic header + deterministic filler, so tests never need the real 0.77 GB model. */
    private fun ggufBody(totalSize: Int, fillerByte: Byte = 0x42): ByteArray {
        val bytes = ByteArray(totalSize) { fillerByte }
        "GGUF".toByteArray(Charsets.US_ASCII).copyInto(bytes, 0)
        return bytes
    }

    private fun partFile(): File = File(tempDir, "$GGUF_FILE_NAME.part")

    // Connect via the literal IPv4 loopback rather than resolving "localhost" (server.url's default
    // host) — on some Windows setups the JVM's dual-stack Happy-Eyeballs-style connect races an
    // unreachable IPv6 loopback first and eats ~20s per affected OkHttpClient instance before it
    // falls back to IPv4. Skipping DNS entirely keeps this suite fast and deterministic.
    private fun serverUrl(path: String): String = "http://127.0.0.1:${server.port}$path"

    @Test
    public fun freshDownloadCompletesAndValidatesMagic(): Unit = runBlocking {
        val body = ggufBody(5_000)
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(body)))

        val downloader = GgufDownloader(tempDir, bufferSize = 512)
        assertFalse(downloader.isDownloaded())
        downloader.download(serverUrl("/model.gguf"))

        assertEquals(GgufDownloadState.Completed, downloader.state.value)
        assertTrue(downloader.isDownloaded())
        assertArrayEquals(body, downloader.targetFile.readBytes())
        assertFalse(partFile().exists())
    }

    @Test
    public fun resumeContinuesFromExistingPartialFileViaRange(): Unit = runBlocking {
        val body = ggufBody(8_000)
        val splitAt = 3_000
        partFile().writeBytes(body.copyOfRange(0, splitAt))

        val remaining = body.copyOfRange(splitAt, body.size)
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes $splitAt-${body.size - 1}/${body.size}")
                .setBody(Buffer().write(remaining)),
        )

        val downloader = GgufDownloader(tempDir, bufferSize = 256)
        downloader.download(serverUrl("/model.gguf"))

        val recorded = server.takeRequest()
        assertEquals("bytes=$splitAt-", recorded.getHeader("Range"))
        assertEquals(GgufDownloadState.Completed, downloader.state.value)
        assertArrayEquals(body, downloader.targetFile.readBytes())
        assertFalse(partFile().exists())
    }

    @Test
    public fun restartsFromScratchWhenServerIgnoresRangeHeader(): Unit = runBlocking {
        // A stale/foreign partial file — e.g. a prior attempt against a server that doesn't honour
        // Range at all. The downloader must truncate rather than corrupt-by-appending.
        val stale = ByteArray(500) { 0x11 }
        partFile().writeBytes(stale)

        val body = ggufBody(4_000)
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(body)))

        val downloader = GgufDownloader(tempDir, bufferSize = 256)
        downloader.download(serverUrl("/model.gguf"))

        val recorded = server.takeRequest()
        assertEquals("bytes=500-", recorded.getHeader("Range")) // we DID ask for a range...
        assertEquals(GgufDownloadState.Completed, downloader.state.value)
        assertArrayEquals(body, downloader.targetFile.readBytes()) // ...but got 200, so restarted clean
    }

    @Test
    public fun truncatedDownloadPersistsPartialFileAndReportsFailed(): Unit = runBlocking {
        val partial = ggufBody(2_000)
        // Declares more bytes than are actually sent, and forces the socket closed right after —
        // OkHttp detects the short stream (fewer bytes than the promised Content-Length) and throws,
        // simulating a connection drop mid-transfer without relying on SocketPolicy enum trivia.
        // "Connection: close" is what makes this deterministic rather than hanging on keep-alive: it
        // makes MockWebServer physically close the socket the moment the (truncated) body is written.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(partial))
                .setHeader("Content-Length", "50000")
                .setHeader("Connection", "close"),
        )

        // Belt-and-suspenders: even if some MockWebServer version keeps the socket open on a
        // Content-Length mismatch, a short read timeout still fails fast instead of hanging up to
        // the production 120s default.
        val quickTimeoutClient = OkHttpClient.Builder()
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
        val downloader = GgufDownloader(tempDir, client = quickTimeoutClient, bufferSize = 256)
        downloader.download(serverUrl("/model.gguf"))

        assertTrue(downloader.state.value is GgufDownloadState.Failed)
        assertFalse(downloader.targetFile.exists())
        assertTrue(partFile().exists())
        assertEquals(partial.size.toLong(), partFile().length())
    }

    @Test
    public fun rejectsNonGgufMagicHeader(): Unit = runBlocking {
        val body = "NOTAGGUFFILEHEADERBUTPLAUSIBLEBYTES".toByteArray(Charsets.US_ASCII)
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(body)))

        val downloader = GgufDownloader(tempDir)
        downloader.download(serverUrl("/model.gguf"))

        assertTrue(downloader.state.value is GgufDownloadState.Failed)
        assertFalse(downloader.targetFile.exists())
        assertFalse(partFile().exists())
    }

    @Test
    public fun sha256MismatchFailsEvenWithValidMagic(): Unit = runBlocking {
        val body = ggufBody(1_000)
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(body)))

        val downloader = GgufDownloader(tempDir, expectedSha256 = "0".repeat(64))
        downloader.download(serverUrl("/model.gguf"))

        assertTrue(downloader.state.value is GgufDownloadState.Failed)
        assertFalse(downloader.targetFile.exists())
    }

    @Test
    public fun sha256MatchSucceeds(): Unit = runBlocking {
        val body = ggufBody(1_000)
        val expected = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(body)))

        // Mixed-case expected hash exercises the case-insensitive compare.
        val downloader = GgufDownloader(tempDir, expectedSha256 = expected.uppercase())
        downloader.download(serverUrl("/model.gguf"))

        assertEquals(GgufDownloadState.Completed, downloader.state.value)
    }

    @Test
    public fun progressEmitsMultipleIncreasingStepsBeforeCompleting(): Unit = runBlocking {
        val body = ggufBody(20_000)
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(body)))

        val downloader = GgufDownloader(tempDir, bufferSize = 512) // forces ~40 read-loop iterations
        val seen = mutableListOf<GgufDownloadState>()
        val collectJob = launch { downloader.state.collect { seen.add(it) } }

        downloader.download(serverUrl("/model.gguf"))
        collectJob.cancel()

        val downloading = seen.filterIsInstance<GgufDownloadState.Downloading>()
        assertTrue("expected multiple progress emissions, got ${downloading.size}", downloading.size > 1)
        for (i in 1 until downloading.size) {
            assertTrue(downloading[i].bytesDownloaded >= downloading[i - 1].bytesDownloaded)
        }
        assertTrue(seen.contains(GgufDownloadState.Verifying))
        assertEquals(GgufDownloadState.Completed, seen.last())
    }

    @Test
    public fun cancelMidDownloadLeavesResumablePartialFile(): Unit = runBlocking {
        val body = ggufBody(200_000)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(body))
                .throttleBody(4_096, 50, TimeUnit.MILLISECONDS),
        )

        val downloader = GgufDownloader(tempDir, bufferSize = 2_048)
        val job = launch { downloader.download(serverUrl("/model.gguf")) }
        delay(300) // let several throttled chunks land before cancelling
        downloader.cancel()
        job.join()

        assertEquals(GgufDownloadState.Cancelled, downloader.state.value)
        assertFalse(downloader.targetFile.exists())
        assertTrue(partFile().exists())
        assertTrue(partFile().length() in 1 until body.size.toLong())
    }

    @Test
    public fun cancelledDownloadCanBeResumedByASubsequentCall(): Unit = runBlocking {
        val body = ggufBody(100_000)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(body))
                .throttleBody(4_096, 50, TimeUnit.MILLISECONDS),
        )

        val downloader = GgufDownloader(tempDir, bufferSize = 2_048)
        val job = launch { downloader.download(serverUrl("/model.gguf")) }
        delay(200)
        downloader.cancel()
        job.join()
        val resumedFrom = partFile().length()
        assertTrue(resumedFrom in 1 until body.size.toLong())

        val remaining = body.copyOfRange(resumedFrom.toInt(), body.size)
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes $resumedFrom-${body.size - 1}/${body.size}")
                .setBody(Buffer().write(remaining)),
        )

        downloader.download(serverUrl("/model.gguf"))

        assertEquals(GgufDownloadState.Completed, downloader.state.value)
        assertArrayEquals(body, downloader.targetFile.readBytes())
    }

    @Test
    public fun httpErrorResponseFails(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))

        val downloader = GgufDownloader(tempDir)
        downloader.download(serverUrl("/model.gguf"))

        assertTrue(downloader.state.value is GgufDownloadState.Failed)
        assertFalse(downloader.targetFile.exists())
    }

    @Test
    public fun isDownloadedReflectsAnAlreadyPresentModelAtConstruction(): Unit = runBlocking {
        File(tempDir, GGUF_FILE_NAME).writeBytes(ggufBody(10))
        val downloader = GgufDownloader(tempDir)
        assertTrue(downloader.isDownloaded())
        assertEquals(GgufDownloadState.Completed, downloader.state.value)
    }
}
