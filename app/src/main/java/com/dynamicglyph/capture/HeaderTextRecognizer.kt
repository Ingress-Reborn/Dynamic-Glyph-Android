package com.dynamicglyph.capture

import android.graphics.Bitmap
import com.dynamicglyph.recognition.GamePhase
import com.dynamicglyph.recognition.GameUILayout
import com.dynamicglyph.recognition.HeaderPhraseMatcher
import com.dynamicglyph.recognition.HeaderReading
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Reads the game's header text with ML Kit.
 *
 * Replaces the iOS Vision-based HeaderTextRecognizer.
 *
 * Only the header band is cropped and scaled, and only the lines ML Kit returns
 * for it are handed to [HeaderPhraseMatcher]; the matcher owns the phrase rules,
 * so this class is deliberately thin. ML Kit has one Latin model and no
 * fast/accurate switch, so the original's two-tier English/CJK strategy
 * collapses to a single recogniser.
 */
class HeaderTextRecognizer(
    private val layout: GameUILayout = GameUILayout.ENGLISH_PORTRAIT,
    private val matcher: HeaderPhraseMatcher = HeaderPhraseMatcher()
) {

    private val recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Read the header from a full frame.
     *
     * Returns a reading with phase [GamePhase.UNKNOWN] when nothing matched,
     * which is information: the tracker's grace period depends on it.
     */
    suspend fun read(frame: Bitmap): HeaderReading {
        val top = (layout.headerTextTop * frame.height).toInt().coerceIn(0, frame.height - 1)
        val bottom = (layout.headerTextBottom * frame.height).toInt().coerceIn(top + 1, frame.height)
        val height = bottom - top

        val cropped = Bitmap.createBitmap(frame, 0, top, frame.width, height)
        val reading = readBand(cropped)
        cropped.recycle()
        return reading
    }

    /**
     * Read a bitmap that is already just the header band.
     *
     * The capture pipeline hands over the band rows directly, so no full-frame
     * Bitmap has to exist on the analysis path.
     */
    suspend fun readBand(band: Bitmap): HeaderReading {
        // 2x helps a small cap-height font; the phrase matcher is exact, so a
        // sharper read is the only thing that improves the outcome.
        val scaled = Bitmap.createScaledBitmap(band, band.width * 2, band.height * 2, true)
        val lines = recognize(scaled)
        if (scaled !== band) scaled.recycle()
        return matcher.read(lines)
    }

    private suspend fun recognize(bitmap: Bitmap): List<Pair<String, Double>> =
        suspendCancellableCoroutine { continuation ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val lines = result.textBlocks.flatMap { block ->
                        block.lines.map { line ->
                            // ML Kit reports a per-line confidence and leaves it
                            // null when it has none. Treat that as usable rather
                            // than as a failure.
                            val confidence = line.confidence ?: 0.9f
                            line.text to confidence.toDouble()
                        }
                    }
                    if (continuation.isActive) continuation.resume(lines)
                }
                .addOnFailureListener {
                    if (continuation.isActive) continuation.resume(emptyList())
                }
        }

    fun close() {
        recognizer.close()
    }
}
