package org.szres.glyphlens.recognition.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotStripReaderTest {
    private val reader = SlotStripReader()

    @Test
    fun syntheticBannerLeavesFourIndicatorsReadable() {
        val reading = reader.read(SyntheticSlotBands.banner(175)).getOrThrow()
        assertEquals(4, reading.slotCount)
        assertNull(reading.highlightedIndex)
        assertTrue(reading.isPartlyCovered)
    }

    @Test
    fun bannerRetainsHighlightEvidenceBelowEdge() {
        val reading = reader.read(SyntheticSlotBands.banner(175, highlighted = 2)).getOrThrow()
        assertEquals(4, reading.slotCount)
        assertEquals(2, reading.highlightedIndex)
        assertTrue(reading.isPartlyCovered)
    }

    @Test
    fun bannerLeavingTooLittleOrCoveringEverythingIsRejected() {
        for (bottom in listOf(205, 226, 240)) {
            assertTrue(reader.read(SyntheticSlotBands.banner(bottom)).isFailure)
        }
    }

    @Test
    fun partialWidthCoverIsNotTreatedAsBanner() {
        assertTrue(reader.read(SyntheticSlotBands.banner(175, left = 0, right = 300)).isFailure)
    }

    @Test
    fun windowLowerEdgeAcrossStripIsReportedAsOverlay() {
        assertEquals(
            SlotStripRejection.CoveredByOverlay,
            reader.read(SyntheticSlotBands.windowOverStrip()).exceptionOrNull(),
        )
    }

    @Test
    fun windowBorderOnlyTouchingTopLeavesFullRowReadable() {
        val reading = reader.read(SyntheticSlotBands.windowAboveStrip()).getOrThrow()
        assertEquals(4, reading.slotCount)
        assertFalse(reading.isPartlyCovered)
    }

    @Test
    fun oneGlyphIndicatorMustBeAtScreenCenter() {
        val centered = reader.read(SyntheticSlotBands.singleMarker(301)).getOrThrow()
        assertEquals(1, centered.slotCount)
        assertEquals(0.5, centered.markerCentersX.single(), 0.02)
        assertEquals(
            SlotStripRejection.IncompleteStrip,
            reader.read(SyntheticSlotBands.singleMarker(155)).exceptionOrNull(),
        )
    }

    @Test
    fun unevenColoredClutterIsRejectedByGeometry() {
        val band = SyntheticSlotBands.outlinedBoxes(lefts = listOf(40, 170, 440)).band()
        assertEquals(SlotStripRejection.UnevenSpacing, reader.read(band).exceptionOrNull())
    }

    @Test
    fun multipleHighlightsAreNotGuessedAt() {
        val canvas = SyntheticSlotBands.outlinedBoxes(highlighted = 0)
        // Add a second independently drawn gold outline to the selected first one.
        val goldCanvas = SyntheticSlotBands.outlinedBoxes(highlighted = 1)
        for (index in 213 until 293) {
            // The full hand-drawn second marker is copied, not a recognizer-selected sample.
            for (y in 145 until 225) {
                val offset = (y * SyntheticSlotBands.WIDTH + index) * 3
                repeat(3) { canvas.pixels[offset + it] = goldCanvas.pixels[offset + it] }
            }
        }
        assertEquals(SlotStripRejection.MultipleHighlights, reader.read(canvas.band()).exceptionOrNull())
    }

    @Test
    fun countdownTrackAcceptsGoldAndLateMagentaProgress() {
        for (magenta in listOf(false, true)) {
            val reading = reader.read(SyntheticSlotBands.timer(magenta)).getOrThrow()
            assertEquals(4, reading.slotCount)
            assertEquals(0, reading.highlightedIndex)
            assertTrue(reading.hasInputTimerBar)
        }
    }

    @Test
    fun countdownSurvivesCoveredIndicatorStripButDoesNotInventSlots() {
        val band = SyntheticSlotBands.timer(markers = false)
        assertTrue(reader.read(band).isFailure)
        assertTrue(reader.hasTimerBar(band))
    }

    @Test
    fun plainPresentationHasNoCountdownEvidence() {
        val band = SyntheticSlotBands.outlinedBoxes().band()
        assertFalse(reader.read(band).getOrThrow().hasInputTimerBar)
        assertFalse(reader.hasTimerBar(band))
    }

    @Test
    fun measuredLayoutKeepsHeaderStripTimerAndArrayApart() {
        val layout = GameUILayout()
        assertTrue(layout.headerTextBottom <= layout.slotStripTop)
        assertTrue(layout.slotStripBottom <= layout.inputTimerBarTop)
        val fit = requireNotNull(NodeArrayDetector().detect(SyntheticRaster().image()))
        assertTrue(layout.isConsistent(fit))
        assertTrue(fit.origin.y > layout.topBandBottom * fit.imageHeight)
    }

    @Test
    fun topBandRowsUseOriginalFrameHeightAndClampToCrop() {
        val band = SyntheticSlotBands.outlinedBoxes().band()
        assertEquals(SyntheticSlotBands.FRAME_HEIGHT, band.frameHeight)
        assertEquals(136 until 231, band.rows(0.104, 0.176))
        assertEquals(0 until 10, band.rows(-1.0, 10.0 / SyntheticSlotBands.FRAME_HEIGHT))
    }
}
