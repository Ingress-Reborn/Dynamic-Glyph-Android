package org.szres.glyphlens.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotPresentationTests {
    private fun glyphs(vararg names: String): List<GlyphID> = names.map(::GlyphID)

    private fun confirmed(vararg names: String): List<SlotObservation> = names.mapIndexed { index, name ->
        SlotObservation(index, SlotObservation.Status.CONFIRMED, GlyphID(name))
    }

    private fun fieldSequences(): SequenceTable = SequenceTable(
        listOf(
            glyphs("change", "perspective"),
            glyphs("change", "perspective", "technology"),
            glyphs("change", "perspective", "attack"),
            glyphs("change", "perspective", "begin", "new"),
            glyphs("change", "perspective", "begin", "new", "resistance"),
        ),
    )

    private fun snapshot(
        state: TrackingState = TrackingState.INPUT,
        slots: List<SlotObservation>,
        expected: Int? = slots.size,
        inputSlotIndex: Int? = 0,
        inputSlotObservedTime: Double? = 100.0,
        sourceFrameTime: Double = 100.0,
        indicatorsObstructed: Boolean = false,
        slotCountConflict: Boolean = false,
        liveness: CaptureLiveness = CaptureLiveness.LIVE,
        acceptance: AcceptanceMode = AcceptanceMode.TrackingSequence,
        outlook: SequenceOutlook = SequenceOutlook.unavailable,
        currentSlotIndex: Int? = null,
        provisionalGlyph: GlyphID? = null,
        provisionalExtensionCount: Int = 0,
    ): GlyphDisplaySnapshot = GlyphDisplaySnapshot(
        captureSessionID = CaptureSessionID(1UL),
        hackID = 1,
        revision = 1,
        liveness = liveness,
        state = state,
        phaseSource = PhaseSource.INPUT_TIMER,
        acceptance = acceptance,
        sourceFrameTime = sourceFrameTime,
        evidenceArrivalUptime = 10.0,
        expectedSlotCount = expected,
        slots = slots,
        currentSlotIndex = currentSlotIndex,
        inputSlotIndex = inputSlotIndex,
        inputSlotObservedTime = inputSlotObservedTime,
        indicatorsObstructed = indicatorsObstructed,
        slotCountConflict = slotCountConflict,
        isFrozen = true,
        provisionalGlyph = provisionalGlyph,
        provisionalExtensionCount = provisionalExtensionCount,
        outlook = outlook,
        calibrationSummary = "11 rings",
    )

    @Test
    fun guidanceWindowStartsAtHighlightedIndicator() {
        val model = snapshot(
            slots = confirmed("change", "perspective", "begin", "new"),
            inputSlotIndex = 1,
        )
        val guidance = requireNotNull(model.upcomingGuidance())
        assertEquals(1, guidance.firstSlotIndex)
        assertEquals(listOf("perspective", "begin"), guidance.styles.mapNotNull { it.glyph }.map { it.name })
        assertTrue(guidance.styles.none { it.isDashed })
    }

    @Test
    fun unreadIndicatorMeansNoGuidance() {
        assertNull(snapshot(slots = confirmed("change", "perspective"), inputSlotIndex = null).upcomingGuidance())
    }

    @Test
    fun guidanceExistsOnlyDuringInput() {
        for (state in listOf(
            TrackingState.PRESENTING,
            TrackingState.COMMAND,
            TrackingState.COMPLETED,
            TrackingState.INTERRUPTED,
            TrackingState.INACTIVE,
        )) {
            assertNull(snapshot(state = state, slots = confirmed("change", "perspective")).upcomingGuidance())
        }
    }

    @Test
    fun staleIndicatorIsNotGuidance() {
        val slots = confirmed("change", "perspective")
        assertNotNull(snapshot(slots = slots, inputSlotObservedTime = 99.25).upcomingGuidance())
        assertNotNull(snapshot(slots = slots, inputSlotObservedTime = 99.5).upcomingGuidance())
        assertNull(snapshot(slots = slots, inputSlotObservedTime = 99.0).upcomingGuidance())
    }

    @Test
    fun coveredOrProvisionalEvidenceWithdrawsInstruction() {
        val slots = confirmed("change", "perspective")
        assertNull(snapshot(slots = slots, indicatorsObstructed = true).upcomingGuidance())
        assertNull(
            snapshot(slots = slots, acceptance = AcceptanceMode.ProvisionalOnly("capture not started"))
                .upcomingGuidance(),
        )
    }

    @Test
    fun conflictStoppedCaptureImpossibleIndexAndInferredSlotsRefuse() {
        val slots = confirmed("change", "perspective")
        assertNull(snapshot(slots = slots, slotCountConflict = true).upcomingGuidance())
        assertNull(snapshot(slots = slots, liveness = CaptureLiveness.STOPPED).upcomingGuidance())
        assertNull(snapshot(slots = slots, inputSlotIndex = 7).upcomingGuidance())
        assertNull(snapshot(slots = slots, inputSlotIndex = -1).upcomingGuidance())
        val inferred = listOf(SlotObservation(0, SlotObservation.Status.INFERRED, GlyphID("see")))
        assertNull(snapshot(slots = inferred).upcomingGuidance())
    }

    @Test
    fun lastSlotHasNothingAfterIt() {
        val model = snapshot(slots = confirmed("change", "perspective"), inputSlotIndex = 1)
        assertEquals(1, model.upcomingGuidance()?.styles?.size)
    }

    @Test
    fun predictedContinuationIsOfferedAsDashedPrediction() {
        val observed = glyphs("change", "perspective")
        val outlook = SequencePredictor(fieldSequences()).outlook(observed, expectedLength = 4)
        val model = snapshot(
            slots = confirmed("change", "perspective") +
                listOf(SlotObservation(2), SlotObservation(3)),
            expected = 4,
            inputSlotIndex = 2,
            outlook = outlook,
        )
        val guidance = requireNotNull(model.upcomingGuidance())
        assertEquals(listOf("begin", "new"), guidance.styles.mapNotNull { it.glyph }.map { it.name })
        assertTrue(guidance.styles.all { it.isDashed })
        assertTrue(guidance.styles.all { model.noteFor(it) == "Predicted" })
    }

    @Test
    fun blankWindowIsRefusedButMissingBesideKnownIsKept() {
        val blank = snapshot(slots = listOf(SlotObservation(0), SlotObservation(1)))
        assertNull(blank.upcomingGuidance())

        val partial = snapshot(
            slots = listOf(
                SlotObservation(0, SlotObservation.Status.MISSING),
                SlotObservation(1, SlotObservation.Status.CONFIRMED, GlyphID("new")),
            ),
        )
        val guidance = requireNotNull(partial.upcomingGuidance())
        assertEquals(2, guidance.styles.size)
        assertEquals(TileStyle.Missing, guidance.styles.first())
    }

    @Test
    fun guidanceIsAWindowIntoTheSameTileStyles() {
        val model = snapshot(slots = confirmed("change", "perspective", "begin"), inputSlotIndex = 1)
        assertEquals(model.tileStyles.subList(1, 3), model.upcomingGuidance()?.styles)
    }

    @Test
    fun observingAndInferredStylesKeepTheirEvidenceMeaning() {
        val observing = snapshot(
            state = TrackingState.PRESENTING,
            slots = listOf(SlotObservation(0, SlotObservation.Status.OBSERVING)),
            currentSlotIndex = 0,
            provisionalGlyph = GlyphID("see"),
            provisionalExtensionCount = 2,
        )
        assertEquals(listOf(TileStyle.Observing(GlyphID("see"))), observing.tileStyles)
        assertEquals("Observing… +2 longer", observing.noteFor(observing.tileStyles.single()))

        val provisional = snapshot(
            state = TrackingState.PRESENTING,
            slots = listOf(SlotObservation(0, SlotObservation.Status.OBSERVING)),
            acceptance = AcceptanceMode.ProvisionalOnly("starting"),
            currentSlotIndex = 0,
            provisionalGlyph = GlyphID("see"),
        )
        assertEquals(listOf(TileStyle.Pending), provisional.tileStyles)

        val inferred = snapshot(
            slots = listOf(SlotObservation(0, SlotObservation.Status.INFERRED, GlyphID("see"))),
        )
        assertEquals(TileStyle.Inferred(GlyphID("see")), inferred.tileStyles.single())
        assertTrue(inferred.tileStyles.single().isDashed)
        assertEquals("Inferred · position unverified", inferred.noteFor(inferred.tileStyles.single()))
    }

    @Test
    fun alternativesAndMissingSlotsKeepTheirPositions() {
        val observed = glyphs("change", "perspective")
        val alternatives = SequencePredictor(fieldSequences()).outlook(observed, expectedLength = 3)
        val model = snapshot(
            slots = confirmed("change", "perspective") + SlotObservation(2),
            expected = 3,
            inputSlotIndex = 2,
            outlook = alternatives,
        )
        assertEquals(TileStyle.Alternatives(2), model.tileStyles[2])
        assertEquals("2 options", model.tileStyles[2].caption)
        assertNotNull(model.upcomingGuidance())

        val missed = snapshot(
            slots = listOf(
                SlotObservation(0, SlotObservation.Status.CONFIRMED, GlyphID("change")),
                SlotObservation(1, SlotObservation.Status.MISSING),
                SlotObservation(2, SlotObservation.Status.CONFIRMED, GlyphID("begin")),
            ),
        )
        assertEquals(TileStyle.Missing, missed.tileStyles[1])
        assertEquals(TileStyle.Confirmed(GlyphID("begin")), missed.tileStyles[2])
    }

    @Test
    fun malformedObservedStatusesBecomeUncertainNotBlankGlyphs() {
        val model = snapshot(
            slots = listOf(
                SlotObservation(0, SlotObservation.Status.CONFIRMED),
                SlotObservation(1, SlotObservation.Status.INFERRED),
                SlotObservation(2, SlotObservation.Status.UNCERTAIN),
            ),
        )
        assertEquals(
            listOf(TileStyle.Uncertain, TileStyle.Uncertain, TileStyle.Uncertain),
            model.tileStyles,
        )
        assertEquals("Not settled", model.noteFor(TileStyle.Uncertain))
        assertEquals("No evidence", model.noteFor(TileStyle.Missing))
        assertNull(model.noteFor(TileStyle.Pending))
    }

    @Test
    fun settledStatusMatchesSwiftStateSet() {
        val expected = mapOf(
            SlotObservation.Status.PENDING to false,
            SlotObservation.Status.OBSERVING to false,
            SlotObservation.Status.CONFIRMED to true,
            SlotObservation.Status.INFERRED to true,
            SlotObservation.Status.MISSING to true,
            SlotObservation.Status.UNCERTAIN to true,
        )
        for ((status, settled) in expected) {
            assertEquals(settled, SlotObservation(0, status).isSettled)
        }
        assertFalse(TileStyle.Pending.glyph != null)
    }
}
