package org.szres.glyphlens.recognition.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class GlyphFrameSessionTest {
    private val blank = SyntheticRaster()
    private val clear = blank.copy().also {
        it.line(it.point(160.0, 130.0), it.point(160.0, 418.0))
    }

    @Test
    fun liveSessionAcquiresEmptyGeometryAndReusesItAcrossShapes() {
        val session = GlyphFrameSession(pixelTestTable())
        assertEquals(GlyphFrameObservation.State.NO_STROKE, session.observe(blank.image(), 1.0)?.state)
        val original = requireNotNull(session.calibration)
        assertEquals(1.0, requireNotNull(session.validatedAt), 0.0)
        assertNotNull(session.lastGridHuntNanoseconds)
        assertEquals(listOf(id("clear")), session.observe(clear.image(), 1.1)?.exactMatches)
        assertSame(original, session.calibration)
        assertNull(session.lastGridHuntNanoseconds)
    }

    @Test
    fun calibrationInvalidatesOnGapClockReversalDimensionsAndReset() {
        val session = GlyphFrameSession(pixelTestTable())
        assertEquals(GlyphFrameObservation.State.NO_STROKE, session.observe(blank.image(), 1.0)?.state)
        assertEquals(listOf(id("clear")), session.observe(clear.image(), 1.1)?.exactMatches)
        assertNull(session.observe(clear.image(), 3.0))
        assertNull(session.calibration)
        assertEquals(GlyphFrameObservation.State.NO_STROKE, session.observe(blank.image(), 4.0)?.state)
        assertNull(session.observe(clear.image(), 2.0))
        assertEquals(GlyphFrameObservation.State.NO_STROKE, session.observe(blank.image(), 5.0)?.state)
        assertNull(session.observe(LuminanceImage(20, 40, ByteArray(800)), 5.1))
        assertNull(session.calibration)
        assertEquals(GlyphFrameObservation.State.NO_STROKE, session.observe(blank.image(), 6.0)?.state)
        session.reset()
        assertNull(session.observe(clear.image(), 6.1))
        assertEquals(GlyphFrameObservation.State.NO_STROKE, session.observe(blank.image(), 6.2)?.state)
        assertNull(session.observe(blank.image(), Double.NaN))
        assertNull(session.calibration)
    }

    @Test
    fun cachedCalibrationExpiresWithoutEmptyArrayRevalidation() {
        val session = GlyphFrameSession(pixelTestTable())
        assertEquals(GlyphFrameObservation.State.NO_STROKE, session.observe(blank.image(), 0.0)?.state)
        for (step in 1..80) {
            assertEquals(listOf(id("clear")), session.observe(clear.image(), step / 10.0)?.exactMatches)
        }
        assertNull(session.observe(clear.image(), 8.1))
        assertNull(session.calibration)
        assertEquals(GlyphFrameObservation.State.NO_STROKE, session.observe(blank.image(), 8.2)?.state)
        assertEquals(listOf(id("clear")), session.observe(clear.image(), 8.3)?.exactMatches)
    }

    @Test
    fun noStrokePeriodicallyTriggersIndependentFullRingValidation() {
        val session = GlyphFrameSession(pixelTestTable())
        session.observe(blank.image(), 10.0)
        val firstFit = session.calibration
        session.observe(blank.image(), 10.49)
        assertNull(session.lastGridHuntNanoseconds)
        session.observe(blank.image(), 10.5)
        assertNotNull(session.lastGridHuntNanoseconds)
        assertEquals(10.5, requireNotNull(session.validatedAt), 0.0)
        assertNotNull(firstFit)
    }
}
