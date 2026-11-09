package org.szres.glyphlens.recognition.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
import org.szres.glyphlens.recognition.text.GamePhase
import org.szres.glyphlens.recognition.text.HeaderReading
import org.szres.glyphlens.recognition.tileStyles
import org.szres.glyphlens.recognition.upcomingGuidance

class PresentationTrackerTests {
    private val table = GlyphTable.parse(
        """
        see,03
        advance,038
        truth,05a
        all,018a920
        clear_all,018a9205a
        perspective,275
        future,2750
        begin,0568a975
        new,06a7
        destiny,13679
        """.trimIndent(),
    )

    private inner class Feed(
        policy: PresentationTracker.Policy = PresentationTracker.Policy(),
    ) {
        val session = CaptureSessionID(1UL)
        val tracker = PresentationTracker(session, policy)
        var time = 100.0
        val events = mutableListOf<TrackingEvent>()

        fun shape(name: String?): ShapeEvidence {
            val id = name?.let(::GlyphID) ?: return ShapeEvidence(
                ShapeEvidence.Kind.NO_STROKE, null, emptySet(), emptyList(),
            )
            val path = requireNotNull(table.path(forID = id))
            val edges = GlyphGeometry.normalizedEdges(path.undirectedEdges)
            return ShapeEvidence(ShapeEvidence.Kind.UNIQUE_VISIBLE_SHAPE, id, edges, emptyList())
        }

        fun step(
            phase: GamePhase?,
            slots: Int? = null,
            highlight: Int? = null,
            glyph: String? = null,
            timerBar: Boolean = false,
            calibrated: Boolean = true,
            after: Double = 0.05,
            headerLag: Double = 0.0,
            from: CaptureSessionID? = null,
        ): List<TrackingEvent> {
            time += after
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
                    sessionID = from ?: session,
                    time = time,
                    header = phase?.let {
                        HeaderReading(it, it.rawValue, 0.9, sourceTime = time - headerLag)
                    },
                    slots = reading,
                    shape = shape(glyph),
                    hasValidCalibration = calibrated,
                    hasInputTimerBar = timerBar,
                ),
            )
            events += produced
            return produced
        }

        fun hold(
            count: Int,
            phase: GamePhase?,
            slots: Int? = null,
            highlight: Int? = null,
            glyph: String? = null,
            timerBar: Boolean = false,
        ) {
            repeat(count) { step(phase, slots, highlight, glyph, timerBar) }
        }

        val committed: List<String?>
            get() = tracker.slots.map { it.glyph?.name }
        val statuses: List<SlotObservation.Status>
            get() = tracker.slots.map { it.status }
        fun count(kind: TrackingEvent.Kind): Int = events.count { it.kind == kind }
    }

    @Test
    fun commandAndPlayerStrokesNeverEnterOrOverwriteTheSequence() {
        val f = Feed()
        f.hold(8, GamePhase.command, glyph = "see")
        assertEquals(TrackingState.COMMAND, f.tracker.state)
        assertTrue(f.tracker.slots.isEmpty())

        f.hold(6, GamePhase.presenting, slots = 2, highlight = 0, glyph = "see")
        f.hold(6, GamePhase.presenting, slots = 2, highlight = 1, glyph = "advance")
        f.hold(2, GamePhase.input, slots = 2, highlight = 0, timerBar = true)
        val frozen = f.committed
        assertEquals(listOf("see", "advance"), frozen)
        f.hold(10, GamePhase.input, slots = 2, highlight = 0, glyph = "truth")
        f.hold(10, GamePhase.input, slots = 2, highlight = 1, glyph = "all")
        assertEquals(frozen, f.committed)
        assertTrue(f.tracker.isFrozen)
        assertEquals(1, f.tracker.inputSlotIndex)
    }

    @Test
    fun stableShapeWaitsForBoundaryAndRepeatedGlyphsKeepTheirSlots() {
        val provisional = Feed()
        provisional.hold(10, GamePhase.presenting, slots = 2, highlight = 0, glyph = "see")
        assertEquals(SlotObservation.Status.OBSERVING, provisional.statuses[0])
        assertTrue(provisional.tracker.confirmedPrefix.isEmpty())
        assertEquals(0, provisional.count(TrackingEvent.Kind.SLOT_COMMITTED))

        val repeated = Feed()
        val names = listOf("see", "advance", "see")
        names.forEachIndexed { index, name ->
            repeated.hold(6, GamePhase.presenting, slots = 3, highlight = index, glyph = name)
        }
        repeated.hold(2, GamePhase.input, slots = 3, highlight = 0, timerBar = true)
        assertEquals(names, repeated.committed)
        assertEquals(names.map(::GlyphID), repeated.tracker.confirmedPrefix)
    }

    @Test
    fun missingSkippedAndLateJoinedSlotsAreNeverCompacted() {
        val f = Feed()
        f.hold(6, GamePhase.presenting, slots = 4, highlight = 0, glyph = "see")
        f.hold(6, GamePhase.presenting, slots = 4, highlight = 1)
        f.hold(6, GamePhase.presenting, slots = 4, highlight = 2, glyph = "advance")
        f.hold(6, GamePhase.presenting, slots = 4, highlight = 3, glyph = "all")
        f.hold(2, GamePhase.input, slots = 4, highlight = 0, timerBar = true)
        assertEquals(listOf("see", null, "advance", "all"), f.committed)
        assertEquals(
            listOf(
                SlotObservation.Status.CONFIRMED,
                SlotObservation.Status.MISSING,
                SlotObservation.Status.CONFIRMED,
                SlotObservation.Status.CONFIRMED,
            ),
            f.statuses,
        )
        assertEquals(listOf(GlyphID("see")), f.tracker.confirmedPrefix)

        val late = Feed()
        late.hold(6, GamePhase.presenting, slots = 4, highlight = 2, glyph = "advance")
        late.hold(6, GamePhase.presenting, slots = 4, highlight = 3, glyph = "all")
        late.hold(2, GamePhase.input, slots = 4, highlight = 0, timerBar = true)
        assertEquals(listOf(null, null, "advance", "all"), late.committed)
        assertTrue(late.tracker.confirmedPrefix.isEmpty())

        val skipped = Feed()
        skipped.hold(6, GamePhase.presenting, slots = 4, highlight = 0, glyph = "see")
        skipped.hold(6, GamePhase.presenting, slots = 4, highlight = 3, glyph = "all")
        assertEquals(SlotObservation.Status.MISSING, skipped.statuses[1])
        assertEquals(SlotObservation.Status.MISSING, skipped.statuses[2])
    }

    @Test
    fun nestedStableShapesChooseTheLargestButUnrelatedShapesAbstain() {
        val nested = Feed()
        nested.hold(3, GamePhase.presenting, slots = 2, highlight = 0, glyph = "see")
        nested.hold(3, GamePhase.presenting, slots = 2, highlight = 0, glyph = "advance")
        nested.hold(2, GamePhase.presenting, slots = 2, highlight = 1)
        assertEquals("advance", nested.committed[0])
        assertEquals(listOf(GlyphID("see")), nested.tracker.slots[0].alternatives)

        val unrelated = Feed()
        unrelated.hold(4, GamePhase.presenting, slots = 2, highlight = 0, glyph = "see")
        unrelated.hold(4, GamePhase.presenting, slots = 2, highlight = 0, glyph = "perspective")
        unrelated.hold(2, GamePhase.presenting, slots = 2, highlight = 1)
        assertEquals(SlotObservation.Status.UNCERTAIN, unrelated.statuses[0])
        assertNull(unrelated.tracker.slots[0].glyph)
        assertEquals(listOf(GlyphID("perspective"), GlyphID("see")), unrelated.tracker.slots[0].alternatives)
        assertTrue(unrelated.tracker.confirmedPrefix.isEmpty())
    }

    @Test
    fun slotCountNeedsTwoVotesAndQualifiedDisagreementIsAConflict() {
        val f = Feed()
        f.step(GamePhase.presenting, slots = 2, highlight = 0)
        assertNull(f.tracker.expectedSlotCount)
        f.step(GamePhase.presenting, slots = 2, highlight = 0)
        assertEquals(2, f.tracker.expectedSlotCount)
        f.hold(4, GamePhase.presenting, slots = 3, highlight = 0)
        assertTrue(f.tracker.slotCountConflict)
        assertNull(f.tracker.expectedSlotCount)
        assertEquals(1, f.count(TrackingEvent.Kind.SLOT_COUNT_CONFLICT))
    }

    @Test
    fun unreadableHeaderIsRetainedBrieflyThenInterruptedWithoutLosingCommittedSlots() {
        val f = Feed()
        f.hold(6, GamePhase.presenting, slots = 2, highlight = 0, glyph = "see")
        f.hold(2, GamePhase.presenting, slots = 2, highlight = 1)
        assertEquals("see", f.committed[0])
        f.hold(3, null, slots = 2, highlight = 1, glyph = "advance")
        assertEquals(TrackingState.PRESENTING, f.tracker.state)
        assertEquals(PhaseSource.RETAINED, f.tracker.phaseSource)
        f.step(null, slots = 2, highlight = 1, glyph = "advance", after = 0.8)
        assertEquals(TrackingState.INTERRUPTED, f.tracker.state)
        assertEquals("see", f.committed[0])
        val hack = f.tracker.hackID
        f.hold(6, GamePhase.presenting, slots = 2, highlight = 1, glyph = "advance")
        f.hold(2, GamePhase.input, slots = 2, highlight = 0, timerBar = true)
        assertEquals(hack, f.tracker.hackID)
        assertEquals(listOf("see", "advance"), f.committed)
    }

    @Test
    fun currentTimerBeatsLaggingHeaderAndCannotPromotePartialKnownSlot() {
        val stale = Feed()
        stale.hold(6, GamePhase.presenting, slots = 2, highlight = 0, glyph = "see")
        stale.step(
            GamePhase.presenting,
            slots = 2,
            highlight = 0,
            glyph = "perspective",
            timerBar = true,
            headerLag = 0.3,
        )
        assertEquals(TrackingState.INPUT, stale.tracker.state)
        assertEquals(PhaseSource.INPUT_TIMER, stale.tracker.phaseSource)
        assertEquals(listOf("see", null), stale.committed)

        val partial = Feed()
        partial.hold(3, GamePhase.presenting, slots = 2, highlight = 0, glyph = "see")
        partial.step(null, glyph = "advance", timerBar = true)
        assertEquals(TrackingState.INPUT, partial.tracker.state)
        assertEquals(SlotObservation.Status.INFERRED, partial.statuses[0])
        assertTrue(partial.tracker.confirmedPrefix.isEmpty())
        assertEquals(0, partial.count(TrackingEvent.Kind.SLOT_COMMITTED))
    }

    @Test
    fun indicatorPreludeSupportsHeaderCoveredPresentationAndLastRelease() {
        val f = Feed()
        f.hold(3, null, slots = 2)
        f.hold(6, null, slots = 2, highlight = 0, glyph = "see")
        f.hold(2, null, slots = 2, highlight = 0)
        f.hold(6, null, slots = 2, highlight = 1, glyph = "advance")
        f.hold(2, null, slots = 2, highlight = 1)
        f.hold(12, null, slots = 2)
        assertEquals(TrackingState.PRESENTING, f.tracker.state)
        assertEquals(listOf("see", "advance"), f.committed)
        f.hold(3, null, slots = 2, timerBar = true)
        f.hold(3, null, slots = 2, highlight = 0, timerBar = true)
        assertEquals(TrackingState.INPUT, f.tracker.state)
        assertEquals(1, f.tracker.hackID)
        assertEquals(0, f.tracker.inputSlotIndex)
        assertEquals(listOf("see", "advance"), f.committed)
    }

    @Test
    fun currentPixelProofRequiresTheRealPreludeAndSurvivesHeaderExpiry() {
        val headerOnly = Feed()
        headerOnly.hold(4, GamePhase.presenting, slots = 2, highlight = 0, glyph = "see")
        assertFalse(headerOnly.tracker.phaseSupportedByCurrentPixels)

        val anchored = Feed()
        anchored.hold(3, null, slots = 2)
        assertFalse(anchored.tracker.phaseSupportedByCurrentPixels)
        anchored.step(GamePhase.presenting, slots = 2, highlight = 0, glyph = "see")
        assertEquals(PhaseSource.HEADER_TEXT, anchored.tracker.phaseSource)
        assertTrue(anchored.tracker.phaseSupportedByCurrentPixels)
        val lastHeaderTime = anchored.tracker.lastPhaseTime
        anchored.hold(30, null, slots = 2, highlight = 0, glyph = "see")
        assertEquals(TrackingState.PRESENTING, anchored.tracker.state)
        assertTrue(anchored.tracker.phaseSupportedByCurrentPixels)
        assertEquals("Pixel proof does not renew the OCR source time", lastHeaderTime, anchored.tracker.lastPhaseTime)
    }

    @Test
    fun commandAndCompletedHeadersOverrideIndependentPresentationAndInputPixels() {
        for (phase in listOf(GamePhase.command, GamePhase.completed)) {
            for (input in listOf(false, true)) {
                val f = Feed()
                f.hold(3, null, slots = 2)
                f.hold(4, null, slots = 2, highlight = 0, glyph = "see")
                if (input) f.step(null, slots = 2, highlight = 0, timerBar = true)
                assertTrue(f.tracker.phaseSupportedByCurrentPixels)
                f.step(phase, slots = 2, highlight = 0, timerBar = true)
                assertEquals(
                    if (phase == GamePhase.command) TrackingState.COMMAND else TrackingState.COMPLETED,
                    f.tracker.state,
                )
                assertEquals(PhaseSource.HEADER_TEXT, f.tracker.phaseSource)
                assertFalse(f.tracker.phaseSupportedByCurrentPixels)
            }
        }
    }

    @Test
    fun losingSlotsCalibrationOrTimerImmediatelyRemovesIndependentPixelProof() {
        for (loseSlots in listOf(false, true)) {
            val f = Feed()
            f.hold(3, null, slots = 2)
            f.hold(4, null, slots = 2, highlight = 0, glyph = "see")
            assertTrue(f.tracker.phaseSupportedByCurrentPixels)
            f.step(null, slots = if (loseSlots) null else 2, highlight = 0, calibrated = loseSlots)
            assertFalse(f.tracker.phaseSupportedByCurrentPixels)
        }
        val f = Feed()
        f.hold(4, GamePhase.presenting, slots = 2, highlight = 0, glyph = "see")
        f.step(null, slots = 2, highlight = 0, timerBar = true)
        assertTrue(f.tracker.phaseSupportedByCurrentPixels)
        val frozen = f.tracker.slots
        f.step(null, slots = 2, highlight = 0)
        assertFalse(f.tracker.phaseSupportedByCurrentPixels)
        assertEquals(TrackingState.INPUT, f.tracker.state)
        assertEquals(frozen, f.tracker.slots)
        f.step(null, slots = 2, highlight = 0, timerBar = true)
        assertTrue(f.tracker.phaseSupportedByCurrentPixels)
        f.tracker.invalidatePhase()
        assertFalse(f.tracker.phaseSupportedByCurrentPixels)
    }

    @Test
    fun freshPreludeAfterLongStripAbsenceStartsSameLengthHackOnlyOnce() {
        val f = Feed()
        f.hold(3, null, slots = 2)
        f.hold(5, null, slots = 2, highlight = 0, glyph = "see")
        f.hold(5, null, slots = 2, highlight = 1, glyph = "advance")
        f.hold(3, null, slots = 2)
        assertEquals(listOf("see", "advance"), f.committed)
        f.hold(60, null)
        assertEquals(TrackingState.INTERRUPTED, f.tracker.state)
        f.hold(3, null, slots = 2)
        f.step(null, slots = 2, highlight = 0, glyph = "truth")
        assertEquals(2, f.tracker.hackID)
        assertEquals(listOf(null, null), f.committed)
        f.hold(4, null, slots = 2, highlight = 0, glyph = "truth")
        f.hold(4, null, slots = 2, highlight = 1, glyph = "perspective")
        f.hold(2, null, slots = 2)
        assertEquals(listOf("truth", "perspective"), f.committed)
        assertEquals(2, f.count(TrackingEvent.Kind.HACK_STARTED))
    }

    @Test
    fun releasedTailFlickerDoesNotStartAnotherHack() {
        val f = Feed()
        f.hold(3, null, slots = 2)
        f.hold(6, null, slots = 2, highlight = 0, glyph = "see")
        f.hold(6, null, slots = 2, highlight = 1, glyph = "advance")
        f.hold(4, null, slots = 2)
        assertEquals(listOf("see", "advance"), f.committed)
        f.step(null)
        f.hold(6, null, slots = 2)
        assertEquals(TrackingState.INTERRUPTED, f.tracker.state)
        f.hold(4, null, slots = 2, highlight = 0, glyph = "truth")
        f.hold(4, null, slots = 2, highlight = 1, glyph = "perspective")
        assertEquals(1, f.tracker.hackID)
        assertEquals(listOf("see", "advance"), f.committed)
    }

    @Test
    fun coveredIndicatorsCreateDisplayOnlyInferencesAndRestoreToVerifiedPositions() {
        val f = Feed()
        f.hold(4, GamePhase.presenting, glyph = "see")
        f.hold(12, GamePhase.presenting)
        assertEquals(listOf("see"), f.committed)
        assertEquals(listOf(SlotObservation.Status.INFERRED), f.statuses)
        assertTrue(f.tracker.indicatorsObstructed)
        assertNull(f.tracker.expectedSlotCount)
        assertTrue(f.tracker.confirmedPrefix.isEmpty())
        val inferredSnapshot = PresentationSnapshotFactory(
            SequencePredictor(
                SequenceTable(listOf(listOf(GlyphID("see"), GlyphID("advance")))),
            ),
        ).snapshot(f.tracker, observation = null, arrivalUptime = f.time, sourceTime = f.time)
        assertEquals(PredictionState.UNOBSERVED, inferredSnapshot.outlook.state)
        assertTrue(inferredSnapshot.outlook.observed.isEmpty())
        assertEquals(listOf(TileStyle.Inferred(GlyphID("see"))), inferredSnapshot.tileStyles)
        assertNull(inferredSnapshot.upcomingGuidance())

        f.hold(3, GamePhase.presenting, glyph = "advance")
        f.hold(3, GamePhase.presenting, slots = 4, highlight = 2, glyph = "all")
        assertFalse(f.tracker.indicatorsObstructed)
        f.step(GamePhase.presenting, slots = 4, highlight = 3)
        assertEquals(
            listOf(
                SlotObservation.Status.MISSING,
                SlotObservation.Status.MISSING,
                SlotObservation.Status.CONFIRMED,
                SlotObservation.Status.OBSERVING,
            ),
            f.statuses,
        )
        assertEquals(listOf(null, null, "all", null), f.committed)
        assertTrue(f.tracker.confirmedPrefix.isEmpty())
    }

    @Test
    fun fallbackUsesSustainedBlanksAndKeepsIdenticalConsecutiveShapesSeparate() {
        val f = Feed()
        repeat(2) {
            f.hold(3, GamePhase.presenting, glyph = "see")
            f.hold(3, GamePhase.presenting, glyph = "advance")
            f.hold(2, GamePhase.presenting)
        }
        assertEquals(listOf("advance", "advance"), f.committed)
        assertEquals(listOf(SlotObservation.Status.INFERRED, SlotObservation.Status.INFERRED), f.statuses)
        assertTrue(f.tracker.confirmedPrefix.isEmpty())

        val brief = Feed()
        brief.hold(3, GamePhase.presenting, glyph = "see")
        brief.step(GamePhase.presenting)
        brief.hold(3, GamePhase.presenting, glyph = "advance")
        assertEquals(1, brief.tracker.slots.size)
        assertEquals(0, brief.count(TrackingEvent.Kind.SLOT_INFERRED))
        brief.hold(3, GamePhase.presenting, glyph = "perspective")
        brief.hold(4, GamePhase.presenting)
        assertEquals(listOf(SlotObservation.Status.UNCERTAIN), brief.statuses)
    }

    @Test
    fun frameGapsRewindsAndForeignSessionsInvalidateOnlyWhatTheyOwn() {
        val gapped = Feed()
        gapped.hold(6, GamePhase.presenting, slots = 2, highlight = 0, glyph = "see")
        gapped.step(GamePhase.presenting, slots = 2, highlight = 0, glyph = "see", after = 3.0)
        assertEquals(0, gapped.count(TrackingEvent.Kind.SLOT_COMMITTED))
        assertTrue(gapped.count(TrackingEvent.Kind.EVIDENCE_INVALIDATED) >= 1)
        gapped.hold(4, GamePhase.presenting, slots = 2, highlight = 0, glyph = "see")
        gapped.hold(2, GamePhase.presenting, slots = 2, highlight = 1)
        assertEquals("see", gapped.committed[0])
        assertEquals(5, gapped.tracker.slots[0].sampleCount)

        val foreign = Feed()
        foreign.hold(4, GamePhase.presenting, slots = 2, highlight = 0, glyph = "see")
        val revision = foreign.tracker.revision
        val events = foreign.step(
            GamePhase.presenting,
            slots = 2,
            highlight = 1,
            glyph = "advance",
            from = CaptureSessionID(99UL),
        )
        assertEquals(listOf(TrackingEvent.Kind.STALE_EVIDENCE_REJECTED), events.map { it.kind })
        assertEquals(revision, foreign.tracker.revision)
        assertTrue(foreign.tracker.confirmedPrefix.isEmpty())

        val rewound = Feed()
        rewound.hold(6, GamePhase.presenting, slots = 2, highlight = 0, glyph = "see")
        rewound.hold(2, GamePhase.presenting, slots = 2, highlight = 1)
        val committed = rewound.tracker.slots.filter { it.status == SlotObservation.Status.CONFIRMED }
        val hack = rewound.tracker.hackID
        rewound.time = 0.95
        rewound.step(null, calibrated = false)
        assertNull(rewound.tracker.lastPhaseTime)
        assertEquals(TrackingState.INTERRUPTED, rewound.tracker.state)
        assertEquals(hack, rewound.tracker.hackID)
        assertEquals(committed, rewound.tracker.slots.filter { it.status == SlotObservation.Status.CONFIRMED })
    }

    @Test
    fun explicitPhaseInvalidationPreservesCommittedSequenceAndHackIdentity() {
        val f = Feed()
        f.hold(6, GamePhase.presenting, slots = 2, highlight = 0, glyph = "see")
        f.hold(2, GamePhase.presenting, slots = 2, highlight = 1)
        val slots = f.tracker.slots
        val hack = f.tracker.hackID
        val revision = f.tracker.revision
        f.tracker.invalidatePhase()
        assertEquals(slots, f.tracker.slots)
        assertEquals(hack, f.tracker.hackID)
        assertNull(f.tracker.lastPhaseTime)
        assertEquals(PhaseSource.NONE, f.tracker.phaseSource)
        assertEquals(TrackingState.INTERRUPTED, f.tracker.state)
        assertTrue(f.tracker.revision > revision)
    }

    @Test
    fun missedPresentationCannotInheritPreviousHacksAnswer() {
        val f = Feed()
        f.hold(6, GamePhase.presenting, slots = 2, highlight = 0, glyph = "see")
        f.hold(6, GamePhase.presenting, slots = 2, highlight = 1, glyph = "advance")
        f.hold(3, GamePhase.input, slots = 2, highlight = 0, timerBar = true)
        f.hold(3, GamePhase.completed)
        assertEquals(listOf("see", "advance"), f.committed)
        f.hold(3, GamePhase.command)
        f.hold(3, GamePhase.input, slots = 4, highlight = 0, timerBar = true)
        assertTrue(f.committed.isEmpty())
    }

    @Test
    fun inputHighlightHasHysteresisAndMovesRevisionWithoutCommitting() {
        val f = Feed()
        for (index in 0 until 3) {
            f.hold(6, GamePhase.presenting, slots = 3, highlight = index, glyph = "see")
        }
        f.hold(4, GamePhase.input, slots = 3, highlight = 1, timerBar = true)
        assertEquals(1, f.tracker.inputSlotIndex)
        val settled = f.tracker.revision
        val events = f.step(GamePhase.input, slots = 3, highlight = null, timerBar = true)
        assertTrue(events.isEmpty())
        assertEquals(1, f.tracker.inputSlotIndex)
        assertEquals(settled, f.tracker.revision)
        f.hold(5, GamePhase.input, slots = 3, highlight = null, timerBar = true)
        assertEquals(1, f.tracker.inputSlotIndex)
        f.step(GamePhase.input, slots = 3, highlight = 2, timerBar = true)
        assertEquals(2, f.tracker.inputSlotIndex)
        assertTrue(f.tracker.revision > settled)
        val beforeTimeout = f.tracker.revision
        f.hold(10, GamePhase.input, slots = 3, highlight = null, timerBar = true)
        assertNull(f.tracker.inputSlotIndex)
        assertNotEquals(beforeTimeout, f.tracker.revision)
    }

    @Test
    fun snapshotPredictsOnlyConfirmedPrefixAndWithdrawsStaleGuidance() {
        val f = Feed()
        val names = listOf("see", "advance", "all", "clear_all")
        names.forEachIndexed { index, name ->
            f.hold(6, GamePhase.presenting, slots = 5, highlight = index, glyph = name)
        }
        f.hold(3, GamePhase.input, slots = 5, highlight = 4, timerBar = true)
        val sequence = names.map(::GlyphID) + GlyphID("truth")
        val factory = PresentationSnapshotFactory(SequencePredictor(SequenceTable(listOf(sequence))))
        val snapshot = factory.snapshot(f.tracker, observation = null, arrivalUptime = 200.0, sourceTime = f.time)
        assertEquals(PredictionState.RESOLVED, snapshot.outlook.state)
        assertEquals(names.map(::GlyphID), snapshot.outlook.observed)
        assertEquals(listOf(GlyphID("truth")), snapshot.outlook.predictedSuffix)
        assertEquals(TileStyle.Predicted(GlyphID("truth")), snapshot.tileStyles[4])
        val guidance = requireNotNull(snapshot.upcomingGuidance())
        assertEquals(4, guidance.firstSlotIndex)
        assertEquals(listOf(TileStyle.Predicted(GlyphID("truth"))), guidance.styles)
        assertTrue(guidance.styles.single().isDashed)

        val stale = factory.snapshot(f.tracker, observation = null, arrivalUptime = 200.0, sourceTime = f.time + 1.0)
        assertNull(stale.upcomingGuidance())
    }

    @Test
    fun redoKeepsFrozenSequenceAndLastSlotGuidanceUntilCompletion() {
        val f = Feed()
        val names = listOf("see", "advance", "truth", "all", "destiny")
        names.forEachIndexed { index, name ->
            f.hold(4, GamePhase.presenting, slots = 5, highlight = index, glyph = name)
        }
        f.step(null, slots = 5, highlight = 0, timerBar = true)
        val frozen = f.tracker.slots
        val hackID = f.tracker.hackID

        // The iOS field report redraws slot 3 after reaching slot 4, then advances to slot 5.
        for (index in listOf(0, 1, 2, 3, 2, 3, 4)) {
            repeat(20) {
                f.step(null, slots = 5, highlight = index, glyph = names[index], timerBar = true)
                assertEquals(TrackingState.INPUT, f.tracker.state)
                assertTrue(f.tracker.phaseSupportedByCurrentPixels)
                assertEquals(index, f.tracker.inputSlotIndex)
                assertEquals(frozen, f.tracker.slots)
                assertEquals(hackID, f.tracker.hackID)
            }
        }
        val factory = PresentationSnapshotFactory()
        val snapshot = factory.snapshot(f.tracker, null, f.time, f.time)
        val guidance = requireNotNull(snapshot.upcomingGuidance())
        assertEquals(4, guidance.firstSlotIndex)
        assertEquals(listOf(GlyphID("destiny")), guidance.styles.map { it.glyph })

        f.step(GamePhase.completed)
        assertNull(f.tracker.inputSlotIndex)
        assertNull(factory.snapshot(f.tracker, null, f.time, f.time).upcomingGuidance())
    }

    @Test
    fun releaseRequiresLastSlotClearArrayAndStableShape() {
        val drawing = Feed()
        drawing.hold(6, GamePhase.presenting, slots = 2, highlight = 0, glyph = "see")
        drawing.hold(3, GamePhase.presenting, slots = 2, highlight = 1, glyph = "truth")
        drawing.hold(3, GamePhase.presenting, slots = 2, glyph = "truth")
        assertEquals(1, drawing.tracker.currentSlotIndex)
        assertEquals(SlotObservation.Status.OBSERVING, drawing.statuses[1])

        val early = Feed()
        early.hold(6, GamePhase.presenting, slots = 3, highlight = 0, glyph = "see")
        early.hold(4, GamePhase.presenting, slots = 3)
        assertEquals(0, early.tracker.currentSlotIndex)
        assertTrue(early.tracker.confirmedPrefix.isEmpty())

        val unstable = Feed()
        unstable.hold(6, GamePhase.presenting, slots = 2, highlight = 0, glyph = "see")
        unstable.step(GamePhase.presenting, slots = 2, highlight = 1, glyph = "truth")
        unstable.hold(4, GamePhase.presenting, slots = 2)
        assertEquals(1, unstable.tracker.currentSlotIndex)
        assertEquals(listOf(GlyphID("see")), unstable.tracker.confirmedPrefix)
    }

    @Test
    fun headerGraceExpiresFromItsSourceFrameRatherThanItsArrival() {
        val current = Feed()
        current.step(GamePhase.presenting, slots = 2, highlight = 0)
        current.hold(6, null, slots = 2, highlight = 0, glyph = "see")
        assertEquals(TrackingState.PRESENTING, current.tracker.state)

        val delayed = Feed()
        delayed.step(GamePhase.presenting, slots = 2, highlight = 0, headerLag = 0.6)
        delayed.hold(6, null, slots = 2, highlight = 0, glyph = "see")
        assertEquals(TrackingState.INTERRUPTED, delayed.tracker.state)
        assertTrue(delayed.tracker.confirmedPrefix.isEmpty())
    }

    @Test
    fun predictionCacheRetainsHolePositionsAndClearsForTheNextHack() {
        val f = Feed()
        val sequence = listOf("see", "truth", "advance", "all").map(::GlyphID)
        val factory = PresentationSnapshotFactory(SequencePredictor(SequenceTable(listOf(sequence))))
        f.hold(4, GamePhase.presenting, slots = 4, highlight = 0, glyph = "see")
        f.hold(4, GamePhase.presenting, slots = 4, highlight = 1)
        val firstPrediction = factory.snapshot(f.tracker, null, f.time, f.time)
        assertEquals(listOf(GlyphID("see")), firstPrediction.outlook.observed)
        assertEquals(sequence.drop(1), firstPrediction.outlook.predictedSuffix)

        f.hold(4, GamePhase.presenting, slots = 4, highlight = 2, glyph = "advance")
        f.hold(4, GamePhase.presenting, slots = 4, highlight = 3, glyph = "all")
        f.step(GamePhase.input, slots = 4, highlight = 0, timerBar = true)
        val withHole = factory.snapshot(f.tracker, null, f.time, f.time)
        assertEquals(SlotObservation.Status.MISSING, withHole.slots[1].status)
        assertEquals(listOf(GlyphID("see")), withHole.outlook.observed)
        assertEquals(sequence.drop(1), withHole.outlook.predictedSuffix)
        assertEquals(listOf("see", null, "advance", "all"), withHole.slots.map { it.glyph?.name })

        f.step(GamePhase.command)
        val nextHack = factory.snapshot(f.tracker, null, f.time, f.time)
        assertEquals(PredictionState.UNOBSERVED, nextHack.outlook.state)
        assertTrue(nextHack.outlook.observed.isEmpty())
        assertTrue(nextHack.outlook.predictedSuffix.isEmpty())
        assertTrue(nextHack.slots.isEmpty())
        assertEquals(listOf("see", null, "advance", "all"), withHole.slots.map { it.glyph?.name })
    }

    @Test
    fun releaseAndIndicatorPoliciesCanDisableOnlyTheirOwnRules() {
        val noRelease = Feed(PresentationTracker.Policy(closesLastSlotOnRelease = false))
        noRelease.hold(3, null, slots = 2)
        noRelease.hold(6, null, slots = 2, highlight = 0, glyph = "see")
        noRelease.hold(6, null, slots = 2, highlight = 1, glyph = "advance")
        noRelease.hold(12, null, slots = 2)
        assertEquals(listOf("see", null), noRelease.committed)
        noRelease.hold(3, null, slots = 2, timerBar = true)
        assertEquals(listOf("see", "advance"), noRelease.committed)

        val noIndicators = Feed(PresentationTracker.Policy(entersPresentationFromIndicators = false))
        noIndicators.hold(3, null, slots = 2)
        noIndicators.hold(6, null, slots = 2, highlight = 0, glyph = "see")
        assertEquals(TrackingState.INACTIVE, noIndicators.tracker.state)
        assertEquals(0, noIndicators.tracker.hackID)
        assertTrue(noIndicators.tracker.slots.isEmpty())
    }

    @Test
    fun resetDropsSequenceAndTimingButDoesNotInventACompletedHack() {
        val f = Feed()
        f.hold(6, GamePhase.presenting, slots = 2, highlight = 0, glyph = "see")
        f.hold(2, GamePhase.presenting, slots = 2, highlight = 1)
        f.tracker.reset()
        assertEquals(TrackingState.INACTIVE, f.tracker.state)
        assertTrue(f.tracker.slots.isEmpty())
        assertTrue(f.tracker.confirmedPrefix.isEmpty())
        assertNull(f.tracker.expectedSlotCount)
        assertNull(f.tracker.lastEvidenceTime)
        assertFalse(f.tracker.isFrozen)
    }
}
