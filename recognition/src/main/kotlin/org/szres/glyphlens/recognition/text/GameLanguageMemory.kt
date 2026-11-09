package org.szres.glyphlens.recognition.text

/** Automatic language probes never change the saved language until a header identifies it. */
class GameLanguageMemory(
    language: GameLanguage,
    val isAutomatic: Boolean,
    val retryInterval: Double = 1.0,
    private val onChange: (GameLanguage) -> Unit = {},
) {
    data class State(val language: GameLanguage, val isConfirmed: Boolean)

    private val lock = Any()
    private var language = language
    private var probeLanguage = language
    private var lastProbe: Double? = null
    private var confirmed = false

    val current: GameLanguage
        get() = synchronized(lock) { language }

    val isConfirmed: Boolean
        get() = synchronized(lock) { confirmed }

    val state: State
        get() = synchronized(lock) { State(language, confirmed) }

    /** One model per admitted frame, with the same retry budget across all languages. */
    fun headerLanguage(at: Double): GameLanguage = synchronized(lock) {
        if (!isAutomatic || confirmed) return@synchronized language
        val previous = lastProbe
        if (previous == null || at < previous) {
            probeLanguage = language
            lastProbe = at
        } else if (at - previous >= retryInterval) {
            probeLanguage = GameLanguage.entries[(probeLanguage.ordinal + 1) % GameLanguage.entries.size]
            lastProbe = at
        }
        probeLanguage
    }

    fun found(found: GameLanguage) {
        val changed = synchronized(lock) {
            val changed = isAutomatic && found != language
            if (changed) language = found
            if (isAutomatic) confirmed = true
            changed
        }
        if (changed) onChange(found)
    }

    companion object {
        /** Last found language, then the first matching preferred language, else English. */
        fun startingGuess(remembered: GameLanguage?, preferredLanguages: List<String>): GameLanguage {
            remembered?.let { return it }
            for (identifier in preferredLanguages) {
                val code = identifier.lowercase()
                if (code.startsWith("zh-hant") || code.startsWith("zh-tw") || code.startsWith("zh-hk")) {
                    return GameLanguage.traditionalChinese
                }
                if (code.startsWith("zh")) return GameLanguage.simplifiedChinese
                if (code.startsWith("ja")) return GameLanguage.japanese
                if (code.startsWith("ko")) return GameLanguage.korean
                if (code.startsWith("en")) return GameLanguage.english
            }
            return GameLanguage.english
        }
    }
}
