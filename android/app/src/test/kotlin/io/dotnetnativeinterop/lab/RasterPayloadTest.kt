package io.dotnetnativeinterop.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

public class RasterPayloadTest {

    @Test
    public fun decodesRgbPayload() {
        // 2x2x3 = 12 bytes: red, green, blue, white
        val bytes = byteArrayOf(
            255.toByte(), 0, 0,
            0, 255.toByte(), 0,
            0, 0, 255.toByte(),
            255.toByte(), 255.toByte(), 255.toByte(),
        )
        val payload = "2x2x3:" + Base64.getEncoder().encodeToString(bytes)
        val img = RasterPayload.decode(payload)!!
        assertEquals(2, img.width)
        assertEquals(2, img.height)
        assertEquals(0xFFFF0000.toInt(), img.pixels[0]) // red
        assertEquals(0xFF00FF00.toInt(), img.pixels[1]) // green
        assertEquals(0xFF0000FF.toInt(), img.pixels[2]) // blue
        assertEquals(0xFFFFFFFF.toInt(), img.pixels[3]) // white
    }

    @Test
    public fun decodesGrayscaleAndLegacyHeader() {
        val bytes = byteArrayOf(0, 128.toByte(), 255.toByte(), 64)
        val payload = "2x2:" + Base64.getEncoder().encodeToString(bytes) // legacy, C defaults to 1
        val img = RasterPayload.decode(payload)!!
        assertEquals(2, img.width)
        assertEquals(0xFF000000.toInt(), img.pixels[0])
        assertEquals(0xFF808080.toInt(), img.pixels[1])
        assertEquals(0xFFFFFFFF.toInt(), img.pixels[2])
    }

    @Test
    public fun returnsNullOnMalformed() {
        assertNull("no colon", RasterPayload.decode("not-a-payload"))
        assertNull("bad dims", RasterPayload.decode("axb:AAAA"))
        assertNull("wrong length", RasterPayload.decode("2x2x3:" + Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))))
        assertNull("bad base64", RasterPayload.decode("2x2x3:@@@@"))
        assertTrue(true)
    }

    @Test
    public fun returnsNullOnOverflowingDimensions() {
        // 65536*65536 wraps a 32-bit Int product to 0, so an EMPTY body would pass a naive
        // `bytes.size != w*h*c` gate and yield a corrupt RasterImage (0 pixels, billions of pixels claimed)
        // the UI then OOMs on. Must be rejected, honoring "returns null on a bad byte count. Never throws."
        assertNull("w*h overflows Int to 0", RasterPayload.decode("65536x65536:"))
        // 46341^2 > Int.MAX_VALUE (46340^2 fits) — the pixel count itself overflows even with C=1.
        assertNull("w*h just over Int.MAX_VALUE", RasterPayload.decode("46341x46341:"))
        // A body whose true W*H*C exceeds Int range can never match an Int-sized byte array → null.
        assertNull("huge RGB dims", RasterPayload.decode("40000x40000x3:AAAA"))
    }
}
