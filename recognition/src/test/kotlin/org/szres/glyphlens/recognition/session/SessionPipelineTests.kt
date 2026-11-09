package org.szres.glyphlens.recognition.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.szres.glyphlens.recognition.CaptureSessionID
import org.szres.glyphlens.recognition.GlyphID
import org.szres.glyphlens.recognition.PredictionState
import org.szres.glyphlens.recognition.TrackingState
import org.szres.glyphlens.recognition.image.GameUILayout
import org.szres.glyphlens.recognition.image.GlyphFrameObservation
import org.szres.glyphlens.recognition.image.GlyphFrameSession
import org.szres.glyphlens.recognition.image.SlotStripReader
import org.szres.glyphlens.recognition.image.SlotStripRejection
import org.szres.glyphlens.recognition.image.SyntheticRaster
import org.szres.glyphlens.recognition.image.SyntheticSlotBands
import org.szres.glyphlens.recognition.image.pixelTestTable
import org.szres.glyphlens.recognition.schedule.GameHeaderReader
import org.szres.glyphlens.recognition.text.CaptureSessionID as TextCaptureSessionID
import org.szres.glyphlens.recognition.text.GamePhase
import org.szres.glyphlens.recognition.text.HeaderPhraseMatcher
import org.szres.glyphlens.recognition.text.HeaderReading

class SessionPipelineTests {
    @Test
    fun shapeEvidenceUsesProductionRecognizerWithoutReclassification() {
        val raster = SyntheticRaster()
        val session = GlyphFrameSession(pixelTestTable())
        val blank = requireNotNull(session.observe(raster.image(), 1.0))
        val blankShape = ShapeEvidence.from(blank)
        assertEquals(ShapeEvidence.Kind.NO_STROKE, blankShape.kind)
        assertTrue(blankShape.edges.isEmpty())

        val drawn = raster.copy().also {
            it.line(it.point(160.0, 130.0), it.point(160.0, 418.0))
        }
        val observation = requireNotNull(session.observe(drawn.image(), 1.1))
        assertEquals(GlyphFrameObservation.State.UNIQUE_VISIBLE_SHAPE, observation.state)
        val shape = ShapeEvidence.from(observation)
        assertEquals(ShapeEvidence.Kind.UNIQUE_VISIBLE_SHAPE, shape.kind)
        assertEquals(listOf(GlyphID("clear")), observation.exactMatches)
        assertEquals(GlyphID("clear"), shape.glyph)
        assertEquals(observation.edges, shape.edges)
        assertEquals(observation.extensions, shape.extensions)
        assertEquals(ShapeEvidence.unavailable, ShapeEvidence.from(null))
    }

    @Test
    fun evidenceAssemblyJoinsLayoutStripTimerAndDelayedHeader() {
        val session = GlyphFrameSession(pixelTestTable())
        val observation = requireNotNull(session.observe(SyntheticRaster().image(), 1.0))
        val strip = SlotStripReader().read(SyntheticSlotBands.outlinedBoxes(highlighted = 0).band())
        assertTrue(strip.isSuccess)
        val header = HeaderPhraseMatcher().read(
            listOf("Incoming glyph sequence" to 0.9),
        ).at(0.8)
        val evidence = FrameEvidence.fromAnalysis(
            sessionID = CaptureSessionID(7UL),
            time = 1.0,
            header = header,
            observation = observation,
            slotResult = strip,
        )
        assertTrue(evidence.hasValidCalibration)
        assertEquals(header, evidence.header)
        assertEquals(strip.getOrNull(), evidence.slots)
        assertEquals(ShapeEvidence.Kind.NO_STROKE, evidence.shape.kind)
        assertNull(evidence.slotRejection)

        val inconsistent = FrameEvidence.fromAnalysis(
            sessionID = CaptureSessionID(7UL),
            time = 1.0,
            header = header,
            observation = observation,
            slotResult = Result.failure(SlotStripRejection.CoveredByOverlay),
            hasInputTimerBar = true,
            layout = GameUILayout(inputTimerBarBottom = 0.3),
        )
        assertFalse(inconsistent.hasValidCalibration)
        assertNull(inconsistent.header)
        assertNull(inconsistent.slots)
        assertEquals(SlotStripRejection.CoveredByOverlay, inconsistent.slotRejection)
        assertTrue(inconsistent.hasInputTimerBar)
    }

    @Test
    fun ocrSchedulePixelsAndTrackerRunThroughOneProductionEvidencePath() {
        val textSession = TextCaptureSessionID(7UL)
        val reader = GameHeaderReader()
        val request = requireNotNull(reader.submit(at = 1.0, session = textSession, inHack = true))
        reader.finishHeader(request, listOf("Incoming glyph sequence" to 0.95))
        val header = requireNotNull(reader.reading(frameTime = 1.0, session = textSession))
        assertEquals(GamePhase.presenting, header.phase)
        assertEquals(1.0, header.sourceTime, 0.0)

        val observation = requireNotNull(
            GlyphFrameSession(pixelTestTable()).observe(SyntheticRaster().image(), 1.0),
        )
        val strip = SlotStripReader().read(SyntheticSlotBands.outlinedBoxes(highlighted = 0).band())
        val tracker = PresentationTracker(textSession)
        tracker.accept(
            FrameEvidence.fromAnalysis(
                sessionID = textSession,
                time = 1.0,
                header = header,
                observation = observation,
                slotResult = strip,
            ),
        )
        assertEquals(TrackingState.PRESENTING, tracker.state)
        assertEquals(1, tracker.hackID)
        assertEquals(4, tracker.slots.size)

        val snapshot = PresentationSnapshotFactory().snapshot(
            tracker,
            observation,
            arrivalUptime = 20.0,
            sourceTime = 1.0,
        )
        assertEquals(PredictionState.UNAVAILABLE, snapshot.outlook.state)
        assertEquals(CaptureSessionID(7UL), snapshot.captureSessionID)
        assertEquals(textSession, snapshot.captureSessionID)
        assertTrue(snapshot.calibrationSummary.startsWith("11 rings · RMS "))
    }

    @Test
    fun timerFromStripIsIndependentOfItsHighlight() {
        val timer = SlotStripReader().read(SyntheticSlotBands.timer())
        val reading = requireNotNull(timer.getOrNull())
        assertTrue(reading.hasInputTimerBar)
        val evidence = FrameEvidence(
            sessionID = CaptureSessionID(1UL),
            time = 2.0,
            header = HeaderReading(GamePhase.input, "go", 1.0, 2.0),
            slots = reading,
            shape = ShapeEvidence.unavailable,
            hasValidCalibration = true,
        )
        assertTrue(evidence.hasInputTimerBar)
    }
}
