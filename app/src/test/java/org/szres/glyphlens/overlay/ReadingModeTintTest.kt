package org.szres.glyphlens.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.szres.glyphlens.diagnostics.PowerReadingMode
import org.szres.glyphlens.recognition.image.AnalysisRateController

class ReadingModeTintTest {
    @Test
    fun preferenceOffAlwaysKeepsOriginalRendererBackground() {
        val active = reading(AnalysisRateController.Mode.ACTIVE)
        assertEquals(ReadingModeTint.NONE, ReadingModeTint.displayed(false, active))
        assertNull(ReadingModeTint.displayed(false, active).backgroundColor)
        assertEquals(ReadingModeTint.NONE, ReadingModeTint.displayed(true, null))
    }

    @Test
    fun realGateModesUseReferenceFillsAndPowerBackoff() {
        assertEquals(
            ReadingModeTint.IDLE,
            ReadingModeTint.displayed(true, reading(AnalysisRateController.Mode.QUIET)),
        )
        assertEquals(
            ReadingModeTint.WAITING,
            ReadingModeTint.displayed(true, reading(AnalysisRateController.Mode.RESTING)),
        )
        assertEquals(
            ReadingModeTint.READING,
            ReadingModeTint.displayed(true, reading(AnalysisRateController.Mode.ACTIVE)),
        )
        assertEquals(
            ReadingModeTint.BACKING_OFF,
            ReadingModeTint.displayed(
                true,
                reading(AnalysisRateController.Mode.ACTIVE).copy(intervalSeconds = 0.098),
            ),
        )
        assertEquals(0xFF333336.toInt(), ReadingModeTint.IDLE.backgroundColor)
        assertEquals(0xFF3D2470.toInt(), ReadingModeTint.WAITING.backgroundColor)
        assertEquals(0xFF0B3D24.toInt(), ReadingModeTint.READING.backgroundColor)
        assertEquals(0xFF4A2900.toInt(), ReadingModeTint.BACKING_OFF.backgroundColor)
    }

    @Test
    fun paletteChangesOnlyInteriorBackground() {
        val original = GlyphPalette()
        val tinted = original.withReadingModeTint(ReadingModeTint.READING)
        assertEquals(ReadingModeTint.READING.backgroundColor, tinted.background)
        assertEquals(original.confirmed, tinted.confirmed)
        assertEquals(original.observing, tinted.observing)
        assertEquals(original.predicted, tinted.predicted)
        assertEquals(original.warning, tinted.warning)
        assertEquals(original.primaryText, tinted.primaryText)
        assertEquals(original.frame, tinted.frame)
        assertEquals(original, original.withReadingModeTint(ReadingModeTint.NONE))
    }

    @Test
    fun disabledPowerSavingHasNoTintEvenWithAnActiveMode() {
        assertEquals(
            ReadingModeTint.NONE,
            ReadingModeTint.from(reading(AnalysisRateController.Mode.ACTIVE).copy(powerSaving = false)),
        )
    }

    private fun reading(mode: AnalysisRateController.Mode) = PowerReadingMode(
        mode = mode,
        intervalSeconds = if (mode == AnalysisRateController.Mode.ACTIVE) 0.049 else 0.249,
        powerSaving = true,
        inHack = false,
    )
}
