package org.szres.glyphlens.ocr

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.szres.glyphlens.recognition.RgbaFrame
import org.szres.glyphlens.recognition.image.GameUILayout

@RunWith(AndroidJUnit4::class)
class FrameCropsInstrumentedTest {
    @Test
    fun headerOcrUsesTheSameLocatedViewportAsSlotRecognition() {
        val pixels = ByteArray(100 * 200 * 4)
        for (y in 0 until 200) for (x in 0 until 100) {
            val index = (y * 100 + x) * 4
            pixels[index] = x.toByte()
            pixels[index + 1] = y.toByte()
            pixels[index + 3] = (-1).toByte()
        }
        val frame = RgbaFrame(100, 200, 123L, pixels)
        val layout = GameUILayout(headerTextTop = 0.2, headerTextBottom = 0.3, contentLeft = 0.1, contentRight = 0.9)
        val crop = FrameCrops.header(frame, layout)
        try {
            assertEquals(10, crop.rect.left)
            assertEquals(40, crop.rect.top)
            assertEquals(90, crop.rect.right)
            assertEquals(60, crop.rect.bottom)
            assertEquals(80, crop.bitmap.width)
            assertEquals(20, crop.bitmap.height)
            assertEquals(0xff0a2800.toInt(), crop.bitmap.getPixel(0, 0))
            assertEquals(0xff593b00.toInt(), crop.bitmap.getPixel(79, 19))
        } finally {
            crop.bitmap.recycle()
        }
    }
}
