package org.szres.glyphlens.recognition.image

import org.szres.glyphlens.recognition.GlyphPoint
import kotlin.math.ceil
import kotlin.math.floor

/** Top-left-origin luminance samples, either packed or read directly from an owned RGBA frame. */
class LuminanceImage private constructor(
    val width: Int,
    val height: Int,
    private var packedPixels: ByteArray?,
    private val rgbaPixels: ByteArray?,
) {
    constructor(width: Int, height: Int, pixels: ByteArray) : this(width, height, pixels, null) {
        require(width > 0 && height > 0) { "Image dimensions must be positive" }
        val count = width.toLong() * height.toLong()
        require(count == pixels.size.toLong()) { "Expected $count luminance bytes, found ${pixels.size}" }
    }

    /** Explicit packed access materializes an RGBA view; sparse readers use the sample operators. */
    val pixels: ByteArray
        get() {
            packedPixels?.let { return it }
            return ByteArray(width * height) { this[it].toByte() }.also { packedPixels = it }
        }

    internal val hasPackedPixels: Boolean get() = packedPixels != null

    /** Returns an unsigned sample in 0...255. */
    operator fun get(x: Int, y: Int): Int = this[y * width + x]

    operator fun get(index: Int): Int {
        packedPixels?.let { return it[index].toInt() and 0xff }
        val rgba = rgbaPixels!!
        val base = index * 4
        val red = rgba[base].toInt() and 0xff
        val green = rgba[base + 1].toInt() and 0xff
        val blue = rgba[base + 2].toInt() and 0xff
        return (77 * red + 150 * green + 29 * blue) shr 8
    }

    /** Nearest-pixel sampling; out-of-frame points have no evidence. */
    fun sample(point: GlyphPoint): Double {
        // Swift's rounded() is nearest with ties away from zero.
        val x = roundedAwayFromZero(point.x).toInt()
        val y = roundedAwayFromZero(point.y).toInt()
        if (x < 0 || y < 0 || x >= width || y >= height) return 0.0
        return this[x, y].toDouble()
    }

    companion object {
        internal fun fromRgba(width: Int, height: Int, pixels: ByteArray): LuminanceImage =
            LuminanceImage(width, height, null, pixels)

        internal fun roundedAwayFromZero(value: Double): Double =
            if (value < 0.0) ceil(value - 0.5) else floor(value + 0.5)
    }
}
