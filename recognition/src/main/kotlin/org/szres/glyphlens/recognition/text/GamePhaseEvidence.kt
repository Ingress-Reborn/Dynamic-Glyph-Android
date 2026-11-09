package org.szres.glyphlens.recognition.text

typealias CaptureSessionID = org.szres.glyphlens.recognition.CaptureSessionID

/** The screens of one Glyph hack. Unknown is evidence in its own right, not a phase to guess. */
enum class GamePhase(val rawValue: String) {
    unknown("unknown"),
    command("command"),
    presenting("presenting"),
    input("input"),
    completed("completed"),
}

/** One reading of the header region, stamped with the source frame's time. */
data class HeaderReading(
    val phase: GamePhase,
    val matchedText: String,
    val confidence: Double,
    val sourceTime: Double = 0.0,
    val language: GameLanguage? = null,
) {
    fun at(sourceTime: Double): HeaderReading = copy(sourceTime = sourceTime)
}

/** Framework-independent, exact phrase matching for game headers. */
class HeaderPhraseMatcher(
    val phrases: List<Phrase> = englishPortrait,
    val minimumConfidence: Double = 0.35,
    private val language: GameLanguage = GameLanguage.english,
) {
    constructor(language: GameLanguage) : this(
        phrases = GameText(language).headerPhrases,
        minimumConfidence = language.confidence.header,
        language = language,
    )

    data class Phrase(
        val phase: GamePhase,
        /** Normalized words that must all appear, in order. */
        val words: List<String>,
        /** Short phrases such as GO must occupy the whole header. */
        val requiresExactMatch: Boolean = false,
        /** CJK headers can match inside one uninterrupted run of letters. */
        val matchesWithin: Boolean = false,
        val language: GameLanguage? = null,
    )

    companion object {
        val englishPortrait: List<Phrase>
            get() = GameText(GameLanguage.english).headerPhrases

        /** Lowercase letters and digits, with every other run treated as a word boundary. */
        fun normalize(text: String): List<String> {
            val words = mutableListOf<String>()
            val current = StringBuilder()
            for (character in text.lowercase()) {
                if (character.isLetterOrDigit()) {
                    current.append(character)
                } else if (current.isNotEmpty()) {
                    words += current.toString()
                    current.clear()
                }
            }
            if (current.isNotEmpty()) words += current.toString()
            return words
        }
    }

    /** The one phase named by the best line, or unknown when lines disagree. */
    fun read(lines: List<Pair<String, Double>>): HeaderReading {
        val hits = mutableListOf<HeaderReading>()
        for ((text, confidence) in lines) {
            if (confidence < minimumConfidence) continue
            val words = normalize(text)
            if (words.isEmpty()) continue
            for (phrase in phrases) {
                if (matches(phrase, words)) {
                    hits += HeaderReading(
                        phase = phrase.phase,
                        matchedText = words.joinToString(" "),
                        confidence = confidence,
                        language = phrase.language?.takeIf { it == language },
                    )
                }
            }
        }
        val phases = hits.mapTo(mutableSetOf()) { it.phase }
        val best = if (phases.size == 1) hits.maxByOrNull { it.confidence } else null
        return best ?: HeaderReading(
            phase = GamePhase.unknown,
            matchedText = if (hits.isEmpty()) "" else "conflicting phrases",
            confidence = 0.0,
        )
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
}
