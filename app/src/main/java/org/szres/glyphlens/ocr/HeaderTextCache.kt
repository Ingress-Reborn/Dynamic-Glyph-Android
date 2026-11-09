package org.szres.glyphlens.ocr

import org.szres.glyphlens.recognition.schedule.HeaderSignature
import org.szres.glyphlens.recognition.text.GameLanguage
import org.szres.glyphlens.recognition.text.OcrTextLine

/** One exact OCR input, confined to the serial OCR worker. Phase and source time are never cached. */
internal class HeaderTextCache {
    private data class Entry(
        val signature: HeaderSignature,
        val language: GameLanguage,
        val lines: List<OcrTextLine>,
    )

    private var entry: Entry? = null

    fun read(
        signature: HeaderSignature?,
        language: GameLanguage,
        recognize: () -> List<OcrTextLine>,
    ): List<OcrTextLine> {
        val previous = entry
        if (signature != null && previous?.signature == signature && previous.language == language) {
            return previous.lines
        }
        val lines = recognize()
        entry = signature?.let { Entry(it, language, lines) }
        return lines
    }
}
