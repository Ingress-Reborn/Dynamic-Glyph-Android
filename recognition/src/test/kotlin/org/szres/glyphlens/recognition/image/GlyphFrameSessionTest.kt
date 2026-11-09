package org.szres.glyphlens.recognition.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.szres.glyphlens.recognition.GlyphPoint

class GlyphFrameSessionTest {
    private val blank = SyntheticRaster()
    private val clear = blank.copy().also {
        it.line(it.point(160.0, 130.0), it.point(160.0, 418.0))
    }

    @Test
    fun savedDeviceGeometryRevalidatesLocallyInANewSessionAndAfterResolutionChanges() {
        var saved: DeviceCalibration? = null
        val first = GlyphFrameSession(pixelTestTable(), onCalibration = { saved = it })
        first.observe(blank.image(), 0.0)
        val loaded = requireNotNull(DeviceCalibration.decode(requireNotNull(saved).encode()))
        val next = GlyphFrameSession(pixelTestTable(), initialCalibration = loaded)

        assertNull(next.retainCalibration(blank.image(), 1.0))
        assertNull(next.validatedAt)
        assertEquals(GlyphFrameObservation.State.NO_STROKE, next.observe(blank.image(), 1.1)?.state)
        assertNull(next.lastGridHuntNanoseconds)
        assertEquals(listOf(id("clear")), next.observe(clear.image(), 1.2)?.exactMatches)

        val larger = SyntheticRaster(scale = 2.0)
        assertEquals(GlyphFrameObservation.State.NO_STROKE, next.observe(larger.image(), 1.3)?.state)
        assertNull(next.lastGridHuntNanoseconds)
        larger.line(larger.point(160.0, 130.0), larger.point(160.0, 418.0))
        assertEquals(listOf(id("clear")), next.observe(larger.image(), 1.4)?.exactMatches)
    }

    @Test
    fun savedCoordinatesCannotActivateCalibrationWithoutAnEmptyGrid() {
        val saved = DeviceCalibration.fromDetection(requireNotNull(NodeArrayDetector().detect(blank.image())))
        val session = GlyphFrameSession(pixelTestTable(), initialCalibration = saved)
        assertNull(session.observe(clear.image(), 0.0))
        assertNull(session.calibration)
        assertNull(session.validatedAt)
        assertNull(session.observe(LuminanceImage(blank.width, blank.height, ByteArray(blank.width * blank.height)), 0.1))
        assertNull(session.calibration)
        assertEquals(GlyphFrameObservation.State.NO_STROKE, session.observe(blank.image(), 0.2)?.state)
        assertNull(session.lastGridHuntNanoseconds)
    }

    @Test
    fun savedGeometryFallsBackForMovedGridAndResetDiscardsTheHint() {
        val saved = DeviceCalibration.fromDetection(requireNotNull(NodeArrayDetector().detect(blank.image())))
        val writes = mutableListOf<DeviceCalibration>()
        val session = GlyphFrameSession(pixelTestTable(), saved, writes::add)
        val moved = SyntheticRaster(offset = GlyphPoint(20.0, 24.0))
        assertEquals(GlyphFrameObservation.State.NO_STROKE, session.observe(moved.image(), 0.0)?.state)
        assertNotNull(session.lastGridHuntNanoseconds)
        assertEquals(1, writes.size)
        assertEquals(saved.origin.x + 20.0 / moved.width, writes.single().origin.x, 0.001)

        session.observe(moved.image(), 0.5)
        assertNull(session.lastGridHuntNanoseconds)
        assertEquals(1, writes.size)
        session.reset()
        session.observe(moved.image(), 1.0)
        assertNull(session.lastGridHuntNanoseconds)
        assertEquals(1, writes.size)

        session.resetCalibration()
        assertNull(session.calibration)
        assertNull(session.validatedAt)
        session.observe(moved.image(), 1.1)
        assertNotNull(session.lastGridHuntNanoseconds)
        assertEquals(2, writes.size)
    }

    @Test
    fun storedGeometryRejectsMalformedOrOutOfFrameValuesAtThePersistenceBoundary() {
        for (stored in listOf(null, "", "0.1,0.2,0.3", "0.1,0.2,NaN,0.4,0.02", "0.2,0.3,0.9,0.5,0.02")) {
            assertNull(DeviceCalibration.decode(stored))
        }
        val original = DeviceCalibration.fromDetection(requireNotNull(NodeArrayDetector().detect(blank.image())))
        assertEquals(original, DeviceCalibration.decode(original.encode()))
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
    fun noStrokePeriodicallyVerifiesKnownRingsWithoutAnotherFullSearch() {
        val session = GlyphFrameSession(pixelTestTable())
        session.observe(blank.image(), 10.0)
        val firstFit = session.calibration
        session.observe(blank.image(), 10.49)
        assertNull(session.lastGridHuntNanoseconds)
        session.observe(blank.image(), 10.5)
        assertNull(session.lastGridHuntNanoseconds)
        assertEquals(10.5, requireNotNull(session.validatedAt), 0.0)
        assertSame(firstFit, session.calibration)
    }

    @Test
    fun localRingVerificationIgnoresPixelsOutsideTheNodeAreas() {
        val session = GlyphFrameSession(pixelTestTable())
        session.observe(blank.image(), 0.0)
        val fit = requireNotNull(session.calibration)
        val noisy = blank.copy()
        for (y in 10..30) for (x in 10..30) noisy.pixels[y * noisy.width + x] = 230.toByte()

        assertSame(fit, NodeArrayDetector().revalidate(noisy.image(), fit))
        assertEquals(GlyphFrameObservation.State.NO_STROKE, session.observe(noisy.image(), 0.5)?.state)
        assertNull(session.lastGridHuntNanoseconds)
        assertEquals(0.5, requireNotNull(session.validatedAt), 0.0)
    }

    @Test
    fun missingOrFilledRingFallsBackWithoutRefreshingEmptyValidation() {
        for (value in listOf(8, 230)) {
            val session = GlyphFrameSession(pixelTestTable())
            session.observe(blank.image(), 0.0)
            val fit = requireNotNull(session.calibration)
            val changed = blank.copy()
            val node = changed.nodes.first()
            val radius = if (value == 8) 9 else 4
            for (y in node.y.toInt() - radius..node.y.toInt() + radius) {
                for (x in node.x.toInt() - radius..node.x.toInt() + radius) {
                    changed.pixels[y * changed.width + x] = value.toByte()
                }
            }

            assertNull(NodeArrayDetector().revalidate(changed.image(), fit))
            session.observe(changed.image(), 0.5)
            assertNotNull(session.lastGridHuntNanoseconds)
            assertEquals(0.0, requireNotNull(session.validatedAt), 0.0)
        }
    }

    @Test
    fun expiredMovedArrayIsReacquiredAtItsNewCoordinates() {
        val session = GlyphFrameSession(pixelTestTable())
        session.observe(blank.image(), 0.0)
        val original = requireNotNull(session.calibration)
        val moved = SyntheticRaster(offset = GlyphPoint(20.0, 24.0)).image()
        assertNull(NodeArrayDetector().revalidate(moved, original))
        for (second in 1..8) assertSame(original, session.retainCalibration(moved, second.toDouble()))

        assertEquals(GlyphFrameObservation.State.NO_STROKE, session.observe(moved, 8.1)?.state)
        assertNotNull(session.lastGridHuntNanoseconds)
        val updated = requireNotNull(session.calibration)
        assertEquals(original.origin.x + 20.0, updated.origin.x, 0.1)
        assertEquals(original.origin.y + 24.0, updated.origin.y, 0.1)
        assertEquals(8.1, requireNotNull(session.validatedAt), 0.0)
    }

    @Test
    fun smallEmptyArrayMovementUpdatesSamplingCoordinatesBeforeTheNextStroke() {
        val session = GlyphFrameSession(pixelTestTable())
        session.observe(blank.image(), 0.0)
        val original = requireNotNull(session.calibration)
        val moved = SyntheticRaster(offset = GlyphPoint(3.2, 0.0))

        assertEquals(GlyphFrameObservation.State.NO_STROKE, session.observe(moved.image(), 0.5)?.state)
        val updated = requireNotNull(session.calibration)
        assertEquals(original.origin.x + 3.2, updated.origin.x, 0.2)
        assertEquals(original.origin.y, updated.origin.y, 0.2)
        assertEquals(0.5, requireNotNull(session.validatedAt), 0.0)
        moved.line(moved.point(160.0, 130.0), moved.point(160.0, 418.0))
        assertEquals(listOf(id("clear")), session.observe(moved.image(), 0.6)?.exactMatches)
    }

    @Test
    fun retainingCalibrationDoesNotReadShapesOrRefreshTheEmptyDeadline() {
        val session = GlyphFrameSession(pixelTestTable())
        session.observe(blank.image(), 0.0)
        val fit = requireNotNull(session.calibration)
        for (second in 1..8) {
            assertSame(fit, session.retainCalibration(clear.image(), second.toDouble()))
            assertEquals(0.0, requireNotNull(session.validatedAt), 0.0)
            assertNull(session.lastGridHuntNanoseconds)
        }
        assertNull(session.retainCalibration(blank.image(), 8.1))
        assertNull(session.calibration)
        session.observe(blank.image(), 8.2)
        assertNotNull(session.calibration)
        assertNull(session.retainCalibration(clear.image(), 10.0))
        assertNull(session.calibration)
        session.observe(blank.image(), 10.1)
        assertNull(session.retainCalibration(clear.image(), 9.0))
        session.observe(blank.image(), 10.2)
        assertNull(session.retainCalibration(LuminanceImage(20, 40, ByteArray(800)), 10.3))
    }
}
