package io.dotnetnativeinterop.trust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json

/**
 * Locks the trust-posture JSON contract to the engine's `TrustPosture.ReportJson()` camelCase output
 * (source-gen, JsonKnownNamingPolicy.CamelCase). If the engine record fields drift, this fails.
 */
public class TrustModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    // Verbatim shape of the engine's TrustPosture.Report() with a live PQ channel up.
    private val secureJson = """
        {
          "transports": [
            {"transport":"ffi","inProcess":true,"encrypted":false,"wire":"in-process (no socket)","detail":"C ABI call across the boundary; trust boundary is the OS process."},
            {"transport":"http","inProcess":false,"encrypted":false,"wire":"127.0.0.1 loopback TCP","detail":"PLAINTEXT loopback HTTP — deliberate and disclosed."},
            {"transport":"sqlcipher","inProcess":false,"encrypted":true,"wire":"on-disk SQLCipher database (WAL)","detail":"AES-256 encrypted at rest via PRAGMA key."},
            {"transport":"binary","inProcess":false,"encrypted":true,"wire":"127.0.0.1 loopback TCP · framed protobuf","detail":"PQ channel up: ML-KEM-768 key exchange, ML-DSA-65 handshake signature, AES-256-GCM per frame."}
          ],
          "binaryPqChannel": {"kem":"ML-KEM-768","sig":"ML-DSA-65","cipher":"AES-256-GCM","kemPublicKeyBytes":1184,"ciphertextBytes":1088,"sharedSecretBytes":32,"handshakeUs":1543.2}
        }
    """.trimIndent()

    @Test
    public fun parsesSecurePostureWithLiveParams() {
        val report = json.decodeFromString<TrustPostureReport>(secureJson)
        assertEquals(4, report.transports.size)

        val http = report.transports.first { it.transport == "http" }
        assertFalse("HTTP must report as plaintext", http.encrypted)

        val binary = report.transports.first { it.transport == "binary" }
        assertTrue(binary.encrypted)

        val pq = report.binaryPqChannel!!
        assertEquals("ML-KEM-768", pq.kem)
        assertEquals("ML-DSA-65", pq.sig)
        assertEquals("AES-256-GCM", pq.cipher)
        assertEquals(1184, pq.kemPublicKeyBytes)
        assertEquals(1088, pq.ciphertextBytes)
        assertEquals(32, pq.sharedSecretBytes)
        assertEquals(1543.2, pq.handshakeUs, 1e-9)
    }

    @Test
    public fun parsesPlaintextPostureWithNullChannel() {
        val plaintext = """
            {"transports":[{"transport":"binary","inProcess":false,"encrypted":false,"wire":"127.0.0.1 loopback TCP · framed protobuf","detail":"Framed protobuf over PLAINTEXT loopback. Start with the PQ flag to negotiate a channel."}],"binaryPqChannel":null}
        """.trimIndent()
        val report = json.decodeFromString<TrustPostureReport>(plaintext)
        assertNull(report.binaryPqChannel)
        assertFalse(report.transports.single().encrypted)
    }
}
