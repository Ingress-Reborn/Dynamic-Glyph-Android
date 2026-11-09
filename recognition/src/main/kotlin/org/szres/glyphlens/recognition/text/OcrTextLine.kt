package org.szres.glyphlens.recognition.text

data class NormalizedRect(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)

data class OcrTextElement(
    val text: String,
    val box: NormalizedRect?,
)

data class OcrTextLine(
    val text: String,
    val confidence: Double?,
    val box: NormalizedRect?,
    val elements: List<OcrTextElement> = emptyList(),
)
