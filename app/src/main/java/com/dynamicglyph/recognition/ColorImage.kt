package com.dynamicglyph.recognition

/**
 * 24-bit RGB image, 3 bytes per pixel, row-major without padding.
 * Ported from Swift ColorImage.swift.
 *
 * The strip reader walks whole rows of this on every analysed frame, so it
 * exposes a packed accessor as well as the tuple one: a `Triple` per pixel is
 * an allocation per pixel.
 */
class ColorImage(
    val width: Int,
    val height: Int,
    val pixels: ByteArray
) {
    init {
        require(pixels.size == width * height * 3) {
            "Pixel count mismatch: expected ${width * height * 3}, got ${pixels.size}"
        }
    }

    /** Returns (red, green, blue) at x, y. Out-of-bounds returns (0,0,0). */
    fun pixel(x: Int, y: Int): Triple<Int, Int, Int> {
        val p = packed(x, y)
        return Triple(red(p), green(p), blue(p))
    }

    /**
     * Packed `0xRRGGBB` at x, y, or 0 outside the image. Allocation-free.
     */
    fun packed(x: Int, y: Int): Int {
        if (x < 0 || x >= width || y < 0 || y >= height) return 0
        val index = (y * width + x) * 3
        val r = pixels[index].toInt() and 0xFF
        val g = pixels[index + 1].toInt() and 0xFF
        val b = pixels[index + 2].toInt() and 0xFF
        return (r shl 16) or (g shl 8) or b
    }

    /**
     * A cropped top band of a frame, retaining the original frame height for
     * GameUILayout ratio calculations.
     */
    data class FrameTopBand(
        val image: ColorImage,
        val frameHeight: Int
    ) {
        init {
            require(frameHeight >= image.height) {
                "A top band cannot be taller than the frame it came from"
            }
        }

        /**
         * Rows of the frame this band actually contains, clamped to it.
         *
         * The ratios are resolved against the frame height, not the band height:
         * the band is a crop, so `slotStripBottom` of 0.176 refers to 176/1000
         * of the whole frame.
         */
        fun rows(top: Double, bottom: Double): IntRange {
            val first = maxOf(0, kotlin.math.floor(top * frameHeight).toInt())
            val last = minOf(image.height, kotlin.math.ceil(bottom * frameHeight).toInt())
            return if (first < last) first until last else IntRange.EMPTY
        }
    }

    companion object {
        fun red(packed: Int): Int = (packed shr 16) and 0xFF
        fun green(packed: Int): Int = (packed shr 8) and 0xFF
        fun blue(packed: Int): Int = packed and 0xFF
    }
}
