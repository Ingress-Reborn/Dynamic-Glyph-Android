package com.dynamicglyph.capture

import android.graphics.Bitmap
import com.dynamicglyph.recognition.ColorImage
import com.dynamicglyph.recognition.GameUILayout
import com.dynamicglyph.recognition.LumaGate
import com.dynamicglyph.recognition.LuminanceImage
import java.nio.ByteBuffer
import kotlin.math.ceil

/**
 * Converts Android pixel buffers into the recognition engine's formats.
 *
 * Replaces iOS ImageDecoder.swift (CGImage -> LuminanceImage / ColorImage).
 *
 * Everything is derived from one packed-ARGB `IntArray` per frame, so a frame is
 * only unpacked once. The engine never needs a `Bitmap`; only the OCR does, and
 * that crops just the header band.
 */
object ImageDecoder {

    /**
     * Unpack an ImageReader plane into packed ARGB.
     *
     * The reader is configured with `PixelFormat.RGBA_8888`, so the bytes are
     * R, G, B, A in memory and the alpha byte can be ignored.
     */
    fun argbFromPlane(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        pixelStride: Int,
        rowStride: Int
    ): IntArray {
        val out = IntArray(width * height)

        if (pixelStride == 4 && rowStride == width * 4) {
            // Contiguous: one pass, no row copies.
            buffer.rewind()
            var index = 0
            while (index < out.size) {
                val r = buffer.get().toInt() and 0xFF
                val g = buffer.get().toInt() and 0xFF
                val b = buffer.get().toInt() and 0xFF
                buffer.get() // alpha
                out[index] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                index++
            }
            return out
        }

        // Padded rows: copy each row, then stride through it.
        val row = ByteArray(rowStride)
        for (y in 0 until height) {
            buffer.position(y * rowStride)
            val available = minOf(rowStride, buffer.remaining())
            buffer.get(row, 0, available)
            val rowBase = y * width
            for (x in 0 until width) {
                val offset = x * pixelStride
                if (offset + 2 >= available) break
                val r = row[offset].toInt() and 0xFF
                val g = row[offset + 1].toInt() and 0xFF
                val b = row[offset + 2].toInt() and 0xFF
                out[rowBase + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }

    /**
     * Greyscale image for the node detector and edge sampler.
     *
     * Uses the same BT.601 luminance as the luma gate, so the gate's thresholds
     * and the detector's 160 threshold refer to the same scale. Unlike the
     * original there is no video-range stretch: Android delivers full-range
     * pixels. See [LumaGate.lumaOfArgb].
     */
    fun luminanceFromArgb(argb: IntArray, width: Int, height: Int): LuminanceImage {
        require(argb.size >= width * height) {
            "Expected ${width * height} pixels, got ${argb.size}"
        }
        val pixels = ByteArray(width * height)
        for (index in 0 until width * height) {
            pixels[index] = LumaGate.lumaOfArgb(argb[index]).toByte()
        }
        return LuminanceImage(width, height, pixels)
    }

    /**
     * The top band, in colour, for the slot strip and countdown bar.
     *
     * Only the rows the layout actually reads are converted: the strip is
     * near the top of the frame, and colour costs three bytes a pixel.
     */
    fun topBandFromArgb(
        argb: IntArray,
        width: Int,
        height: Int,
        layout: GameUILayout = GameUILayout.ENGLISH_PORTRAIT
    ): ColorImage.FrameTopBand {
        val bandHeight = ceil(layout.topBandBottom * height).toInt().coerceIn(1, height)
        val pixels = ByteArray(width * bandHeight * 3)
        for (y in 0 until bandHeight) {
            val rowBase = y * width
            for (x in 0 until width) {
                val pixel = argb[rowBase + x]
                val out = (rowBase + x) * 3
                pixels[out] = ((pixel shr 16) and 0xFF).toByte()
                pixels[out + 1] = ((pixel shr 8) and 0xFF).toByte()
                pixels[out + 2] = (pixel and 0xFF).toByte()
            }
        }
        return ColorImage.FrameTopBand(
            image = ColorImage(width, bandHeight, pixels),
            frameHeight = height
        )
    }

    /** A full-frame Bitmap, for the OCR crop. */
    fun bitmapFromArgb(argb: IntArray, width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)

    /** A Bitmap of one row range, for the OCR crop. */
    fun bitmapRows(
        argb: IntArray,
        width: Int,
        height: Int,
        firstRow: Int,
        rowCount: Int
    ): Bitmap {
        val top = firstRow.coerceIn(0, height - 1)
        val count = rowCount.coerceIn(1, height - top)
        val slice = IntArray(width * count)
        System.arraycopy(argb, top * width, slice, 0, width * count)
        return Bitmap.createBitmap(slice, width, count, Bitmap.Config.ARGB_8888)
    }
}
