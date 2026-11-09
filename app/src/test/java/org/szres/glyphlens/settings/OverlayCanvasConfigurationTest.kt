package org.szres.glyphlens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayCanvasConfigurationTest {
    @Test
    fun standardMatchesSwiftCanvasAndSharpnessChangesRealPixelsOnly() {
        val standard = OverlayCanvasConfiguration.standard
        assertEquals(640, standard.width)
        assertEquals(64, standard.height)
        assertEquals(2, standard.resolution)
        assertEquals(1280, standard.pixelWidth)
        assertEquals(128, standard.pixelHeight)
        assertEquals("10:1", standard.ratioLabel())

        val sharper = OverlayCanvasConfiguration(standard.width, standard.height, 3)
        assertEquals(standard.aspectRatio, sharper.aspectRatio, 0.0)
        assertEquals(1920, sharper.pixelWidth)
        assertEquals(192, sharper.pixelHeight)
    }

    @Test
    fun storedValuesAreClampedAndPresetsHaveUniqueRatios() {
        val sanitized = OverlayCanvasConfiguration.sanitized(1, 999, 99)
        assertEquals(256, sanitized.width)
        assertEquals(192, sanitized.height)
        assertEquals(3, sanitized.resolution)
        assertEquals(4, OverlayCanvasConfiguration.presets.map { it.aspectRatio }.toSet().size)
    }

    @Test
    fun windowGeometryUsesRealAspectRatioAndScreenBoundsWithoutChangingForSharpness() {
        val standard = OverlayCanvasConfiguration.standard
        val one = OverlayWindowGeometry.fit(standard, density = 2f, availableWidthPixels = 1_000)
        val three = OverlayWindowGeometry.fit(
            OverlayCanvasConfiguration(standard.width, standard.height, 3),
            density = 2f,
            availableWidthPixels = 1_000,
        )
        assertEquals(one, three)
        assertEquals(720, one.widthPixels)
        assertEquals(72, one.canvasHeightPixels)
        assertEquals(standard.aspectRatio, one.widthPixels.toDouble() / one.canvasHeightPixels, 0.01)

        val tall = OverlayWindowGeometry.fit(
            OverlayCanvasConfiguration(640, 128, 2),
            density = 3f,
            availableWidthPixels = 600,
        )
        assertEquals(600, tall.widthPixels)
        assertEquals(120, tall.canvasHeightPixels)
        assertTrue(tall.widthPixels <= 600)
    }
}
