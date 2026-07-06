package io.dotnetnativeinterop.transport.pb

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 5869 HKDF-SHA256 — byte-for-byte compatible with the engine's
 * `System.Security.Cryptography.HKDF.DeriveKey(SHA256, ikm, L, salt, info)`. Implemented directly over
 * `javax.crypto.Mac` (HmacSHA256) rather than through BouncyCastle so the derivation is transparently
 * verifiable against the RFC test vectors (see HkdfTest); the KEM/DSA primitives still use BC.
 *
 * The secure channel only ever derives 32-byte keys, so Expand emits a single T(1) block, but the loop
 * is written for the general case so the test vectors (which request longer outputs) exercise it too.
 */
internal object Hkdf {
    private const val HASH_LEN = 32

    /** HKDF(ikm, salt, info) -> [outputLength] bytes (Extract then Expand, per RFC 5869). */
    fun deriveKey(ikm: ByteArray, outputLength: Int, salt: ByteArray, info: ByteArray): ByteArray {
        require(outputLength in 1..(255 * HASH_LEN)) { "HKDF output length out of range: $outputLength" }
        val prk = extract(salt, ikm)
        return expand(prk, info, outputLength)
    }

    // HKDF-Extract: PRK = HMAC(salt, ikm). An empty salt is replaced by HashLen zero bytes (RFC 5869 §2.2),
    // matching .NET; in this transport salt is always ciphertext||session_id, so it is never empty.
    private fun extract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val effectiveSalt = if (salt.isEmpty()) ByteArray(HASH_LEN) else salt
        return hmac(effectiveSalt, ikm)
    }

    // HKDF-Expand: T(n) = HMAC(PRK, T(n-1) || info || n), output = T(1) || T(2) || ... truncated to length.
    private fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val take = minOf(previous.size, length - offset)
            System.arraycopy(previous, 0, out, offset, take)
            offset += take
            counter++
        }
        return out
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}
