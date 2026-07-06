package io.dotnetnativeinterop.transport.pb

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Per-frame AES-256-GCM for the framed-protobuf secure channel — the client mirror of the engine's
 * `AeadFrameCipher`. Each direction has its OWN key (HKDF with a distinct info label; see
 * [PqHandshakeClient]) and its OWN monotonic frame counter used as the GCM nonce, so a nonce is never
 * reused under a key.
 *
 * On the wire an encrypted frame payload is `ciphertext || tag(16)`; the 12-byte nonce is implicit (both
 * endpoints derive it from their frame counter) and never transmitted. `javax.crypto` AES/GCM/NoPadding
 * appends the 16-byte GCM tag to the ciphertext on encrypt — the exact `ciphertext || tag` layout the
 * engine's `AesGcm.Encrypt(cipherSpan, tagSpan)` produces — so the two are byte-compatible. No AAD is
 * passed (a deliberate match: there is no cleartext header to bind; the monotonic counter=nonce already
 * authenticates frame order).
 */
internal class AeadFrameCipher(private val sendKey: ByteArray, private val recvKey: ByteArray) {
    private var sendCounter = 0L
    private var recvCounter = 0L

    /** Encrypts one frame payload; returns `ciphertext || tag`. Advances the send counter. */
    fun encryptOutbound(plaintext: ByteArray): ByteArray {
        val nonce = nonceFor(sendCounter++)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sendKey, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        return cipher.doFinal(plaintext)
    }

    /**
     * Decrypts one `ciphertext || tag` frame payload. Advances the receive counter. Throws
     * `javax.crypto.AEADBadTagException` (the client-side analogue of the engine's
     * `AuthenticationTagMismatchException`) if the frame was tampered with, forged, or reordered.
     */
    fun decryptInbound(frame: ByteArray): ByteArray {
        val nonce = nonceFor(recvCounter++)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(recvKey, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        return cipher.doFinal(frame)
    }

    companion object {
        /** Cipher name for the Trust inspector's live-params display (matches the engine's label). */
        const val ALGORITHM = "AES-256-GCM"

        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128   // 16-byte GCM tag (also CryptoKit's required size on Apple)
        private const val NONCE_BYTES = 12

        // Nonce = little-endian frame counter in the low 8 bytes, high 4 bytes zero — identical to the
        // engine's BinaryPrimitives.WriteInt64LittleEndian(nonce, counter) into a 12-byte buffer.
        private fun nonceFor(counter: Long): ByteArray {
            val nonce = ByteArray(NONCE_BYTES)
            ByteBuffer.wrap(nonce).order(ByteOrder.LITTLE_ENDIAN).putLong(counter)
            return nonce
        }
    }
}
