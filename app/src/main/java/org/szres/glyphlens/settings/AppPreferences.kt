package org.szres.glyphlens.settings

import android.content.Context
import org.szres.glyphlens.recognition.text.GameLanguage
import org.szres.glyphlens.recognition.text.GameLanguageMemory

class AppPreferences(context: Context) {
    private val applicationContext = context.applicationContext
    private val values = applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    var recognitionMode: RecognitionMode
        get() {
            val stored = values.getString(RECOGNITION_MODE_KEY, null)
            return RecognitionMode.entries.firstOrNull { it.rawValue == stored } ?: RecognitionMode.REALTIME
        }
        set(value) = values.edit().putString(RECOGNITION_MODE_KEY, value.rawValue).apply()

    var hidesAfterDrawing: Boolean
        get() = values.getBoolean(AUTO_HIDE_KEY, false)
        set(value) = values.edit().putBoolean(AUTO_HIDE_KEY, value).apply()

    var showsFloatingWindow: Boolean
        get() = values.getBoolean("display.floatingWindow", true)
        set(value) = values.edit().putBoolean("display.floatingWindow", value).apply()

    var gameLanguage: GameLanguage?
        get() = values.getString("game.language", null)?.let(GameLanguage::fromRawValue)
        set(value) = values.edit().putString("game.language", value?.rawValue).apply()

    var detectedGameLanguage: GameLanguage?
        get() = values.getString("game.detectedLanguage", null)?.let(GameLanguage::fromRawValue)
        set(value) = values.edit().putString("game.detectedLanguage", value?.rawValue).apply()

    fun makeGameLanguageMemory(): GameLanguageMemory {
        val locales = applicationContext.resources.configuration.locales
        val preferred = (0 until locales.size()).map { locales[it].toLanguageTag() }
        return makeGameLanguageMemory(gameLanguage, detectedGameLanguage, preferred) { found ->
            detectedGameLanguage = found
        }
    }

    var protectsFloatingWindowFromCapture: Boolean
        get() = values.getBoolean("pip.preventsCapture", false)
        set(value) = values.edit().putBoolean("pip.preventsCapture", value).apply()

    var overlayCanvas: OverlayCanvasConfiguration
        get() = OverlayCanvasConfiguration.sanitized(
            width = values.getInt(CANVAS_WIDTH_KEY, OverlayCanvasConfiguration.standard.width),
            height = values.getInt(CANVAS_HEIGHT_KEY, OverlayCanvasConfiguration.standard.height),
            resolution = values.getInt(CANVAS_RESOLUTION_KEY, OverlayCanvasConfiguration.standard.resolution),
        )
        set(value) = values.edit()
            .putInt(CANVAS_WIDTH_KEY, value.width)
            .putInt(CANVAS_HEIGHT_KEY, value.height)
            .putInt(CANVAS_RESOLUTION_KEY, value.resolution)
            .apply()

    var colorsFloatingWindowByMode: Boolean
        get() = values.getBoolean(MODE_COLORS_KEY, false)
        set(value) = values.edit().putBoolean(MODE_COLORS_KEY, value).apply()

    companion object {
        const val PREFERENCES_NAME = "glyph-lens"
        const val RECOGNITION_MODE_KEY = "capture.recognitionMode"
        const val AUTO_HIDE_KEY = "display.hidesAfterDrawing"
        const val MODE_COLORS_KEY = "diagnostics.floatingWindowModeColors"
        const val CANVAS_WIDTH_KEY = "pip.canvas.width"
        const val CANVAS_HEIGHT_KEY = "pip.canvas.height"
        const val CANVAS_RESOLUTION_KEY = "pip.canvas.resolution"

        internal fun makeGameLanguageMemory(
            chosen: GameLanguage?,
            remembered: GameLanguage?,
            preferredLanguages: List<String>,
            onChange: (GameLanguage) -> Unit = {},
        ): GameLanguageMemory = GameLanguageMemory(
            language = chosen ?: GameLanguageMemory.startingGuess(remembered, preferredLanguages),
            isAutomatic = chosen == null,
            onChange = onChange,
        )
    }
}
