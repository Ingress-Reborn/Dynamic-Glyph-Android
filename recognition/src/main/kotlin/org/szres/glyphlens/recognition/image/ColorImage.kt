package org.szres.glyphlens.recognition.image

import kotlin.math.ceil
import kotlin.math.floor

/** A packed, top-left-origin, 8-bit RGB image with no row padding. */
class ColorImage(
    val width: Int,
    val height: Int,
    /** Three bytes per pixel, red first. Validated once at this boundary. */
    val pixels: ByteArray,
) {
    init {
        require(width > 0 && height > 0) { "Image dimensions must be positive" }
        val pixelCount = width.toLong() * height.toLong()
        require(pixelCount <= Int.MAX_VALUE && pixelCount * 3L == pixels.size.toLong()) {
            "Expected ${pixelCount * 3L} RGB bytes, found ${pixels.size}"
        }
    }

    operator fun get(x: Int, y: Int): RgbPixel {
        val base = (y * width + x) * 3
        return RgbPixel(
            red = pixels[base].toInt() and 0xff,
            green = pixels[base + 1].toInt() and 0xff,
            blue = pixels[base + 2].toInt() and 0xff,
        )
    }
}

@JvmInline
value class RgbPixel private constructor(private val packed: Int) {
    constructor(red: Int, green: Int, blue: Int) : this((red shl 16) or (green shl 8) or blue)

    val red: Int get() = (packed ushr 16) and 0xff
    val green: Int get() = (packed ushr 8) and 0xff
    val blue: Int get() = packed and 0xff

    operator fun component1(): Int = red
    operator fun component2(): Int = green
    operator fun component3(): Int = blue
}

/**
 * The top strip of a delivered frame, starting at its first row. The backing
 * [image] may contain the whole frame when other readers also need its colour.
 *
 * [frameHeight] remains the height of the original frame, not this crop, so all
 * [GameUILayout] fractions are resolved in the same coordinate space.
 */
class FrameTopBand(
    val image: ColorImage,
    val frameHeight: Int,
) {
    init {
        require(frameHeight >= image.height) { "A top band cannot be taller than its source frame" }
    }

    /** Rows of the frame this strip contains, clamped to it; the end is exclusive. */
    fun rows(top: Double, bottom: Double): IntRange {
        val first = maxOf(0, floor(top * frameHeight).toInt())
        val last = minOf(image.height, ceil(bottom * frameHeight).toInt())
        return if (first < last) first until last else IntRange.EMPTY
    }
}
