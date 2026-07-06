package io.dotnetnativeinterop.transport.pb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.AEADBadTagException

/**
 * Per-frame AES-256-GCM behaviour. Two ciphers are configured as the client<->server mirror (client
 * send key == server receive key, and vice versa) exactly as the handshake wires them, so a frame
 * round-trips only when both the directional keys and the monotonic counter=nonce line up.
 */
public class AeadFrameCipherTest {

    private val keyC2S = ByteArray(32) { it.toByte() }
    private val keyS2C = ByteArray(32) { (100 + it).toByte() }

    private fun client() = AeadFrameCipher(sendKey = keyC2S, recvKey = keyS2C)
    private fun server() = AeadFrameCipher(sendKey = keyS2C, recvKey = keyC2S)

    @Test
    public fun clientToServerFrameRoundTrips() {
        val client = client()
        val server = server()
        val plaintext = "framed-protobuf hello".toByteArray()

        val encrypted = client.encryptOutbound(plaintext)
        // ciphertext || tag(16): same length as plaintext plus the 16-byte GCM tag.
        assertEquals(plaintext.size + 16, encrypted.size)
        assertArrayEquals(plaintext, server.decryptInbound(encrypted))
    }

    @Test
    public fun monotonicCounterKeepsMultipleFramesInLockstep() {
        val client = client()
        val server = server()
        val frames = listOf("one", "two", "three", "four").map { it.toByteArray() }

        val wire = frames.map { client.encryptOutbound(it) }
        // Decrypt IN ORDER — each side advances its own counter, so order must be preserved.
        wire.forEachIndexed { i, w -> assertArrayEquals(frames[i], server.decryptInbound(w)) }
    }

    @Test
    public fun reusingNonceIsAvoided_sameKeyDifferentCounterGivesDifferentCiphertext() {
        val client = client()
        val plaintext = "repeat".toByteArray()
        val a = client.encryptOutbound(plaintext)
        val b = client.encryptOutbound(plaintext)
        // Same plaintext, same key, but counter advanced -> different nonce -> different ciphertext.
        assertTrue("nonce reuse would make these identical", !a.contentEquals(b))
    }

    @Test
    public fun tamperedFrameFailsAuthentication() {
        val client = client()
        val server = server()
        val encrypted = client.encryptOutbound("authentic".toByteArray())
        encrypted[0] = (encrypted[0].toInt() xor 0xFF).toByte()
        assertThrows(AEADBadTagException::class.java) { server.decryptInbound(encrypted) }
    }

    @Test
    public fun outOfOrderFrameFailsAuthentication() {
        val client = client()
        val server = server()
        val first = client.encryptOutbound("first".toByteArray())
        val second = client.encryptOutbound("second".toByteArray())
        // Deliver the SECOND frame while the server still expects counter 0 -> wrong nonce -> tag fails.
        assertThrows(AEADBadTagException::class.java) { server.decryptInbound(second) }
        // (first would have decrypted; we deliberately skipped it to force the nonce mismatch.)
        assertTrue(first.isNotEmpty())
    }
}
