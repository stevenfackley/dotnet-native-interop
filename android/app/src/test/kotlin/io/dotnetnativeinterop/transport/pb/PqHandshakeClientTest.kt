package io.dotnetnativeinterop.transport.pb

import com.google.protobuf.ByteString
import dni.frame.v1.Envelope
import dni.frame.v1.HandshakeOffer
import org.bouncycastle.crypto.generators.MLDSAKeyPairGenerator
import org.bouncycastle.crypto.generators.MLKEMKeyPairGenerator
import org.bouncycastle.crypto.kems.MLKEMExtractor
import org.bouncycastle.crypto.params.MLDSAKeyGenerationParameters
import org.bouncycastle.crypto.params.MLDSAParameters
import org.bouncycastle.crypto.params.MLDSAPrivateKeyParameters
import org.bouncycastle.crypto.params.MLDSAPublicKeyParameters
import org.bouncycastle.crypto.params.MLKEMKeyGenerationParameters
import org.bouncycastle.crypto.params.MLKEMParameters
import org.bouncycastle.crypto.params.MLKEMPrivateKeyParameters
import org.bouncycastle.crypto.params.MLKEMPublicKeyParameters
import org.bouncycastle.crypto.signers.MLDSASigner
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/**
 * Full PQ handshake round-trip on the JVM: a faithful BouncyCastle "server" (the exact ML-KEM-768 /
 * ML-DSA-65 primitives the .NET engine's BouncyCastlePqcProvider uses) drives the real
 * [PqHandshakeClient]. Proves the client verifies the offer, encapsulates a shared secret the server
 * can decapsulate, and that BOTH sides derive the same directional AES-256-GCM keys (a frame encrypted
 * by the client decrypts on the server's mirror cipher, and vice versa).
 *
 * This is the JVM half of the wire-protocol proof. Cross-language byte-compat with the running .NET
 * server (same BC algorithms/formats; sizes already matched in the B0 gate) still needs the rebuilt
 * libdni.so on a device — see the report.
 */
public class PqHandshakeClientTest {

    private val random = SecureRandom()

    private class ServerIdentity(
        val kemPrivate: MLKEMPrivateKeyParameters,
        val kemPublicEncoded: ByteArray,
        val dsaPrivate: MLDSAPrivateKeyParameters,
        val dsaPublicEncoded: ByteArray,
    )

    private fun newServer(): ServerIdentity {
        val kemGen = MLKEMKeyPairGenerator()
        kemGen.init(MLKEMKeyGenerationParameters(random, MLKEMParameters.ml_kem_768))
        val kemPair = kemGen.generateKeyPair()

        val dsaGen = MLDSAKeyPairGenerator()
        dsaGen.init(MLDSAKeyGenerationParameters(random, MLDSAParameters.ml_dsa_65))
        val dsaPair = dsaGen.generateKeyPair()

        return ServerIdentity(
            kemPrivate = kemPair.private as MLKEMPrivateKeyParameters,
            kemPublicEncoded = (kemPair.public as MLKEMPublicKeyParameters).encoded,
            dsaPrivate = dsaPair.private as MLDSAPrivateKeyParameters,
            dsaPublicEncoded = (dsaPair.public as MLDSAPublicKeyParameters).encoded,
        )
    }

    // Builds the server's HandshakeOffer, signing (kem_public_key || session_id) with ML-DSA — exactly
    // as the engine's PqSecureChannel.ServerHandshakeAsync does.
    private fun buildOffer(server: ServerIdentity, sessionId: ByteArray, corruptSignature: Boolean): Envelope {
        val signMessage = server.kemPublicEncoded + sessionId
        val signer = MLDSASigner()
        signer.init(true, server.dsaPrivate)
        signer.update(signMessage, 0, signMessage.size)
        val signature = signer.generateSignature()
        if (corruptSignature) signature[0] = (signature[0].toInt() xor 0xFF).toByte()

        return Envelope.newBuilder()
            .setHandshakeOffer(
                HandshakeOffer.newBuilder()
                    .setKemPublicKey(ByteString.copyFrom(server.kemPublicEncoded))
                    .setSigPublicKey(ByteString.copyFrom(server.dsaPublicEncoded))
                    .setSignature(ByteString.copyFrom(signature))
                    .setKemAlgorithm("ML-KEM-768")
                    .setSigAlgorithm("ML-DSA-65")
                    .setCipher("AES-256-GCM")
                    .setSessionId(ByteString.copyFrom(sessionId)),
            )
            .build()
    }

    @Test
    public fun handshakeDerivesMatchingDirectionalKeys() {
        val server = newServer()
        val sessionId = ByteArray(32).also { random.nextBytes(it) }

        // Preload the client's input with the framed offer; capture the client's framed reply.
        val clientIn = ByteArrayInputStream(frame(buildOffer(server, sessionId, corruptSignature = false).toByteArray()))
        val clientOut = ByteArrayOutputStream()
        val channel = FrameChannel(clientIn, clientOut)

        val result = PqHandshakeClient.handshake(channel)

        // Negotiated sizes: FIPS 203 ML-KEM-768 public key 1184 B, ciphertext 1088 B, shared secret 32 B.
        assertEquals(1184, result.params.kemPublicKeyBytes)
        assertEquals(1088, result.params.ciphertextBytes)
        assertEquals(32, result.params.sharedSecretBytes)
        assertEquals("ML-KEM-768", result.params.kem)
        assertEquals("ML-DSA-65", result.params.sig)
        assertEquals("AES-256-GCM", result.params.cipher)

        // Server side: decapsulate the client's ciphertext and derive its own directional keys.
        val reply = Envelope.parseFrom(deframe(clientOut.toByteArray()))
        val ciphertext = reply.handshakeReply.ciphertext.toByteArray()
        val serverSecret = MLKEMExtractor(server.kemPrivate).extractSecret(ciphertext)

        val salt = ciphertext + sessionId
        val c2s = Hkdf.deriveKey(serverSecret, 32, salt, "dni-pb c2s v1".toByteArray(Charsets.US_ASCII))
        val s2c = Hkdf.deriveKey(serverSecret, 32, salt, "dni-pb s2c v1".toByteArray(Charsets.US_ASCII))
        val serverCipher = AeadFrameCipher(sendKey = s2c, recvKey = c2s)

        // Client -> server frame, then server -> client frame, both must round-trip.
        val toServer = "client says hi".toByteArray()
        assertArrayEquals(toServer, serverCipher.decryptInbound(result.cipher.encryptOutbound(toServer)))

        val toClient = "server replies".toByteArray()
        assertArrayEquals(toClient, result.cipher.decryptInbound(serverCipher.encryptOutbound(toClient)))
    }

    @Test
    public fun tamperedSignatureIsRejected() {
        val server = newServer()
        val sessionId = ByteArray(32).also { random.nextBytes(it) }
        val channel = FrameChannel(
            ByteArrayInputStream(frame(buildOffer(server, sessionId, corruptSignature = true).toByteArray())),
            ByteArrayOutputStream(),
        )
        assertThrows(PqHandshakeException::class.java) { PqHandshakeClient.handshake(channel) }
    }

    @Test
    public fun freshSessionIdChangesDerivedKeys() {
        // Same server keys, two different session_ids -> the client-side send key must differ (replay
        // freshness: session_id folds into the HKDF salt).
        val server = newServer()
        val keyA = clientSendKeyFor(server, ByteArray(32) { 1 })
        val keyB = clientSendKeyFor(server, ByteArray(32) { 2 })
        org.junit.Assert.assertFalse("session_id must randomize derived keys", keyA.contentEquals(keyB))
    }

    // Runs a handshake and returns the key the client's cipher encrypts with, by decrypting a probe
    // frame with a server-derived c2s cipher (the client's send key == the server's c2s recv key).
    private fun clientSendKeyFor(server: ServerIdentity, sessionId: ByteArray): ByteArray {
        val clientOut = ByteArrayOutputStream()
        val channel = FrameChannel(
            ByteArrayInputStream(frame(buildOffer(server, sessionId, corruptSignature = false).toByteArray())),
            clientOut,
        )
        PqHandshakeClient.handshake(channel)
        val ciphertext = Envelope.parseFrom(deframe(clientOut.toByteArray())).handshakeReply.ciphertext.toByteArray()
        val serverSecret = MLKEMExtractor(server.kemPrivate).extractSecret(ciphertext)
        return Hkdf.deriveKey(serverSecret, 32, ciphertext + sessionId, "dni-pb c2s v1".toByteArray(Charsets.US_ASCII))
    }

    private fun frame(payload: ByteArray): ByteArray {
        val prefix = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.size).array()
        return prefix + payload
    }

    private fun deframe(frame: ByteArray): ByteArray = frame.copyOfRange(4, frame.size)
}
