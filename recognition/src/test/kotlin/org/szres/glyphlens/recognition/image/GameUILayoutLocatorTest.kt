package org.szres.glyphlens.recognition.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.szres.glyphlens.recognition.RgbaFrame
import kotlin.math.roundToInt

class GameUILayoutLocatorTest {
    @Test
    fun newlyAppearingClippedSingleMarkerIsLocatedBeforePeriodicSearch() {
        val viewport = Viewport(603, 1508, 1.0, 0, 98)
        val empty = viewport.place(scene(SyntheticSlotBands.Canvas().band()))
        val blank = viewport.place(scene(SyntheticSlotBands.singleMarker()))
        val fit = requireNotNull(NodeArrayDetector().detect(empty.luminance))
        val locator = GameUILayoutLocator()
        locator.observe(empty.topBand.image, fit, 0.0)
        assertEquals(SlotStripRejection.NoMarkers, locator.lastSlotResult.exceptionOrNull())
        val clipped = SlotStripReader().read(blank)
        assertTrue(clipped.isFailure)
        assertTrue(clipped.exceptionOrNull() != SlotStripRejection.NoMarkers)

        locator.observe(blank.topBand.image, fit, 0.05)
        val reading = locator.lastSlotResult.getOrThrow()
        assertEquals(1, reading.slotCount)
        assertEquals(null, reading.highlightedIndex)
        locator.observe(blank.topBand.image, fit, 0.10)
        assertTrue(locator.lastSlotUsedCache)
        assertEquals(null, locator.lastSlotResult.getOrThrow().highlightedIndex)
    }

    @Test
    fun stableViewportReusesMarkerSamplesButReadsTheCurrentHighlight() {
        val viewport = Viewport(603, 1311, 1.0, 0, 0)
        val first = viewport.place(scene(SyntheticSlotBands.outlinedBoxes().band()))
        val fit = requireNotNull(NodeArrayDetector().detect(first.luminance))
        val locator = GameUILayoutLocator()
        val layout = locator.observe(first.topBand.image, fit, 0.0)
        val initialPixels = locator.lastSlotPixelReadCount

        val highlighted = viewport.place(scene(SyntheticSlotBands.outlinedBoxes(highlighted = 3).band()))
        assertSame(layout, locator.observe(highlighted.topBand.image, fit, 0.05))
        val reading = locator.lastSlotResult.getOrThrow()
        assertEquals(4, reading.slotCount)
        assertEquals(3, reading.highlightedIndex)
        assertTrue(locator.lastSlotUsedCache)
        assertTrue(locator.lastSlotPixelReadCount < initialPixels / 3)
    }

    @Test
    fun cachedViewportDoesNotKeepMarkersAfterTheyDisappear() {
        val viewport = Viewport(603, 1311, 1.0, 0, 0)
        val first = viewport.place(scene(SyntheticSlotBands.outlinedBoxes(highlighted = 2).band()))
        val fit = requireNotNull(NodeArrayDetector().detect(first.luminance))
        val locator = GameUILayoutLocator()
        locator.observe(first.topBand.image, fit, 0.0)
        assertEquals(2, locator.lastSlotResult.getOrThrow().highlightedIndex)

        val absent = viewport.place(scene(SyntheticSlotBands.Canvas().band()))
        locator.observe(absent.topBand.image, fit, 0.05)
        assertTrue(locator.lastSlotResult.isFailure)
        assertTrue(!locator.lastSlotUsedCache)

        val restored = viewport.place(scene(SyntheticSlotBands.outlinedBoxes(highlighted = 0).band()))
        locator.observe(restored.topBand.image, fit, 0.10)
        assertEquals(4, locator.lastSlotResult.getOrThrow().slotCount)
        assertEquals(0, locator.lastSlotResult.getOrThrow().highlightedIndex)
    }

    @Test
    fun locatesWholeIndicatorsAndCountdownInsideDifferentViewports() {
        val source = scene(SyntheticSlotBands.timer())
        for (viewport in listOf(
            Viewport(603, 1311, 1.0, 0, 0),
            Viewport(603, 1072, 0.78, 66, 24),
            Viewport(603, 1508, 1.0, 0, 98),
            Viewport(603, 2144, 1.0, 0, 416),
            Viewport(804, 1206, 0.85, 146, 46),
            Viewport(301, 666, 0.48, 6, 18),
            Viewport(1206, 2666, 2.0, 0, 22),
        )) {
            val frame = viewport.place(source)
            val fit = requireNotNull(NodeArrayDetector().detect(frame.luminance)) { viewport.toString() }
            assertEquals(11, fit.matchedNodes.size)
            val locator = GameUILayoutLocator()
            val layout = locator.observe(frame.topBand.image, fit, 0.0)
            val reading = SlotStripReader(layout).read(frame).getOrThrow()
            assertEquals(viewport.toString(), 4, reading.slotCount)
            assertEquals(0, reading.highlightedIndex)
            assertTrue(viewport.toString(), reading.hasInputTimerBar)
            assertSame(layout, locator.observe(frame.topBand.image, fit, 0.05))
        }
    }

    @Test
    fun fixedHeightFractionsFailBeforeLocatorFindsUncoveredSlotRow() {
        val frame = Viewport(603, 1508, 1.0, 0, 98).place(scene(SyntheticSlotBands.timer()))
        assertTrue(SlotStripReader().read(frame).isFailure)
        val fit = requireNotNull(NodeArrayDetector().detect(frame.luminance))
        assertTrue(GameUILayout().isConsistent(fit))
        val layout = GameUILayoutLocator().observe(frame.topBand.image, fit, 1.0)
        assertEquals(4, SlotStripReader(layout).read(frame).getOrThrow().slotCount)
    }

    @Test
    fun changedViewportReplacesCachedLayoutWithoutChangingFrameDimensions() {
        val source = scene(SyntheticSlotBands.timer())
        val first = Viewport(803, 1600, 1.0, 100, 70).place(source)
        val second = Viewport(803, 1600, 0.9, 130, 250).place(source)
        val locator = GameUILayoutLocator()
        for ((index, frame) in listOf(first, second).withIndex()) {
            val fit = requireNotNull(NodeArrayDetector().detect(frame.luminance))
            val layout = locator.observe(frame.topBand.image, fit, index * 0.1)
            val reading = SlotStripReader(layout).read(frame).getOrThrow()
            assertEquals(4, reading.slotCount)
            assertTrue(reading.hasInputTimerBar)
        }
    }

    @Test
    fun firstArrayFitCorrectsProvisionalFullFrameLayoutForPillarboxedContent() {
        val frame = Viewport(603, 1180, 0.9, 30, 0).place(scene(SyntheticSlotBands.timer()))
        val defaultReading = SlotStripReader().read(frame).getOrThrow()
        assertEquals(4, defaultReading.slotCount)
        assertTrue(!defaultReading.hasInputTimerBar)

        val locator = GameUILayoutLocator()
        locator.observe(frame.topBand.image, null, 0.0)
        assertEquals(4, locator.lastSlotResult.getOrThrow().slotCount)
        val fit = requireNotNull(NodeArrayDetector().detect(frame.luminance))
        val layout = locator.observe(frame.topBand.image, fit, 0.05)

        assertTrue(layout.contentLeft > 0.0)
        assertTrue(layout.contentRight < 1.0)
        assertEquals(4, locator.lastSlotResult.getOrThrow().slotCount)
        assertTrue(locator.lastSlotResult.getOrThrow().hasInputTimerBar)
        assertTrue(SlotStripReader(layout).hasTimerBar(frame))
    }

    @Test
    fun coveredFragmentsDoNotBecomeACompleteSmallerSlotRow() {
        val frame = Viewport(603, 1508, 1.0, 0, 98).place(scene(SyntheticSlotBands.windowOverStrip()))
        val fit = requireNotNull(NodeArrayDetector().detect(frame.luminance))
        val layout = GameUILayoutLocator().observe(frame.topBand.image, fit, 0.0)
        assertTrue(SlotStripReader(layout).read(frame).isFailure)
    }

    @Test
    fun singleSlotUsesArrayScaleAndCenterRatherThanInventingPitch() {
        val frame = Viewport(803, 1600, 1.0, 100, 100).place(scene(SyntheticSlotBands.singleMarker()))
        val fit = requireNotNull(NodeArrayDetector().detect(frame.luminance))
        val layout = GameUILayoutLocator().observe(frame.topBand.image, fit, 0.0)
        assertEquals(1, SlotStripReader(layout).read(frame).getOrThrow().slotCount)
    }

    @Test
    fun locatesFiveAdjacentGoldAndVioletOutlinesAcrossDarkBackground() {
        val lefts = listOf(69, 165, 261, 357, 453)
        val viewport = Viewport(603, 1508, 1.0, 0, 98)
        for (highlight in listOf(0, 4)) {
            val frame = viewport.place(scene(SyntheticSlotBands.outlinedBoxes(lefts, highlight).band()))
            assertTrue(SlotStripReader().read(frame).isFailure)
            val fit = requireNotNull(NodeArrayDetector().detect(frame.luminance))
            val locator = GameUILayoutLocator()
            locator.observe(frame.topBand.image, fit, 0.0)

            val reading = locator.lastSlotResult.getOrThrow()
            assertEquals(5, reading.slotCount)
            assertEquals(highlight, reading.highlightedIndex)
            for ((index, left) in lefts.withIndex()) {
                assertEquals((left + 39.5) / viewport.width, reading.markerCentersX[index], 0.001)
            }
        }
    }

    @Test
    fun highlightedMarkersDetachedInnerOutlineIsNotAnExtraSlot() {
        val source = scene(SyntheticSlotBands.outlinedBoxes().band())
        for (y in 157..212) {
            for (x in 129..184) {
                if (x in 136..177 && y in 164..205) continue
                val offset = (y * source.width + x) * 3
                source.pixels[offset] = 240.toByte()
                source.pixels[offset + 1] = 180.toByte()
                source.pixels[offset + 2] = 50
            }
        }
        val frame = Viewport(603, 1508, 1.0, 0, 98).place(source)
        assertTrue(SlotStripReader().read(frame).isFailure)
        val fit = requireNotNull(NodeArrayDetector().detect(frame.luminance))
        val locator = GameUILayoutLocator()
        locator.observe(frame.topBand.image, fit, 0.0)
        val reading = locator.lastSlotResult.getOrThrow()
        assertEquals(4, reading.slotCount)
        assertEquals(0, reading.highlightedIndex)
        for ((index, left) in listOf(117, 213, 309, 405).withIndex()) {
            assertEquals((left + 39.5) / frame.luminance.width, reading.markerCentersX[index], 0.001)
        }
    }

    private fun scene(band: FrameTopBand): ColorImage {
        val width = 603
        val height = 1311
        val pixels = ByteArray(width * height * 3)
        val array = SyntheticRaster(width / 320.0).image()
        for (y in 0 until array.height) {
            for (x in 0 until width) {
                val destination = ((y + 247) * width + x) * 3
                repeat(3) { pixels[destination + it] = array.pixels[y * width + x] }
            }
        }
        band.image.pixels.copyInto(pixels)
        return ColorImage(width, height, pixels)
    }

    private data class Viewport(val width: Int, val height: Int, val scale: Double, val left: Int, val top: Int) {
        fun place(source: ColorImage): ImageFrame {
            val contentWidth = (source.width * scale).roundToInt()
            val contentHeight = (source.height * scale).roundToInt()
            val pixels = ByteArray(width * height * 4)
            for (y in 0 until contentHeight) {
                for (x in 0 until contentWidth) {
                    val sx = (x / scale).toInt().coerceAtMost(source.width - 1)
                    val sy = (y / scale).toInt().coerceAtMost(source.height - 1)
                    val destination = ((y + top) * width + x + left) * 4
                    val original = (sy * source.width + sx) * 3
                    repeat(3) { pixels[destination + it] = source.pixels[original + it] }
                    pixels[destination + 3] = 255.toByte()
                }
            }
            return ImageFrame.fromRgba(RgbaFrame(width, height, 0, pixels), fullColor = true)
        }
    }
}
