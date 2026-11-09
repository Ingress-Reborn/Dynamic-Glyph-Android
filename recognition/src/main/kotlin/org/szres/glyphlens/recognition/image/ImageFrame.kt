package org.szres.glyphlens.recognition.image

import org.szres.glyphlens.recognition.RgbaFrame
import kotlin.math.ceil

/**
 * The single decoded representation passed through the pixel pipeline.
 *
 * Android capture supplies opaque, upright RGBA pixels. Alpha is therefore ignored.
 * Luminance uses the explicit full-range BT.601 integer approximation below; it is
 * not claimed to be byte-identical to CoreGraphics' DeviceGray conversion.
 */
class ImageFrame private constructor(
    val luminance: LuminanceImage,
    val topBand: FrameTopBand,
    val timestampNanos: Long,
) {
    val timeSeconds: Double
        get() = timestampNanos / 1_000_000_000.0

    companion object {
        /** Validates and converts the external RGBA buffer once at the image entry. */
        @JvmStatic
        fun fromRgba(
            frame: RgbaFrame,
            layout: GameUILayout = GameUILayout(),
            fullColor: Boolean = false,
        ): ImageFrame {
            val width = frame.width
            val height = frame.height
            require(width > 0 && height > 0) { "Frame dimensions must be positive" }
            val count = width.toLong() * height.toLong()
            require(count <= Int.MAX_VALUE && count * 4L == frame.pixels.size.toLong()) {
                "Expected ${count * 4L} RGBA bytes, found ${frame.pixels.size}"
            }
            val bandRows = ceil(layout.topBandBottom * height).toInt()
            require(bandRows in 1..height) { "The layout must leave a nonempty top band" }

            val luminance = ByteArray(count.toInt())
            val colorRows = if (fullColor) height else bandRows
            val color = ByteArray(width * colorRows * 3)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val pixel = y * width + x
                    val source = pixel * 4
                    val red = frame.pixels[source].toInt() and 0xff
                    val green = frame.pixels[source + 1].toInt() and 0xff
                    val blue = frame.pixels[source + 2].toInt() and 0xff
                    // Same channel order and fixed-point weights as LumaGate's RGBA path.
                    luminance[pixel] = ((77 * red + 150 * green + 29 * blue) shr 8).toByte()
                    if (y < colorRows) {
                        val destination = pixel * 3
                        color[destination] = red.toByte()
                        color[destination + 1] = green.toByte()
                        color[destination + 2] = blue.toByte()
                    }
                }
            }
            val band = FrameTopBand(
                image = ColorImage(width, colorRows, color),
                frameHeight = height,
            )
            return ImageFrame(LuminanceImage(width, height, luminance), band, frame.timestampNanos)
        }
    }
}

/** Convenience entry; keep and reuse the returned value rather than decoding per reader. */
fun RgbaFrame.toImageFrame(layout: GameUILayout = GameUILayout(), fullColor: Boolean = false): ImageFrame =
    ImageFrame.fromRgba(this, layout, fullColor)
