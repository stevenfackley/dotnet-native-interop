package io.dotnetnativeinterop.transport.pb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * HKDF-SHA256 verified against RFC 5869 Appendix A test vectors. Because the engine derives its
 * per-direction keys with the BCL's `HKDF.DeriveKey(SHA256, ...)` (also RFC 5869), matching the RFC
 * here is what guarantees the client and engine derive the SAME AES-256-GCM keys from the same inputs.
 */
public class HkdfTest {

    @Test
    public fun rfc5869TestCase1() {
        val ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        val expected = hex(
            "3cb25f25faacd57a90434f64d0362f2a" +
                "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865",
        )
        assertArrayEquals(expected, Hkdf.deriveKey(ikm, 42, salt, info))
    }

    @Test
    public fun rfc5869TestCase3_emptySaltAndInfo() {
        val ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val expected = hex(
            "8da4e775a563c18f715f802a063c5a31" +
                "b8a11f5c5ee1879ec3454e5f3c738d2d" +
                "9d201395faa4b61a96c8",
        )
        assertArrayEquals(expected, Hkdf.deriveKey(ikm, 42, ByteArray(0), ByteArray(0)))
    }

    @Test
    public fun deriveKeyReturnsRequestedLength() {
        val key = Hkdf.deriveKey(ByteArray(32) { 1 }, 32, ByteArray(16) { 2 }, "dni-pb c2s v1".toByteArray())
        assertEquals(32, key.size)
    }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }
}
