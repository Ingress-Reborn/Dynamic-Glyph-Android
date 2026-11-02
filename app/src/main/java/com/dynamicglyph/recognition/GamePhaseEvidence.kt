package com.dynamicglyph.recognition

/**
 * Game phase evidence and header reading types.
 * Ported from Swift GamePhaseEvidence.swift.
 */

/** Identifies a capture session. */
@JvmInline
value class CaptureSessionID(val value: Long) : Comparable<CaptureSessionID> {
    override fun compareTo(other: CaptureSessionID): Int = value.compareTo(other.value)
}

/** Game phase as determined from header text and indicators. */
enum class GamePhase {
    UNKNOWN,
    COMMAND,
    PRESENTING,
    INPUT,
    COMPLETED
}

/** Where the phase determination came from. */
enum class PhaseSource {
    HEADER_TEXT,
    RETAINED,
    INPUT_TIMER,
    INDICATOR_TRANSITION,
    NONE
}

/**
 * Header reading result from OCR + phrase matching.
 *
 * `sourceTime` is the time of the frame the text was read from, not the time the
 * result arrived: OCR runs behind the sampler, and the tracker's grace period has
 * to be measured from the frame, or a slow read silently extends it. The matcher
 * does not know the frame time, so it leaves this at 0 and the caller sets it.
 */
data class HeaderReading(
    val phase: GamePhase,
    val matchedText: String,
    val confidence: Double,
    val sourceTime: Double = 0.0
) {
    val isKnown: Boolean get() = phase != GamePhase.UNKNOWN
}

/**
 * Shape evidence extracted from a frame observation.
 */
data class ShapeEvidence(
    val kind: ShapeKind,
    val glyph: GlyphID?,
    val edges: Set<GlyphPath.Edge>,
    val extensions: List<GlyphID>
)

enum class ShapeKind {
    NO_STROKE,
    UNIQUE_VISIBLE_SHAPE,
    AMBIGUOUS_SHAPE,
    UNRECOGNIZED_STROKE,
    UNEXPLAINED_INK,
    CALIBRATION_UNAVAILABLE,
    CALIBRATION_MISMATCH
}

/**
 * Complete evidence from a single frame.
 */
data class FrameEvidence(
    val sessionID: CaptureSessionID,
    val time: Double,
    val header: HeaderReading?,
    val slots: SlotStripReader.Reading?,
    val slotRejection: SlotStripReader.Rejection?,
    val shape: ShapeEvidence?,
    val hasValidCalibration: Boolean,
    val hasInputTimerBar: Boolean
)

/**
 * Matches header text against the game's own phrases.
 *
 * Ported from Swift HeaderPhraseMatcher. The matching rules are the original's,
 * including the two that look like over-engineering until you have seen the
 * failure they prevent:
 *
 * - `requiresExactMatch` for "GO!", which is two characters. A substring match
 *   would classify stray map text as the input phase.
 * - Two different phases matching the same header is a contradiction and reads
 *   as `unknown`, rather than picking whichever came first.
 */
class HeaderPhraseMatcher(
    private val phrases: List<Phrase> = ENGLISH_PORTRAIT,
    private val minimumConfidence: Double = 0.35
) {

    data class Phrase(
        val phase: GamePhase,
        /** Normalized words that must all appear, consecutively and in order. */
        val words: List<String>,
        /** The header must consist of exactly these words. */
        val requiresExactMatch: Boolean = false,
        /** Match inside the line's letters, ignoring word boundaries (CJK). */
        val matchesWithin: Boolean = false
    )

    /**
     * The phase named by the best line, or `unknown`.
     */
    fun read(lines: List<Pair<String, Double>>): HeaderReading {
        val hits = mutableListOf<HeaderReading>()

        for ((text, confidence) in lines) {
            if (confidence < minimumConfidence) continue
            val words = normalize(text)
            if (words.isEmpty()) continue
            for (phrase in phrases) {
                if (matches(phrase, words)) {
                    hits.add(
                        HeaderReading(
                            phase = phrase.phase,
                            matchedText = words.joinToString(" "),
                            confidence = confidence
                        )
                    )
                }
            }
        }

        val phases = hits.map { it.phase }.toSet()
        if (phases.size != 1) {
            return HeaderReading(
                phase = GamePhase.UNKNOWN,
                matchedText = if (hits.isEmpty()) "" else "conflicting phrases",
                confidence = 0.0
            )
        }
        return hits.maxByOrNull { it.confidence }!!
    }

    private fun matches(phrase: Phrase, words: List<String>): Boolean {
        if (phrase.requiresExactMatch) return words == phrase.words
        if (phrase.matchesWithin) {
            return words.joinToString("").contains(phrase.words.joinToString(""))
        }
        if (phrase.words.size > words.size) return false
        for (start in 0..(words.size - phrase.words.size)) {
            if (words.subList(start, start + phrase.words.size) == phrase.words) return true
        }
        return false
    }

    companion object {
        /**
         * English portrait phrases, verbatim from GameText.english.
         *
         * "GO!" is the input-phase header; it is two characters, so anything
         * less strict than a whole-header match would fire on map text.
         */
        val ENGLISH_PORTRAIT: List<Phrase> = listOf(
            Phrase(GamePhase.COMMAND, listOf("command", "channel", "open")),
            Phrase(GamePhase.PRESENTING, listOf("incoming", "glyph", "sequence")),
            Phrase(GamePhase.COMPLETED, listOf("glyph", "hack", "complete")),
            Phrase(GamePhase.INPUT, listOf("go"), requiresExactMatch = true)
        )

        /**
         * Lowercase, with everything that is not a letter or digit becoming a
         * single separator. "Command channel open…" and "COMMAND  CHANNEL OPEN"
         * both normalise to `[command, channel, open]`.
         */
        fun normalize(text: String): List<String> {
            val words = mutableListOf<String>()
            val current = StringBuilder()
            for (ch in text.lowercase()) {
                if (ch.isLetterOrDigit()) {
                    current.append(ch)
                } else if (current.isNotEmpty()) {
                    words.add(current.toString())
                    current.clear()
                }
            }
            if (current.isNotEmpty()) words.add(current.toString())
            return words
        }
    }
}
