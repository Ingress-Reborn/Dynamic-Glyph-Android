package org.szres.glyphlens.recognition.image

import kotlin.math.ceil
import kotlin.math.floor

/** Top-left-origin RGB samples, either packed or read directly from an owned RGBA frame. */
class ColorImage private constructor(
    val width: Int,
    val height: Int,
    private var packedPixels: ByteArray?,
    private val rgbaPixels: ByteArray?,
) {
    constructor(width: Int, height: Int, pixels: ByteArray) : this(width, height, pixels, null) {
        require(width > 0 && height > 0) { "Image dimensions must be positive" }
        val pixelCount = width.toLong() * height.toLong()
        require(pixelCount <= Int.MAX_VALUE && pixelCount * 3L == pixels.size.toLong()) {
            "Expected ${pixelCount * 3L} RGB bytes, found ${pixels.size}"
        }
    }

    /** Explicit packed access materializes an RGBA view; sparse readers use the sample operators. */
    val pixels: ByteArray
        get() {
            packedPixels?.let { return it }
            val result = ByteArray(width * height * 3)
            for (row in 0 until height) copyRgbRow(row, 0, width, result, row * width * 3)
            packedPixels = result
            return result
        }

    internal val hasPackedPixels: Boolean get() = packedPixels != null

    operator fun get(x: Int, y: Int): RgbPixel = this[y * width + x]

    operator fun get(index: Int): RgbPixel {
        val packed = packedPixels
        val source = packed ?: rgbaPixels!!
        val base = index * (if (packed == null) 4 else 3)
        return RgbPixel(
            red = source[base].toInt() and 0xff,
            green = source[base + 1].toInt() and 0xff,
            blue = source[base + 2].toInt() and 0xff,
        )
    }

    internal fun copyRgbRow(y: Int, left: Int, right: Int, destination: ByteArray, offset: Int) {
        packedPixels?.let {
            it.copyInto(destination, offset, (y * width + left) * 3, (y * width + right) * 3)
            return
        }
        val rgba = rgbaPixels!!
        var output = offset
        for (x in left until right) {
            val source = (y * width + x) * 4
            destination[output++] = rgba[source]
            destination[output++] = rgba[source + 1]
            destination[output++] = rgba[source + 2]
        }
    }

    companion object {
        internal fun fromRgba(width: Int, height: Int, pixels: ByteArray): ColorImage =
            ColorImage(width, height, null, pixels)
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
