package org.szres.glyphlens.ocr

import org.szres.glyphlens.recognition.RgbaFrame
import org.szres.glyphlens.recognition.image.ColorImage
import org.szres.glyphlens.recognition.image.GameUILayout
import org.szres.glyphlens.recognition.schedule.GameTextSchedule
import org.szres.glyphlens.recognition.schedule.HeaderSignature
import org.szres.glyphlens.recognition.text.CaptureSessionID
import org.szres.glyphlens.recognition.text.GameLanguage
import org.szres.glyphlens.recognition.text.HeaderPhraseMatcher
import org.szres.glyphlens.recognition.text.HeaderReading
import org.szres.glyphlens.recognition.text.OcrTextLine

internal data class GameOcrWork(
    val request: GameTextSchedule.Request,
    val session: CaptureSessionID,
    val frame: RgbaFrame,
    val color: ColorImage,
    val language: GameLanguage,
    val layout: GameUILayout = GameUILayout(),
)

internal data class GameOcrOutcome(val header: HeaderReading? = null)

internal fun interface GameOcrOperation {
    fun read(work: GameOcrWork): GameOcrOutcome
}

internal class MlKitGameOcrOperation(
    private val reader: MlKitTextReader,
    private val headerLineObserver: ((GameTextSchedule.Request, List<OcrTextLine>) -> Unit)? = null,
) : GameOcrOperation {
    private val headerText = HeaderTextCache()

    override fun read(work: GameOcrWork): GameOcrOutcome {
        val lines = headerText.read(
            HeaderSignature.from(work.color, layout = work.layout),
            work.language,
        ) {
            val crop = FrameCrops.header(work.frame, work.layout)
            try {
                reader.readSync(
                    bitmap = crop.bitmap,
                    crop = crop.rect,
                    fullFrameWidth = work.frame.width,
                    fullFrameHeight = work.frame.height,
                    language = work.language,
                )
            } finally {
                crop.bitmap.recycle()
            }
        }
        headerLineObserver?.invoke(work.request, lines)
        val header = HeaderPhraseMatcher(work.language)
            .read(lines.map { it.text to (it.confidence ?: 0.0) })
            .at(work.request.sourceTime)
        return GameOcrOutcome(header)
    }
}
