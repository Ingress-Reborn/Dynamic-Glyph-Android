package org.szres.glyphlens.overlay

import org.szres.glyphlens.diagnostics.PowerReadingMode
import org.szres.glyphlens.recognition.image.AnalysisRateController

/** Debug fill for the real analysis gate. Business status colours and strokes never change. */
internal fun GlyphPalette.withReadingModeTint(tint: ReadingModeTint): GlyphPalette =
    tint.backgroundColor?.let { copy(background = it) } ?: this

enum class ReadingModeTint(val backgroundColor: Int?) {
    NONE(null),
    IDLE(0xFF333336.toInt()),
    WAITING(0xFF3D2470.toInt()),
    READING(0xFF0B3D24.toInt()),
    BACKING_OFF(0xFF4A2900.toInt()),
    ;

    companion object {
        fun from(reading: PowerReadingMode?): ReadingModeTint {
            if (reading == null || !reading.powerSaving) return NONE
            val policy = AnalysisRateController.Policy()
            val nominal = if (reading.mode == AnalysisRateController.Mode.ACTIVE) {
                policy.activeInterval
            } else {
                policy.quietInterval
            }
            if (reading.intervalSeconds > nominal + 0.0005) return BACKING_OFF
            return when (reading.mode) {
                AnalysisRateController.Mode.QUIET -> IDLE
                AnalysisRateController.Mode.RESTING -> WAITING
                AnalysisRateController.Mode.ACTIVE -> READING
            }
        }

        fun displayed(enabled: Boolean, reading: PowerReadingMode?): ReadingModeTint =
            if (enabled) from(reading) else NONE
    }
}
