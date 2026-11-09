package org.szres.glyphlens.recognition.text

/** The language Ingress shows, chosen independently of the phone's language. */
enum class GameLanguage(val rawValue: String) {
    english("en"),
    simplifiedChinese("zh-Hans"),
    traditionalChinese("zh-Hant"),
    japanese("ja"),
    korean("ko");

    /** English, Simplified Chinese and Japanese have been checked against recordings. */
    val isRecorded: Boolean
        get() = this == english || this == simplifiedChinese || this == japanese

    /** The game's own OCR language first, with English retained for mixed-language screens. */
    val recognitionLanguages: List<String>
        get() = when (this) {
            english -> listOf("en-US")
            simplifiedChinese -> listOf("zh-Hans", "en-US")
            traditionalChinese -> listOf("zh-Hant", "en-US")
            japanese -> listOf("ja-JP", "en-US")
            korean -> listOf("ko-KR", "en-US")
        }

    val nativeName: String
        get() = when (this) {
            english -> "English"
            simplifiedChinese -> "简体中文"
            traditionalChinese -> "繁體中文"
            japanese -> "日本語"
            korean -> "한국어"
        }

    val needsAccurateRecognition: Boolean
        get() = this != english

    data class Confidence(val header: Double)

    val confidence: Confidence
        get() = Confidence(if (this == english) 0.35 else 0.3)

    companion object {
        fun fromRawValue(rawValue: String): GameLanguage? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

class GameText(val language: GameLanguage = GameLanguage.english) {
    val headerPhrases: List<HeaderPhraseMatcher.Phrase>
        get() = if (language == GameLanguage.english) {
            headers.getValue(language)
        } else {
            headers.getValue(language) + headers.getValue(GameLanguage.english)
        }

    companion object {
        private val headers = mapOf(
            GameLanguage.english to listOf(
                HeaderPhraseMatcher.Phrase(GamePhase.command, listOf("command", "channel", "open")),
                HeaderPhraseMatcher.Phrase(GamePhase.presenting, listOf("incoming", "glyph", "sequence")),
                HeaderPhraseMatcher.Phrase(GamePhase.completed, listOf("glyph", "hack", "complete")),
                HeaderPhraseMatcher.Phrase(GamePhase.input, listOf("go"), requiresExactMatch = true),
            ),
            GameLanguage.simplifiedChinese to listOf(
                HeaderPhraseMatcher.Phrase(GamePhase.command, listOf("命令频道"), matchesWithin = true),
                HeaderPhraseMatcher.Phrase(GamePhase.presenting, listOf("接收符文序列"), matchesWithin = true),
                HeaderPhraseMatcher.Phrase(GamePhase.completed, listOf("符文入侵完成"), matchesWithin = true),
                HeaderPhraseMatcher.Phrase(GamePhase.input, listOf("开始"), requiresExactMatch = true),
            ),
            GameLanguage.traditionalChinese to listOf(
                HeaderPhraseMatcher.Phrase(GamePhase.command, listOf("指令頻道"), matchesWithin = true),
                HeaderPhraseMatcher.Phrase(GamePhase.presenting, listOf("接收glyph序列"), matchesWithin = true),
                HeaderPhraseMatcher.Phrase(GamePhase.completed, listOf("符文入侵完成"), matchesWithin = true),
            ),
            GameLanguage.japanese to listOf(
                HeaderPhraseMatcher.Phrase(GamePhase.command, listOf("コマンドチャンネル"), matchesWithin = true),
                HeaderPhraseMatcher.Phrase(GamePhase.presenting, listOf("グリフを受信中"), matchesWithin = true),
                HeaderPhraseMatcher.Phrase(GamePhase.completed, listOf("ハック完了"), matchesWithin = true),
            ),
            GameLanguage.korean to listOf(
                HeaderPhraseMatcher.Phrase(GamePhase.command, listOf("명령채널"), matchesWithin = true),
                HeaderPhraseMatcher.Phrase(GamePhase.presenting, listOf("글리프시퀀스"), matchesWithin = true),
                HeaderPhraseMatcher.Phrase(GamePhase.completed, listOf("해킹완료"), matchesWithin = true),
            ),
        ).mapValues { (language, phrases) ->
            phrases.map { phrase ->
                // GO is shared; the completion phrase is identical in Simplified and Traditional Chinese.
                val identifiesLanguage = phrase.phase != GamePhase.input && phrase.words != listOf("符文入侵完成")
                if (identifiesLanguage) phrase.copy(language = language) else phrase
            }
        }
    }
}
