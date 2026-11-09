package org.szres.glyphlens.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.szres.glyphlens.recognition.CaptureSessionID
import org.szres.glyphlens.recognition.RgbaFrame
import org.szres.glyphlens.recognition.image.ImageFrame
import org.szres.glyphlens.recognition.schedule.GameTextSchedule
import org.szres.glyphlens.recognition.text.GameLanguage
import org.szres.glyphlens.recognition.text.GamePhase

@RunWith(AndroidJUnit4::class)
class HeaderTextInstrumentedTest {
    @Test
    fun exactRepeatedPixelsProduceNewSourceEvidenceAndChangedPixelsReplaceTheReading() {
        val bitmap = Bitmap.createBitmap(603, 1333, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(bitmap.width * bitmap.height)
        try {
            bitmap.eraseColor(Color.BLACK)
            Canvas(bitmap).drawText("INCOMING GLYPH SEQUENCE", 18f, 117f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 32f
            })
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
        val rgba = ByteArray(pixels.size * 4)
        for (index in pixels.indices) {
            rgba[index * 4] = (pixels[index] ushr 16).toByte()
            rgba[index * 4 + 1] = (pixels[index] ushr 8).toByte()
            rgba[index * 4 + 2] = pixels[index].toByte()
            rgba[index * 4 + 3] = (-1).toByte()
        }
        fun work(at: Double, bytes: ByteArray): GameOcrWork {
            val frame = RgbaFrame(603, 1333, (at * 1e9).toLong(), bytes)
            return GameOcrWork(
                request = GameTextSchedule.Request((at * 10).toInt(), GameTextSchedule.Mode.header, at),
                session = CaptureSessionID(1uL),
                frame = frame,
                color = ImageFrame.fromRgba(frame, fullColor = true).topBand.image,
                language = GameLanguage.english,
            )
        }
        MlKitTextReader().use { reader ->
            val operation = MlKitGameOcrOperation(reader)
            val first = requireNotNull(operation.read(work(1.0, rgba)).header)
            val repeated = requireNotNull(operation.read(work(1.2, rgba.copyOf())).header)
            val blank = ByteArray(rgba.size) { if (it % 4 == 3) (-1).toByte() else 0 }
            val changed = requireNotNull(operation.read(work(1.4, blank)).header)

            assertEquals(GamePhase.presenting, first.phase)
            assertEquals(first.phase, repeated.phase)
            assertEquals(1.0, first.sourceTime, 0.0)
            assertEquals(1.2, repeated.sourceTime, 0.0)
            assertEquals(GamePhase.unknown, changed.phase)
            assertEquals(1.4, changed.sourceTime, 0.0)
        }
    }
}
