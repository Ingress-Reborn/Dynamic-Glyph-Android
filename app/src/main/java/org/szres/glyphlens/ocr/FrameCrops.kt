package org.szres.glyphlens.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import org.szres.glyphlens.recognition.RgbaFrame
import org.szres.glyphlens.recognition.image.GameUILayout
import kotlin.math.ceil
import kotlin.math.floor

data class OcrCrop(val bitmap: Bitmap, val rect: Rect)

object FrameCrops {
    fun header(frame: RgbaFrame, layout: GameUILayout = GameUILayout()): OcrCrop {
        val top = floor(frame.height * layout.headerTextTop).toInt()
        val bottom = ceil(frame.height * layout.headerTextBottom).toInt()
        val left = floor(frame.width * layout.contentLeft).toInt()
        val right = ceil(frame.width * layout.contentRight).toInt()
        return crop(frame, Rect(left, top, right, bottom))
    }

    fun crop(frame: RgbaFrame, rect: Rect, scale: Int = 1): OcrCrop {
        require(
            scale >= 1 && rect.left >= 0 && rect.top >= 0 &&
                rect.right <= frame.width && rect.bottom <= frame.height &&
                rect.width() > 0 && rect.height() > 0
        )
        require(frame.pixels.size == frame.width * frame.height * 4)

        val width = rect.width() * scale
        val height = rect.height() * scale
        val output = IntArray(width * height)
        for (y in 0 until height) {
            val sourceY = rect.top + y / scale
            for (x in 0 until width) {
                val sourceX = rect.left + x / scale
                val offset = (sourceY * frame.width + sourceX) * 4
                output[y * width + x] = rgbaPixelToArgb(
                    frame.pixels[offset],
                    frame.pixels[offset + 1],
                    frame.pixels[offset + 2],
                    frame.pixels[offset + 3],
                )
            }
        }
        return OcrCrop(Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888), rect)
    }
}

internal fun rgbaPixelToArgb(red: Byte, green: Byte, blue: Byte, alpha: Byte): Int =
    (alpha.toInt() and 0xFF shl 24) or
        (red.toInt() and 0xFF shl 16) or
        (green.toInt() and 0xFF shl 8) or
        (blue.toInt() and 0xFF)
