package org.szres.glyphlens.recognition.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotStripReaderTest {
    private val reader = SlotStripReader()

    @Test
    fun calibratedMarkersReadEachNewHighlightWithFewerPixelSamples() {
        reader.read(SyntheticSlotBands.outlinedBoxes().band()).getOrThrow()
        val initialPixels = reader.lastPixelReadCount
        assertFalse(reader.usedCachedMarkers)

        for (highlight in listOf(0, 1, 2, 3, null)) {
            val reading = reader.read(SyntheticSlotBands.outlinedBoxes(highlighted = highlight).band()).getOrThrow()
            assertEquals(4, reading.slotCount)
            assertEquals(highlight, reading.highlightedIndex)
            assertTrue("Unchanged marker geometry should reuse its calibrated samples", reader.usedCachedMarkers)
            assertTrue("Cached reading must use substantially fewer actual pixel reads", reader.lastPixelReadCount < initialPixels / 3)
        }
    }

    @Test
    fun disappearingMarkersDoNotReuseThePreviousReading() {
        reader.read(SyntheticSlotBands.outlinedBoxes(highlighted = 2).band()).getOrThrow()
        val absent = reader.read(SyntheticSlotBands.Canvas().band())
        assertEquals(SlotStripRejection.NoMarkers, absent.exceptionOrNull())
        assertFalse(reader.usedCachedMarkers)

        val restored = reader.read(SyntheticSlotBands.outlinedBoxes(highlighted = 1).band()).getOrThrow()
        assertEquals(4, restored.slotCount)
        assertEquals(1, restored.highlightedIndex)
    }

    @Test
    fun movingTheMarkersReplacesTheirCachedCoordinates() {
        val original = reader.read(SyntheticSlotBands.outlinedBoxes().band()).getOrThrow()
        val moved = reader.read(
            SyntheticSlotBands.outlinedBoxes(lefts = listOf(125, 221, 317, 413), highlighted = 3).band(),
        ).getOrThrow()
        assertEquals(4, moved.slotCount)
        assertEquals(3, moved.highlightedIndex)
        assertFalse(reader.usedCachedMarkers)
        for (index in original.markerCentersX.indices) {
            assertEquals(8.0 / SyntheticSlotBands.WIDTH, moved.markerCentersX[index] - original.markerCentersX[index], 0.0001)
        }
        reader.read(SyntheticSlotBands.outlinedBoxes(lefts = listOf(125, 221, 317, 413)).band()).getOrThrow()
        assertTrue(reader.usedCachedMarkers)
    }

    @Test
    fun addedMarkersBesideACachedSingleSlotCannotKeepTheOldSlotCount() {
        val single = SyntheticSlotBands.singleMarker()
        reader.read(single).getOrThrow()
        val expanded = SyntheticSlotBands.Canvas()
        single.image.pixels.copyInto(expanded.pixels)
        for (y in 150 until 220) {
            for (x in 266 until 336) {
                val source = (y * SyntheticSlotBands.WIDTH + x) * 3
                for (shift in listOf(-96, 96)) {
                    val destination = (y * SyntheticSlotBands.WIDTH + x + shift) * 3
                    repeat(3) { expanded.pixels[destination + it] = single.image.pixels[source + it] }
                }
            }
        }

        val reading = reader.read(expanded.band()).getOrThrow()
        assertEquals(3, reading.slotCount)
        assertNull(reading.highlightedIndex)
        assertFalse(reader.usedCachedMarkers)
    }

    @Test
    fun aNewWindowOverCachedMarkersStillRejectsTheCoveredStrip() {
        reader.read(SyntheticSlotBands.outlinedBoxes(highlighted = 2).band()).getOrThrow()
        assertEquals(
            SlotStripRejection.CoveredByOverlay,
            reader.read(SyntheticSlotBands.windowOverStrip()).exceptionOrNull(),
        )
        assertFalse(reader.usedCachedMarkers)
    }

    @Test
    fun timerSamplesFollowAppearanceColorChangesDisappearanceAndMovedTrack() {
        assertFalse(reader.hasTimerBar(SyntheticSlotBands.outlinedBoxes().band()))
        assertTrue(reader.hasTimerBar(SyntheticSlotBands.timer()))
        assertTrue(reader.hasTimerBar(SyntheticSlotBands.timer(magentaProgress = true)))
        assertFalse(reader.hasTimerBar(SyntheticSlotBands.outlinedBoxes().band()))

        val original = SyntheticSlotBands.timer(magentaProgress = true)
        val moved = SyntheticSlotBands.outlinedBoxes(highlighted = 0)
        for (y in 300 until 303) {
            val source = y * SyntheticSlotBands.WIDTH * 3
            val destination = (y + 10) * SyntheticSlotBands.WIDTH * 3
            original.image.pixels.copyInto(moved.pixels, destination, source, source + SyntheticSlotBands.WIDTH * 3)
        }
        assertTrue(reader.hasTimerBar(moved.band()))
        assertFalse(reader.hasTimerBar(SyntheticSlotBands.Canvas().band()))
    }

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
        reader.read(SyntheticSlotBands.outlinedBoxes().band()).getOrThrow()
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
