package org.szres.glyphlens.settings

import android.content.Context

class AppPreferences(context: Context) {
    private val values = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    var hidesAfterDrawing: Boolean
        get() = values.getBoolean(AUTO_HIDE_KEY, true)
        set(value) = values.edit().putBoolean(AUTO_HIDE_KEY, value).apply()

    var showsFloatingWindow: Boolean
        get() = values.getBoolean("display.floatingWindow", true)
        set(value) = values.edit().putBoolean("display.floatingWindow", value).apply()

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
        const val AUTO_HIDE_KEY = "display.hidesAfterDrawing"
        const val MODE_COLORS_KEY = "diagnostics.floatingWindowModeColors"
        const val CANVAS_WIDTH_KEY = "pip.canvas.width"
        const val CANVAS_HEIGHT_KEY = "pip.canvas.height"
        const val CANVAS_RESOLUTION_KEY = "pip.canvas.resolution"
    }
}
