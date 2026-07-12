package io.dotnetnativeinterop.lab

import java.util.Base64

/**
 * A decoded raster frame: ARGB_8888 pixels, row-major. Pure Kotlin (no android.graphics.Bitmap in the
 * decode path) so it is JVM-unit-testable; the UI layer turns this into an ImageBitmap.
 */
public data class RasterImage(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is RasterImage && width == other.width && height == other.height && pixels.contentEquals(other.pixels))

    override fun hashCode(): Int = (width * 31 + height) * 31 + pixels.contentHashCode()
}

/**
 * Decodes the engine's visual payload `"WxHxC:base64"` (C=1 grayscale or C=3 RGB; legacy `"WxH:base64"`
 * is treated as C=1) into ARGB pixels. Returns null on a malformed header, bad base64, or a byte count
 * that does not equal W*H*C. Never throws.
 */
public object RasterPayload {
    public fun decode(payload: String): RasterImage? {
        val colon = payload.indexOf(':')
        if (colon <= 0) return null
        val axes = payload.substring(0, colon).split('x')
        if (axes.size < 2) return null
        val w = axes[0].toIntOrNull() ?: return null
        val h = axes[1].toIntOrNull() ?: return null
        val c = if (axes.size >= 3) (axes[2].toIntOrNull() ?: return null) else 1
        if (w <= 0 || h <= 0 || (c != 1 && c != 3)) return null

        val bytes = try {
            Base64.getDecoder().decode(payload.substring(colon + 1))
        } catch (_: IllegalArgumentException) {
            return null
        }
        // W*H*C computed in Long: an Int product silently wraps (e.g. "65536x65536" overflows w*h to 0),
        // which would sneak a 0-byte body past this length gate and return a structurally-corrupt image
        // (pixels.size = 0 while width*height claims billions) that the UI then OOMs building a bitmap
        // from. Bound W*H to Int so IntArray(w*h) and the pixel loop below stay in range.
        val pixelCount = w.toLong() * h.toLong()
        if (pixelCount > Int.MAX_VALUE) return null
        if (bytes.size.toLong() != pixelCount * c.toLong()) return null

        val pixels = IntArray(w * h)
        if (c == 1) {
            for (i in pixels.indices) {
                val v = bytes[i].toInt() and 0xFF
                pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        } else {
            var b = 0
            for (i in pixels.indices) {
                val r = bytes[b].toInt() and 0xFF
                val g = bytes[b + 1].toInt() and 0xFF
                val bl = bytes[b + 2].toInt() and 0xFF
                pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
                b += 3
            }
        }
        return RasterImage(w, h, pixels)
    }
}
