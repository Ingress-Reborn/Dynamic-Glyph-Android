package org.szres.glyphlens.recognition.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.szres.glyphlens.recognition.CaptureSessionID
import org.szres.glyphlens.recognition.GlyphGeometry
import org.szres.glyphlens.recognition.GlyphID
import org.szres.glyphlens.recognition.GlyphTable
import org.szres.glyphlens.recognition.PhaseSource
import org.szres.glyphlens.recognition.PredictionState
import org.szres.glyphlens.recognition.SequencePredictor
import org.szres.glyphlens.recognition.SequenceTable
import org.szres.glyphlens.recognition.SlotObservation
import org.szres.glyphlens.recognition.TileStyle
import org.szres.glyphlens.recognition.TrackingEvent
import org.szres.glyphlens.recognition.TrackingState
import org.szres.glyphlens.recognition.image.SlotStripReading
import org.szres.glyphlens.recognition.tileStyles
import org.szres.glyphlens.recognition.upcomingGuidance

class PresentationTrackerTests {
    private val table = GlyphTable.parse(
        """
        see,03
        advance,038
        truth,05a
        all,018a920
        perspective,275
        destiny,13679
        """.trimIndent(),
    )

    private inner class Feed {
        val session = CaptureSessionID(1UL)
        val tracker = PresentationTracker(session)
        var time = 100.0
        val events = mutableListOf<TrackingEvent>()

        fun prelude(slots: Int) {
            hold(3, slots = slots)
        }

        fun step(
            slots: Int? = null,
            highlight: Int? = null,
            glyph: String? = null,
            timerBar: Boolean = false,
            calibrated: Boolean = true,
            after: Double = 0.05,
        ): List<TrackingEvent> {
            time += after
            val id = glyph?.let(::GlyphID)
            val shape = if (id == null) {
                ShapeEvidence(ShapeEvidence.Kind.NO_STROKE, null, emptySet(), emptyList())
            } else {
                val path = requireNotNull(table.path(forID = id))
                ShapeEvidence(
                    ShapeEvidence.Kind.UNIQUE_VISIBLE_SHAPE,
                    id,
                    GlyphGeometry.normalizedEdges(path.undirectedEdges),
                    emptyList(),
                )
            }
            val reading = slots?.let { count ->
                SlotStripReading(
                    slotCount = count,
                    highlightedIndex = highlight,
                    markerCentersX = (0 until count).map { (it + 0.5) / count },
                    hasInputTimerBar = timerBar,
                )
            }
            val produced = tracker.accept(
                FrameEvidence(
                    sessionID = session,
                    time = time,
                    slots = reading,
                    shape = shape,
                    hasValidCalibration = calibrated,
                    hasInputTimerBar = timerBar,
                ),
            )
            events += produced
            return produced
        }

        fun hold(
            count: Int,
            slots: Int? = null,
            highlight: Int? = null,
            glyph: String? = null,
            timerBar: Boolean = false,
        ) {
            repeat(count) { step(slots, highlight, glyph, timerBar) }
        }

        fun map() {
            time += 0.05
            tracker.observeMap(time)
        }

        val committed: List<String?>
            get() = tracker.slots.map { it.glyph?.name }
        val statuses: List<SlotObservation.Status>
            get() = tracker.slots.map { it.status }
        fun count(kind: TrackingEvent.Kind): Int = events.count { it.kind == kind }
    }

    @Test
    fun strokesWithoutAStablePreludeNeverStartPresentation() {
        val drawing = Feed()
        drawing.hold(8, glyph = "see")
        drawing.hold(8, slots = 2, highlight = 0, glyph = "truth")
        drawing.hold(8, slots = 2, highlight = 1, glyph = "all", timerBar = true)
        assertTrue(drawing.tracker.slots.isEmpty())
        assertEquals(0, drawing.tracker.hackID)
        assertFalse(drawing.tracker.phaseSupportedByCurrentPixels)

        val brief = Feed()
        brief.step(slots = 2)
        brief.hold(4, slots = 2, highlight = 0, glyph = "see")
        assertEquals(0, brief.tracker.hackID)

        val late = Feed()
        late.prelude(4)
        late.hold(4, slots = 4, highlight = 2, glyph = "advance")
        assertTrue(late.tracker.slots.isEmpty())
    }

    @Test
    fun playerStrokesNeverOverwriteThePresentedSequence() {
        val f = Feed()
        f.prelude(2)
        f.hold(6, slots = 2, highlight = 0, glyph = "see")
        f.hold(6, slots = 2, highlight = 1, glyph = "advance")
        f.step(slots = 2, highlight = 0, glyph = "truth", timerBar = true)
        assertEquals(TrackingState.INPUT, f.tracker.state)
        assertEquals(PhaseSource.INPUT_TIMER, f.tracker.phaseSource)
        assertEquals(listOf("see", "advance"), f.committed)
        val frozen = f.tracker.slots
        f.hold(10, slots = 2, highlight = 0, glyph = "truth", timerBar = true)
        f.hold(10, slots = 2, highlight = 1, glyph = "all", timerBar = true)
        assertEquals(frozen, f.tracker.slots)
        assertTrue(f.tracker.isFrozen)
        assertEquals(1, f.tracker.inputSlotIndex)
    }

    @Test
    fun stableShapeWaitsForBoundaryAndRepeatedGlyphsKeepTheirSlots() {
        val f = Feed()
        f.prelude(3)
        f.hold(6, slots = 3, highlight = 0, glyph = "see")
        assertEquals(SlotObservation.Status.OBSERVING, f.statuses[0])
        assertTrue(f.tracker.confirmedPrefix.isEmpty())
        f.hold(6, slots = 3, highlight = 1, glyph = "advance")
        f.hold(6, slots = 3, highlight = 2, glyph = "see")
        f.step(slots = 3, highlight = 0, timerBar = true)
        assertEquals(listOf("see", "advance", "see"), f.committed)
        assertEquals(3, f.count(TrackingEvent.Kind.SLOT_COMMITTED))
    }

    @Test
    fun missingAndSkippedSlotsKeepTheirOriginalPositions() {
        val f = Feed()
        f.prelude(4)
        f.hold(6, slots = 4, highlight = 0, glyph = "see")
        f.hold(6, slots = 4, highlight = 1)
        f.hold(6, slots = 4, highlight = 2, glyph = "advance")
        f.hold(6, slots = 4, highlight = 3, glyph = "all")
        f.step(slots = 4, highlight = 0, timerBar = true)
        assertEquals(listOf("see", null, "advance", "all"), f.committed)
        assertEquals(SlotObservation.Status.MISSING, f.statuses[1])
        assertEquals(listOf(GlyphID("see")), f.tracker.confirmedPrefix)

        val skipped = Feed()
        skipped.prelude(4)
        skipped.hold(6, slots = 4, highlight = 0, glyph = "see")
        skipped.hold(6, slots = 4, highlight = 3, glyph = "all")
        assertEquals(SlotObservation.Status.MISSING, skipped.statuses[1])
        assertEquals(SlotObservation.Status.MISSING, skipped.statuses[2])
    }

    @Test
    fun nestedStableShapesChooseTheLargestButUnrelatedShapesAbstain() {
        val nested = Feed()
        nested.prelude(2)
        nested.hold(3, slots = 2, highlight = 0, glyph = "see")
        nested.hold(3, slots = 2, highlight = 0, glyph = "advance")
        nested.step(slots = 2, highlight = 1)
        assertEquals("advance", nested.committed[0])
        assertEquals(listOf(GlyphID("see")), nested.tracker.slots[0].alternatives)

        val unrelated = Feed()
        unrelated.prelude(2)
        unrelated.hold(4, slots = 2, highlight = 0, glyph = "see")
        unrelated.hold(4, slots = 2, highlight = 0, glyph = "perspective")
        unrelated.step(slots = 2, highlight = 1)
        assertEquals(SlotObservation.Status.UNCERTAIN, unrelated.statuses[0])
        assertNull(unrelated.tracker.slots[0].glyph)
        assertTrue(unrelated.tracker.confirmedPrefix.isEmpty())
    }

    @Test
    fun slotCountNeedsTwoVotesAndQualifiedDisagreementIsAConflict() {
        val f = Feed()
        f.prelude(2)
        f.step(slots = 2, highlight = 0)
        assertNull(f.tracker.expectedSlotCount)
        f.step(slots = 2, highlight = 0)
        assertEquals(2, f.tracker.expectedSlotCount)
        f.hold(4, slots = 3, highlight = 0)
        assertTrue(f.tracker.slotCountConflict)
        assertNull(f.tracker.expectedSlotCount)
    }

    @Test
    fun losingVisualEvidenceRetainsBrieflyThenInterruptsWithoutLosingConfirmedSlots() {
        val f = Feed()
        f.prelude(2)
        f.hold(6, slots = 2, highlight = 0, glyph = "see")
        f.step(slots = 2, highlight = 1)
        f.step(calibrated = false)
        assertEquals(TrackingState.PRESENTING, f.tracker.state)
        assertEquals(PhaseSource.RETAINED, f.tracker.phaseSource)
        assertFalse(f.tracker.phaseSupportedByCurrentPixels)
        f.step(calibrated = false, after = 0.8)
        assertEquals(TrackingState.INTERRUPTED, f.tracker.state)
        assertEquals("see", f.committed[0])
    }

    @Test
    fun intermediateBlankAndBrieflyHiddenSlotsPreserveTheVisualSequence() {
        val f = Feed()
        f.prelude(2)
        f.hold(4, slots = 2, highlight = 0, glyph = "see")
        f.hold(3, glyph = "perspective")
        assertEquals(PhaseSource.RETAINED, f.tracker.phaseSource)
        assertFalse(f.tracker.phaseSupportedByCurrentPixels)
        f.hold(3, slots = 2, highlight = 0, glyph = "see")
        f.hold(3, slots = 2)
        assertEquals(PhaseSource.INDICATOR_TRANSITION, f.tracker.phaseSource)
        assertEquals(0, f.tracker.currentSlotIndex)
        f.hold(4, slots = 2, highlight = 1, glyph = "truth")
        f.step(slots = 2, highlight = 0, timerBar = true)
        assertEquals(listOf("see", "truth"), f.committed)
        assertEquals(List(2) { SlotObservation.Status.CONFIRMED }, f.statuses)
        assertTrue(f.tracker.slots[0].alternatives.isEmpty())
        assertEquals(7, f.tracker.slots[0].sampleCount)
        assertEquals(1, f.tracker.hackID)
    }

    @Test
    fun lastIndicatorReleaseFinalizesTheFinalGlyphBeforeInput() {
        val f = Feed()
        f.prelude(2)
        f.hold(6, slots = 2, highlight = 0, glyph = "see")
        f.hold(6, slots = 2, highlight = 1, glyph = "advance")
        f.hold(3, slots = 2)
        assertEquals(TrackingState.PRESENTING, f.tracker.state)
        assertEquals(listOf("see", "advance"), f.committed)
        assertEquals(listOf(GlyphID("see"), GlyphID("advance")), f.tracker.confirmedPrefix)
        f.step(slots = 2, highlight = 0, timerBar = true)
        assertEquals(TrackingState.INPUT, f.tracker.state)
        assertEquals(1, f.tracker.hackID)
    }

    @Test
    fun releaseRequiresLastSlotClearArrayAndStableShape() {
        val drawing = Feed()
        drawing.prelude(2)
        drawing.hold(6, slots = 2, highlight = 0, glyph = "see")
        drawing.hold(3, slots = 2, highlight = 1, glyph = "truth")
        drawing.hold(3, slots = 2, glyph = "truth")
        assertEquals(1, drawing.tracker.currentSlotIndex)
        assertEquals(SlotObservation.Status.OBSERVING, drawing.statuses[1])

        val early = Feed()
        early.prelude(3)
        early.hold(6, slots = 3, highlight = 0, glyph = "see")
        early.hold(4, slots = 3)
        assertTrue(early.tracker.confirmedPrefix.isEmpty())

        val unstable = Feed()
        unstable.prelude(2)
        unstable.hold(6, slots = 2, highlight = 0, glyph = "see")
        unstable.step(slots = 2, highlight = 1, glyph = "truth")
        unstable.hold(4, slots = 2)
        assertEquals(listOf(GlyphID("see")), unstable.tracker.confirmedPrefix)
    }

    @Test
    fun losingSlotsCalibrationOrTimerRemovesCurrentPixelProof() {
        for (loseSlots in listOf(false, true)) {
            val f = Feed()
            f.prelude(2)
            f.hold(4, slots = 2, highlight = 0, glyph = "see")
            assertTrue(f.tracker.phaseSupportedByCurrentPixels)
            f.step(slots = if (loseSlots) null else 2, highlight = 0, calibrated = loseSlots)
            assertFalse(f.tracker.phaseSupportedByCurrentPixels)
        }
        val f = Feed()
        f.prelude(2)
        f.hold(4, slots = 2, highlight = 0, glyph = "see")
        f.step(slots = 2, highlight = 0, timerBar = true)
        val frozen = f.tracker.slots
        f.step(slots = 2, highlight = 0)
        assertFalse(f.tracker.phaseSupportedByCurrentPixels)
        assertEquals(TrackingState.INPUT, f.tracker.state)
        assertEquals(frozen, f.tracker.slots)
        f.step(slots = 2, highlight = 0, timerBar = true)
        assertTrue(f.tracker.phaseSupportedByCurrentPixels)
    }

    @Test
    fun fiveGlyphsPreserveFourthAndFifthAndPredictFromConfirmedPrefix() {
        val f = Feed()
        val names = listOf("see", "advance", "truth", "all", "destiny")
        val sequence = names.map(::GlyphID)
        val factory = PresentationSnapshotFactory(SequencePredictor(SequenceTable(listOf(sequence))))
        f.prelude(5)
        f.hold(4, slots = 5, highlight = 0, glyph = names[0])
        f.hold(4, slots = 5, highlight = 1, glyph = names[1])
        f.step(slots = 5, highlight = 2)
        val prediction = factory.snapshot(f.tracker, null, f.time, f.time)
        assertEquals(PredictionState.RESOLVED, prediction.outlook.state)
        assertEquals(sequence.take(2), prediction.outlook.observed)
        assertEquals(sequence.drop(2), prediction.outlook.predictedSuffix)
        assertEquals(TileStyle.Predicted(sequence[3]), prediction.tileStyles[3])
        assertEquals(TileStyle.Predicted(sequence[4]), prediction.tileStyles[4])
        for (index in 2 until 5) {
            f.hold(4, slots = 5, highlight = index, glyph = names[index])
        }
        f.step(slots = 5, highlight = 0, timerBar = true)
        assertEquals(names, f.committed)
        assertEquals(List(5) { SlotObservation.Status.CONFIRMED }, f.statuses)
        assertEquals(sequence, f.tracker.confirmedPrefix)
    }

    @Test
    fun mapRetainsResultsAndNextPreludeStartsANewHackOnlyOnce() {
        val f = Feed()
        f.prelude(2)
        f.hold(4, slots = 2, highlight = 0, glyph = "see")
        f.hold(4, slots = 2, highlight = 1, glyph = "advance")
        f.step(slots = 2, highlight = 0, timerBar = true)
        val frozen = f.tracker.slots
        f.map()
        f.map()
        assertEquals(frozen, f.tracker.slots)
        assertEquals(1, f.tracker.hackID)
        assertNotEquals(TrackingState.COMPLETED, f.tracker.state)
        f.prelude(2)
        f.step(slots = 2, highlight = 0, glyph = "truth")
        assertEquals(2, f.tracker.hackID)
        assertEquals(listOf(null, null), f.committed)
        f.hold(4, slots = 2, highlight = 0, glyph = "truth")
        f.hold(4, slots = 2, highlight = 1, glyph = "perspective")
        f.step(slots = 2, highlight = 0, timerBar = true)
        assertEquals(listOf("truth", "perspective"), f.committed)
        assertEquals(2, f.count(TrackingEvent.Kind.HACK_STARTED))
    }

    @Test
    fun freshPreludeAfterLongStripAbsenceStartsAnotherSameLengthHack() {
        val f = Feed()
        f.prelude(2)
        f.hold(4, slots = 2, highlight = 0, glyph = "see")
        f.hold(4, slots = 2, highlight = 1, glyph = "advance")
        f.hold(3, slots = 2)
        f.hold(40)
        f.prelude(2)
        f.hold(4, slots = 2, highlight = 0, glyph = "truth")
        f.hold(4, slots = 2, highlight = 1, glyph = "perspective")
        f.step(slots = 2, highlight = 0, timerBar = true)
        assertEquals(2, f.tracker.hackID)
        assertEquals(listOf("truth", "perspective"), f.committed)
    }

    @Test
    fun inputRedoAndHighlightGapsNeverCommitPlayerShapes() {
        val f = Feed()
        val names = listOf("see", "advance", "truth", "all", "destiny")
        f.prelude(5)
        names.forEachIndexed { index, name -> f.hold(4, slots = 5, highlight = index, glyph = name) }
        f.step(slots = 5, highlight = 0, timerBar = true)
        val frozen = f.tracker.slots
        for (index in listOf(0, 1, 2, 3, 2, 3, 4)) {
            f.hold(3, slots = 5, highlight = index, glyph = "perspective", timerBar = true)
            assertEquals(index, f.tracker.inputSlotIndex)
            assertEquals(frozen, f.tracker.slots)
            assertEquals(1, f.tracker.hackID)
        }
        val revision = f.tracker.revision
        f.step(slots = 5, timerBar = true)
        assertEquals(4, f.tracker.inputSlotIndex)
        assertEquals(revision, f.tracker.revision)
        f.hold(8, slots = 5, timerBar = true)
        assertNull(f.tracker.inputSlotIndex)
        assertEquals(frozen, f.tracker.slots)
    }

    @Test
    fun predictionCacheKeepsHolesAndClearsAtTheNextVisualStart() {
        val f = Feed()
        val sequence = listOf("see", "truth", "advance", "all").map(::GlyphID)
        val factory = PresentationSnapshotFactory(SequencePredictor(SequenceTable(listOf(sequence))))
        f.prelude(4)
        f.hold(4, slots = 4, highlight = 0, glyph = "see")
        f.hold(4, slots = 4, highlight = 1)
        f.hold(4, slots = 4, highlight = 2, glyph = "advance")
        f.hold(4, slots = 4, highlight = 3, glyph = "all")
        f.step(slots = 4, highlight = 0, timerBar = true)
        val withHole = factory.snapshot(f.tracker, null, f.time, f.time)
        assertEquals(listOf("see", null, "advance", "all"), withHole.slots.map { it.glyph?.name })
        assertEquals(listOf(GlyphID("see")), withHole.outlook.observed)
        assertEquals(sequence.drop(1), withHole.outlook.predictedSuffix)
        f.map()
        f.prelude(4)
        f.step(slots = 4, highlight = 0, glyph = "truth")
        val next = factory.snapshot(f.tracker, null, f.time, f.time)
        assertEquals(PredictionState.UNOBSERVED, next.outlook.state)
        assertTrue(next.outlook.observed.isEmpty())
        assertTrue(next.outlook.predictedSuffix.isEmpty())
        assertEquals(listOf(null, null, null, null), next.slots.map { it.glyph?.name })
        assertEquals(listOf("see", null, "advance", "all"), withHole.slots.map { it.glyph?.name })
    }

    @Test
    fun mapWithdrawsPlayerGuidanceWithoutInventingCompletion() {
        val f = Feed()
        f.prelude(2)
        f.hold(4, slots = 2, highlight = 0, glyph = "see")
        f.hold(4, slots = 2, highlight = 1, glyph = "advance")
        f.step(slots = 2, highlight = 1, timerBar = true)
        val factory = PresentationSnapshotFactory()
        assertEquals(1, requireNotNull(factory.snapshot(f.tracker, null, f.time, f.time).upcomingGuidance()).firstSlotIndex)
        f.map()
        assertNull(f.tracker.inputSlotIndex)
        assertNull(factory.snapshot(f.tracker, null, f.time, f.time).upcomingGuidance())
        assertEquals(listOf("see", "advance"), f.committed)
        assertNotEquals(TrackingState.COMPLETED, f.tracker.state)
    }

    @Test
    fun resetClearsSequenceAndTiming() {
        val f = Feed()
        f.prelude(2)
        f.hold(4, slots = 2, highlight = 0, glyph = "see")
        f.step(slots = 2, highlight = 1)
        f.tracker.reset()
        assertEquals(TrackingState.INACTIVE, f.tracker.state)
        assertTrue(f.tracker.slots.isEmpty())
        assertTrue(f.tracker.confirmedPrefix.isEmpty())
        assertNull(f.tracker.expectedSlotCount)
        assertNull(f.tracker.lastEvidenceTime)
        assertFalse(f.tracker.isFrozen)
    }
}
