package org.szres.glyphlens.recognition.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.szres.glyphlens.recognition.CaptureSessionID
import org.szres.glyphlens.recognition.GlyphID
import org.szres.glyphlens.recognition.PhaseSource
import org.szres.glyphlens.recognition.PredictionState
import org.szres.glyphlens.recognition.SequencePredictor
import org.szres.glyphlens.recognition.SequenceTable
import org.szres.glyphlens.recognition.TrackingState
import org.szres.glyphlens.recognition.image.GameUILayout
import org.szres.glyphlens.recognition.image.GlyphFrameObservation
import org.szres.glyphlens.recognition.image.GlyphFrameSession
import org.szres.glyphlens.recognition.image.SlotStripReader
import org.szres.glyphlens.recognition.image.SlotStripRejection
import org.szres.glyphlens.recognition.image.SyntheticRaster
import org.szres.glyphlens.recognition.image.SyntheticSlotBands
import org.szres.glyphlens.recognition.image.pixelTestTable

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
    fun evidenceAssemblyJoinsLayoutStripAndTimer() {
        val session = GlyphFrameSession(pixelTestTable())
        val observation = requireNotNull(session.observe(SyntheticRaster().image(), 1.0))
        val strip = SlotStripReader().read(SyntheticSlotBands.outlinedBoxes(highlighted = 0).band())
        assertTrue(strip.isSuccess)
        val evidence = FrameEvidence.fromAnalysis(
            sessionID = CaptureSessionID(7UL),
            time = 1.0,
            observation = observation,
            slotResult = strip,
        )
        assertTrue(evidence.hasValidCalibration)
        assertEquals(strip.getOrNull(), evidence.slots)
        assertEquals(ShapeEvidence.Kind.NO_STROKE, evidence.shape.kind)
        assertNull(evidence.slotRejection)

        val inconsistent = FrameEvidence.fromAnalysis(
            sessionID = CaptureSessionID(7UL),
            time = 1.0,
            observation = observation,
            slotResult = Result.failure(SlotStripRejection.CoveredByOverlay),
            hasInputTimerBar = true,
            layout = GameUILayout(inputTimerBarBottom = 0.3),
        )
        assertFalse(inconsistent.hasValidCalibration)
        assertNull(inconsistent.slots)
        assertEquals(SlotStripRejection.CoveredByOverlay, inconsistent.slotRejection)
        assertTrue(inconsistent.hasInputTimerBar)
    }

    @Test
    fun pixelsEstablishPresentationAndConfirmThePrefixUsedForPrediction() {
        val capture = CaptureSessionID(7UL)
        val tracker = PresentationTracker(capture)
        val session = GlyphFrameSession(pixelTestTable())
        val reader = SlotStripReader()
        val blank = SyntheticRaster()
        val clear = blank.copy().also {
            it.line(it.point(160.0, 130.0), it.point(160.0, 418.0))
        }
        var time = 1.0
        fun frame(raster: SyntheticRaster, highlight: Int?): GlyphFrameObservation {
            time += 0.05
            val observation = requireNotNull(session.observe(raster.image(), time))
            val strip = reader.read(SyntheticSlotBands.outlinedBoxes(highlighted = highlight).band())
            tracker.accept(
                FrameEvidence.fromAnalysis(
                    sessionID = capture,
                    time = time,
                    observation = observation,
                    slotResult = strip,
                ),
            )
            return observation
        }
        repeat(3) { frame(blank, null) }
        assertEquals(TrackingState.INACTIVE, tracker.state)
        repeat(3) { frame(clear, 0) }
        assertEquals(TrackingState.PRESENTING, tracker.state)
        assertEquals(PhaseSource.INDICATOR_TRANSITION, tracker.phaseSource)
        assertEquals(1, tracker.hackID)
        assertEquals(4, tracker.slots.size)
        assertTrue(tracker.confirmedPrefix.isEmpty())

        val observation = frame(blank, 1)
        assertEquals(listOf(GlyphID("clear")), tracker.confirmedPrefix)
        val sequence = listOf("clear", "advance", "all", "balance").map(::GlyphID)

        val snapshot = PresentationSnapshotFactory(SequencePredictor(SequenceTable(listOf(sequence)))).snapshot(
            tracker,
            observation,
            arrivalUptime = 20.0,
            sourceTime = time,
        )
        assertEquals(PredictionState.RESOLVED, snapshot.outlook.state)
        assertEquals(sequence.drop(1), snapshot.outlook.predictedSuffix)
        assertEquals(CaptureSessionID(7UL), snapshot.captureSessionID)
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
            slots = reading,
            shape = ShapeEvidence.unavailable,
            hasValidCalibration = true,
        )
        assertTrue(evidence.hasInputTimerBar)
    }
}
