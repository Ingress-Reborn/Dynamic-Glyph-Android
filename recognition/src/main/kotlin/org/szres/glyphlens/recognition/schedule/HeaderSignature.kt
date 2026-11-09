package org.szres.glyphlens.recognition.schedule

import org.szres.glyphlens.recognition.image.ColorImage
import org.szres.glyphlens.recognition.image.FrameTopBand
import org.szres.glyphlens.recognition.image.GameUILayout
import kotlin.math.ceil
import kotlin.math.floor

/** Exact packed RGB content of the header OCR crop, including its frame identity. */
class HeaderSignature(
    val width: Int,
    val frameHeight: Int,
    val rows: IntRange,
    pixels: ByteArray,
    val columns: IntRange = 0 until width,
) {
    private val content: ByteArray = pixels.copyOf()

    /** A defensive copy; cached baselines cannot be mutated by their caller. */
    val pixels: ByteArray
        get() = content.copyOf()

    override fun equals(other: Any?): Boolean = other is HeaderSignature &&
        width == other.width && frameHeight == other.frameHeight && rows == other.rows && columns == other.columns &&
        content.contentEquals(other.content)

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + frameHeight
        result = 31 * result + rows.hashCode()
        result = 31 * result + columns.hashCode()
        result = 31 * result + content.contentHashCode()
        return result
    }

    companion object {
        /**
         * Uses the same integral content crop as the header recognizer.
         * A missing or incomplete crop returns null, which fails open to OCR.
         */
        fun from(
            image: ColorImage,
            frameHeight: Int = image.height,
            layout: GameUILayout = GameUILayout(),
        ): HeaderSignature? {
            if (frameHeight < image.height || frameHeight <= 0) return null
            val top = layout.headerTextTop * frameHeight
            val bottom = layout.headerTextBottom * frameHeight
            if (!top.isFinite() || !bottom.isFinite() || top < 0 || bottom > frameHeight || bottom <= top) {
                return null
            }
            val first = floor(top).toInt()
            val last = ceil(bottom).toInt()
            if (first < 0 || first >= last || last > image.height) return null
            val left = floor(layout.contentLeft * image.width).toInt()
            val right = ceil(layout.contentRight * image.width).toInt()
            if (left < 0 || right > image.width || left >= right) return null
            val stride = image.width * 3
            val rowBytes = (right - left) * 3
            val pixels = ByteArray(rowBytes * (last - first))
            for (row in first until last) {
                image.pixels.copyInto(pixels, (row - first) * rowBytes, row * stride + left * 3, row * stride + right * 3)
            }
            return HeaderSignature(
                width = image.width,
                frameHeight = frameHeight,
                rows = first until last,
                pixels = pixels,
                columns = left until right,
            )
        }

        fun from(band: FrameTopBand, layout: GameUILayout = GameUILayout()): HeaderSignature? =
            from(band.image, band.frameHeight, layout)
    }
}
