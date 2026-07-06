package io.dotnetnativeinterop.transport.pb

import dni.frame.v1.Envelope
import dni.frame.v1.FeatureRunPb
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Length-prefixed framing ([u32 little-endian length][payload]) and Envelope round-tripping — the wire
 * the engine's FrameConnection reads/writes. Uses in-memory streams so the framing is provable without a
 * socket. Also checks the plaintext<->encrypted symmetry (a cipher-attached writer's frames decode on a
 * mirror-cipher reader).
 */
public class FrameChannelTest {

    @Test
    public fun lengthPrefixIsU32LittleEndianOfThePayload() {
        val out = ByteArrayOutputStream()
        FrameChannel(ByteArrayInputStream(ByteArray(0)), out).writeFrame(byteArrayOf(1, 2, 3, 4, 5))
        val bytes = out.toByteArray()
        // 5 bytes payload -> little-endian length prefix 05 00 00 00, then the payload.
        assertArrayEquals(byteArrayOf(5, 0, 0, 0, 1, 2, 3, 4, 5), bytes)
    }

    @Test
    public fun envelopeFrameRoundTrips() {
        val request = PbEnvelopes.run("req-1", "ping")

        val out = ByteArrayOutputStream()
        FrameChannel(ByteArrayInputStream(ByteArray(0)), out).writeFrame(request.toByteArray())

        val reader = FrameChannel(ByteArrayInputStream(out.toByteArray()), ByteArrayOutputStream())
        val frame = reader.readFrame()!!
        val parsed = Envelope.parseFrom(frame)
        assertEquals(Envelope.BodyCase.RUN, parsed.bodyCase)
        assertEquals("req-1", parsed.requestId)
        assertEquals("ping", parsed.run.id)
    }

    @Test
    public fun cleanEofAtFrameBoundaryReturnsNull() {
        val reader = FrameChannel(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream())
        assertNull(reader.readFrame())
    }

    // ---- hostile-input controls (the 16 MB frame cap is the one adversarial guard) ----------------

    @Test
    public fun lengthPrefixOverTheCapIsRejected() {
        // A length prefix claiming > MAX_FRAME_BYTES must be refused BEFORE any allocation.
        val prefix = leU32(FrameChannel.MAX_FRAME_BYTES.toLong() + 1)
        val reader = FrameChannel(ByteArrayInputStream(prefix), ByteArrayOutputStream())
        assertThrows(IOException::class.java) { reader.readFrame() }
    }

    @Test
    public fun zeroLengthPrefixIsRejected() {
        val reader = FrameChannel(ByteArrayInputStream(leU32(0)), ByteArrayOutputStream())
        assertThrows(IOException::class.java) { reader.readFrame() }
    }

    @Test
    public fun truncatedPayloadThrowsEof() {
        // Prefix promises 10 bytes but only 3 follow before EOF — a truncated frame, not a clean boundary.
        val truncated = leU32(10) + byteArrayOf(1, 2, 3)
        val reader = FrameChannel(ByteArrayInputStream(truncated), ByteArrayOutputStream())
        assertThrows(EOFException::class.java) { reader.readFrame() }
    }

    private fun leU32(value: Long): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array()

    @Test
    public fun encryptedFrameDecodesOnMirrorCipherReader() {
        val keyC2S = ByteArray(32) { it.toByte() }
        val keyS2C = ByteArray(32) { (200 - it).toByte() }

        val out = ByteArrayOutputStream()
        val writer = FrameChannel(ByteArrayInputStream(ByteArray(0)), out)
        writer.useCipher(AeadFrameCipher(sendKey = keyC2S, recvKey = keyS2C))
        val request = PbEnvelopes.features("secure-req")
        writer.writeFrame(request.toByteArray())

        val reader = FrameChannel(ByteArrayInputStream(out.toByteArray()), ByteArrayOutputStream())
        reader.useCipher(AeadFrameCipher(sendKey = keyS2C, recvKey = keyC2S))
        val parsed = Envelope.parseFrom(reader.readFrame()!!)
        assertEquals(Envelope.BodyCase.FEATURES, parsed.bodyCase)
        assertEquals("secure-req", parsed.requestId)
    }

    @Test
    public fun featureRunMappingMatchesEngineFields() {
        // The pb payload mapper must project onto the exact FeatureResult fields the other transports use.
        val pb = FeatureRunPb.newBuilder()
            .setId("ping").setResult("pong").setElapsedMs(1.5).setOk(true).build()
        val result = pb.toModel()
        assertEquals("ping", result.id)
        assertEquals("pong", result.result)
        assertEquals(1.5, result.elapsedMs, 1e-9)
        assertEquals(true, result.ok)
    }
}
