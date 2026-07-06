package io.dotnetnativeinterop.transport.pb

import com.google.protobuf.ByteString
import dni.frame.v1.Envelope
import dni.frame.v1.HandshakeReply
import org.bouncycastle.crypto.kems.MLKEMGenerator
import org.bouncycastle.crypto.params.MLDSAParameters
import org.bouncycastle.crypto.params.MLDSAPublicKeyParameters
import org.bouncycastle.crypto.params.MLKEMParameters
import org.bouncycastle.crypto.params.MLKEMPublicKeyParameters
import org.bouncycastle.crypto.signers.MLDSASigner
import java.security.SecureRandom

/** The negotiated params of a completed PQ handshake — feeds the Trust inspector's live-params card. */
internal data class PqChannelParams(
    val kem: String,
    val sig: String,
    val cipher: String,
    val kemPublicKeyBytes: Int,
    val ciphertextBytes: Int,
    val sharedSecretBytes: Int,
    val handshakeUs: Double,
)

/** The outcome of a client handshake: the per-frame cipher plus the negotiated params for the UI. */
internal class PqHandshakeResult(val cipher: AeadFrameCipher, val params: PqChannelParams)

/** Thrown when the PQ handshake cannot be completed (bad offer, failed signature, wrong frame type). */
internal class PqHandshakeException(message: String) : Exception(message)

/**
 * Client side of the framed-protobuf PQ handshake — the mirror of the engine's
 * `PqSecureChannel.ServerHandshakeAsync`, run over a still-plaintext [FrameChannel] before any
 * application frames flow. One round trip:
 *
 *   server -> client  HandshakeOffer  { ML-KEM-768 public key, ML-DSA-65 public key, session_id (fresh),
 *                                        ML-DSA signature over (kem_public_key || session_id), alg labels }
 *   client -> server  HandshakeReply  { ML-KEM ciphertext encapsulating the shared secret }
 *
 * The client verifies the signature over (kem_public_key || session_id) BEFORE encapsulating, then both
 * sides HKDF-SHA256 the shared secret into two directional AES-256-GCM keys (salt = ciphertext ||
 * session_id, so a replayed ciphertext can never resurrect the same keys) and switch the channel to
 * [AeadFrameCipher]. Uses BouncyCastle's LIGHTWEIGHT `org.bouncycastle.crypto.*` API directly — never
 * the JCA "BC" provider, whose Android-bundled legacy copy lacks these PQC classes entirely.
 *
 * SECURITY NOTE (loopback teaching artifact): the ML-DSA signature authenticates handshake transcript
 * INTEGRITY against a passive tamperer — it does NOT pin the server's identity (trust-on-first-use).
 * Nothing here checks that the offered sig_public_key belongs to the expected server. Do not use off
 * loopback without pinning the server's ML-DSA public key.
 */
internal object PqHandshakeClient {
    // Directional HKDF info labels — ASCII, byte-identical to the engine's InfoClientToServer /
    // InfoServerToClient. The client SENDS on c2s and RECEIVES on s2c (mirror of the server).
    private val INFO_CLIENT_TO_SERVER = "dni-pb c2s v1".toByteArray(Charsets.US_ASCII)
    private val INFO_SERVER_TO_CLIENT = "dni-pb s2c v1".toByteArray(Charsets.US_ASCII)
    private const val KEY_BYTES = 32

    private val random = SecureRandom()

    /**
     * Runs the handshake over [channel] (which must NOT yet have a cipher attached) and returns the
     * negotiated cipher + params. Does NOT attach the cipher to the channel — the caller does that after
     * this returns, so the handshake frames themselves stay plaintext (as the server expects).
     */
    fun handshake(channel: FrameChannel): PqHandshakeResult {
        val start = System.nanoTime()

        val offerBytes = channel.readFrame()
            ?: throw PqHandshakeException("server closed the connection before sending a handshake offer")
        val offerEnvelope = Envelope.parseFrom(offerBytes)
        if (offerEnvelope.bodyCase != Envelope.BodyCase.HANDSHAKE_OFFER) {
            throw PqHandshakeException("expected a handshake offer but received ${offerEnvelope.bodyCase}")
        }

        val offer = offerEnvelope.handshakeOffer
        val kemPublicKey = offer.kemPublicKey.toByteArray()
        val sessionId = offer.sessionId.toByteArray()

        // Verify the ML-DSA signature over (kem_public_key || session_id) — exactly what the server signed.
        val message = concat(kemPublicKey, sessionId)
        if (!verify(offer.sigPublicKey.toByteArray(), message, offer.signature.toByteArray())) {
            throw PqHandshakeException("server handshake signature failed ML-DSA verification")
        }

        // Encapsulate to the server's ML-KEM public key -> (ciphertext on the wire, shared secret local).
        // A wrong-length/garbage key throws a raw BC IllegalArgumentException/DataLengthException; wrap it
        // so a malformed offer surfaces as the PqHandshakeException the class contract promises.
        val kemPub = wrapBc("invalid ML-KEM public key in offer") {
            MLKEMPublicKeyParameters(MLKEMParameters.ml_kem_768, kemPublicKey)
        }
        val encapsulated = wrapBc("ML-KEM encapsulation failed") { MLKEMGenerator(random).generateEncapsulated(kemPub) }
        val ciphertext = encapsulated.encapsulation
        val sharedSecret = encapsulated.secret

        val reply = Envelope.newBuilder()
            .setHandshakeReply(HandshakeReply.newBuilder().setCiphertext(ByteString.copyFrom(ciphertext)))
            .build()
        channel.writeFrame(reply.toByteArray())

        // Derive the two directional keys. salt = ciphertext || session_id (session_id in the SALT, not the
        // info, so a fresh session_id randomizes the extracted PRK — matching the engine's DeriveKeys).
        val salt = concat(ciphertext, sessionId)
        val clientToServer = Hkdf.deriveKey(sharedSecret, KEY_BYTES, salt, INFO_CLIENT_TO_SERVER)
        val serverToClient = Hkdf.deriveKey(sharedSecret, KEY_BYTES, salt, INFO_SERVER_TO_CLIENT)
        val cipher = AeadFrameCipher(sendKey = clientToServer, recvKey = serverToClient)

        val handshakeUs = (System.nanoTime() - start) / 1000.0
        val params = PqChannelParams(
            kem = offer.kemAlgorithm.ifEmpty { "ML-KEM-768" },
            sig = offer.sigAlgorithm.ifEmpty { "ML-DSA-65" },
            cipher = offer.cipher.ifEmpty { AeadFrameCipher.ALGORITHM },
            kemPublicKeyBytes = kemPublicKey.size,
            ciphertextBytes = ciphertext.size,
            sharedSecretBytes = sharedSecret.size,
            handshakeUs = handshakeUs,
        )
        return PqHandshakeResult(cipher, params)
    }

    // Verifies an ML-DSA-65 signature over [message] with the server's encoded public key. Private: only
    // handshake() uses it. A malformed public key/signature (raw BC exception) is wrapped as a
    // PqHandshakeException; a well-formed-but-wrong signature simply returns false.
    private fun verify(sigPublicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        wrapBc("ML-DSA verification error") {
            val publicKey = MLDSAPublicKeyParameters(MLDSAParameters.ml_dsa_65, sigPublicKey)
            val verifier = MLDSASigner()
            verifier.init(false, publicKey)
            verifier.update(message, 0, message.size)
            verifier.verifySignature(signature)
        }

    // Runs a BouncyCastle call, translating its raw RuntimeExceptions (IllegalArgumentException,
    // DataLengthException, …) into the PqHandshakeException the class contract promises. A
    // PqHandshakeException already thrown inside passes through unchanged.
    private inline fun <T> wrapBc(what: String, block: () -> T): T =
        try {
            block()
        } catch (e: PqHandshakeException) {
            throw e
        } catch (e: RuntimeException) {
            throw PqHandshakeException("$what: ${e.message}")
        }

    private fun concat(first: ByteArray, second: ByteArray): ByteArray {
        val result = ByteArray(first.size + second.size)
        System.arraycopy(first, 0, result, 0, first.size)
        System.arraycopy(second, 0, result, first.size, second.size)
        return result
    }
}
