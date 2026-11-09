package org.szres.glyphlens.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.szres.glyphlens.recognition.text.GameLanguage
import org.szres.glyphlens.recognition.text.NormalizedRect
import org.szres.glyphlens.recognition.text.OcrTextElement
import org.szres.glyphlens.recognition.text.OcrTextLine

/** Bundled ML Kit model families; both Chinese game languages use the same Chinese model. */
internal enum class MlKitScript { latin, chinese, japanese, korean }

internal fun GameLanguage.mlKitScript(): MlKitScript = when (this) {
    GameLanguage.english -> MlKitScript.latin
    GameLanguage.simplifiedChinese, GameLanguage.traditionalChinese -> MlKitScript.chinese
    GameLanguage.japanese -> MlKitScript.japanese
    GameLanguage.korean -> MlKitScript.korean
}

class MlKitTextReader : AutoCloseable {
    private val recognizers = mutableMapOf<MlKitScript, TextRecognizer>()

    fun read(
        bitmap: Bitmap,
        crop: Rect,
        fullFrameWidth: Int,
        fullFrameHeight: Int,
        language: GameLanguage = GameLanguage.english,
    ): Task<List<OcrTextLine>> {
        require(fullFrameWidth > 0 && fullFrameHeight > 0 && crop.width() > 0 && crop.height() > 0)
        val scaleX = bitmap.width.toDouble() / crop.width()
        val scaleY = bitmap.height.toDouble() / crop.height()
        val input = InputImage.fromBitmap(bitmap, 0)
        return recognizerFor(language).process(input).continueWith { task ->
            task.result.textBlocks.flatMap { block ->
                block.lines.map { line ->
                    val box = normalize(line.boundingBox, crop, scaleX, scaleY, fullFrameWidth, fullFrameHeight)
                    val elements = line.elements.map { element ->
                        OcrTextElement(
                            element.text,
                            normalize(element.boundingBox, crop, scaleX, scaleY, fullFrameWidth, fullFrameHeight),
                        )
                    }
                    OcrTextLine(line.text, line.confidence.toDouble(), box, elements)
                }
            }
        }
    }

    fun readSync(
        bitmap: Bitmap,
        crop: Rect,
        fullFrameWidth: Int,
        fullFrameHeight: Int,
        language: GameLanguage,
    ): List<OcrTextLine> = Tasks.await(read(bitmap, crop, fullFrameWidth, fullFrameHeight, language))

    private fun recognizerFor(language: GameLanguage): TextRecognizer = recognizers.getOrPut(language.mlKitScript()) {
        when (language.mlKitScript()) {
            MlKitScript.latin -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            MlKitScript.chinese -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            MlKitScript.japanese -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            MlKitScript.korean -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        }
    }

    private fun normalize(
        bounds: Rect?,
        crop: Rect,
        scaleX: Double,
        scaleY: Double,
        fullFrameWidth: Int,
        fullFrameHeight: Int,
    ): NormalizedRect? {
        if (bounds == null) return null
        return normalizeOcrBounds(
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom,
            cropLeft = crop.left,
            cropTop = crop.top,
            scaleX = scaleX,
            scaleY = scaleY,
            fullFrameWidth = fullFrameWidth,
            fullFrameHeight = fullFrameHeight,
        )
    }

    override fun close() {
        recognizers.values.forEach(TextRecognizer::close)
        recognizers.clear()
    }
}

internal fun normalizeOcrBounds(
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    cropLeft: Int,
    cropTop: Int,
    scaleX: Double,
    scaleY: Double,
    fullFrameWidth: Int,
    fullFrameHeight: Int,
): NormalizedRect = NormalizedRect(
    x = (cropLeft + left / scaleX) / fullFrameWidth,
    y = (cropTop + top / scaleY) / fullFrameHeight,
    width = (right - left) / scaleX / fullFrameWidth,
    height = (bottom - top) / scaleY / fullFrameHeight,
)
