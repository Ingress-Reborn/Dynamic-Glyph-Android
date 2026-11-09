package org.szres.glyphlens.recognition.image

import org.szres.glyphlens.recognition.RgbaFrame
import kotlin.math.ceil

/**
 * Views over the captured frame; only pixels requested by a reader are converted.
 *
 * Android capture supplies opaque, upright RGBA pixels. Alpha is therefore ignored.
 * Luminance uses the explicit full-range BT.601 integer approximation below; it is
 * not claimed to be byte-identical to CoreGraphics' DeviceGray conversion.
 * The owned RGBA bytes must remain unchanged while these views or asynchronous OCR use them.
 */
class ImageFrame private constructor(
    val luminance: LuminanceImage,
    val topBand: FrameTopBand,
    val timestampNanos: Long,
) {
    val timeSeconds: Double
        get() = timestampNanos / 1_000_000_000.0

    companion object {
        /** Validates the external RGBA buffer once without converting the full frame. */
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

            val colorRows = if (fullColor) height else bandRows
            val band = FrameTopBand(
                image = ColorImage.fromRgba(width, colorRows, frame.pixels),
                frameHeight = height,
            )
            return ImageFrame(LuminanceImage.fromRgba(width, height, frame.pixels), band, frame.timestampNanos)
        }
    }
}

/** Convenience entry; keep and reuse the returned value rather than decoding per reader. */
fun RgbaFrame.toImageFrame(layout: GameUILayout = GameUILayout(), fullColor: Boolean = false): ImageFrame =
    ImageFrame.fromRgba(this, layout, fullColor)
