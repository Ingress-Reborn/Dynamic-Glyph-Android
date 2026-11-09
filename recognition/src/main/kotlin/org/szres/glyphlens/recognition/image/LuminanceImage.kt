package org.szres.glyphlens.recognition.image

import org.szres.glyphlens.recognition.GlyphPoint
import kotlin.math.ceil
import kotlin.math.floor

/** A packed, top-left-origin, 8-bit luminance image. */
class LuminanceImage(
    val width: Int,
    val height: Int,
    val pixels: ByteArray,
) {
    init {
        require(width > 0 && height > 0) { "Image dimensions must be positive" }
        val count = width.toLong() * height.toLong()
        require(count == pixels.size.toLong()) { "Expected $count luminance bytes, found ${pixels.size}" }
    }

    /** Returns an unsigned sample in 0...255. */
    operator fun get(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xff

    /** Nearest-pixel sampling; out-of-frame points have no evidence. */
    fun sample(point: GlyphPoint): Double {
        // Swift's rounded() is nearest with ties away from zero.
        val x = roundedAwayFromZero(point.x).toInt()
        val y = roundedAwayFromZero(point.y).toInt()
        if (x < 0 || y < 0 || x >= width || y >= height) return 0.0
        return this[x, y].toDouble()
    }

    companion object {
        internal fun roundedAwayFromZero(value: Double): Double =
            if (value < 0.0) ceil(value - 0.5) else floor(value + 0.5)
    }
}
