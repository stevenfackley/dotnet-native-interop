package io.dotnetnativeinterop.evs

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

@Serializable
private data class Fixture(
    val query: String,
    val ids: List<Long>,
    @SerialName("expectedTopChunkId") val expectedTopChunkId: String,
)

public class WordPieceTokenizerTest {
    private val root = File(System.getProperty("user.dir")).parentFile.parentFile
    private val vocab = File(root, "core/DotnetNativeInterop.Engine/Ai/assets/vocab.txt").readLines()
    private val fixture = Json { ignoreUnknownKeys = true }
        .decodeFromString<Fixture>(File(root, "core/DotnetNativeInterop.EdgeIndexPublisher/edge-fixtures.json").readText())

    @Test public fun idsMatchCsharpFixture() {
        val (ids, _) = WordPieceTokenizer(vocab).encode(fixture.query, maxLen = 64)
        assertEquals(fixture.ids, ids.toList())
    }
}
