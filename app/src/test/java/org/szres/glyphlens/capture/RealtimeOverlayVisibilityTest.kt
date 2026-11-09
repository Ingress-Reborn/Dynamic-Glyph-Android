package org.szres.glyphlens.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.szres.glyphlens.recognition.AcceptanceMode
import org.szres.glyphlens.recognition.CaptureLiveness
import org.szres.glyphlens.recognition.CaptureSessionID
import org.szres.glyphlens.recognition.GlyphDisplaySnapshot
import org.szres.glyphlens.recognition.GlyphID
import org.szres.glyphlens.recognition.PhaseSource
import org.szres.glyphlens.recognition.PredictionState
import org.szres.glyphlens.recognition.SequenceOutlook
import org.szres.glyphlens.recognition.SequencePredictor
import org.szres.glyphlens.recognition.SequenceTable
import org.szres.glyphlens.recognition.SlotObservation
import org.szres.glyphlens.recognition.TrackingState
import org.szres.glyphlens.recognition.outlook

class RealtimeOverlayVisibilityTest {
    private val sequence = listOf("question", "less", "forget", "all", "lie").map(::GlyphID)
    private val predictor = SequencePredictor(
        SequenceTable(listOf(sequence, listOf("question", "more", "seek", "all", "truth").map(::GlyphID))),
    )

    @Test
    fun hidingBeforePredictionKeepsTheCurrentRoundEligible() {
        val visibility = RealtimeOverlayVisibility()
        val incomplete = snapshot(confirmedCount = 1)
        val ready = snapshot()
        assertEquals(PredictionState.AMBIGUOUS, incomplete.outlook.state)
        assertEquals(PredictionState.RESOLVED, ready.outlook.state)
        assertEquals(sequence.drop(2), ready.outlook.predictedSuffix)

        visibility.show()
        visibility.hide(incomplete)

        assertFalse(visibility.wantsWindow)
        assertFalse(visibility.observe(incomplete))
        assertTrue(visibility.observe(ready))
        assertTrue(visibility.wantsWindow)
    }

    @Test
    fun aDismissedReadyRoundStaysHiddenUntilTheNextRoundIsReady() {
        val visibility = RealtimeOverlayVisibility()
        val firstRound = snapshot()

        assertTrue(visibility.observe(firstRound))
        visibility.hide(firstRound)
        repeat(3) {
            assertFalse(visibility.observe(firstRound))
            assertFalse(visibility.wantsWindow)
        }
        assertFalse(visibility.observe(snapshot(hackID = 2, confirmedCount = 1)))
        assertTrue(visibility.observe(snapshot(hackID = 2)))
        assertTrue(visibility.wantsWindow)
    }

    @Test
    fun readyEvidenceWhileVisibleDoesNotReopenAfterDismissal() {
        val visibility = RealtimeOverlayVisibility()
        val ready = snapshot()

        visibility.show()
        assertFalse(visibility.observe(ready))
        assertTrue(visibility.wantsWindow)
        visibility.hide(ready)

        assertFalse(visibility.observe(ready))
        assertFalse(visibility.wantsWindow)
    }

    @Test
    fun dismissingReadySnapshotConsumesItEvenBeforeTheNextObservation() {
        val visibility = RealtimeOverlayVisibility()
        visibility.show()
        visibility.hide(snapshot())

        assertFalse(visibility.observe(snapshot()))
        assertFalse(visibility.wantsWindow)
    }

    @Test
    fun manualShowRestoresTheWindowAndResetAllowsANewSession() {
        val visibility = RealtimeOverlayVisibility()
        assertTrue(visibility.observe(snapshot()))
        visibility.hide(snapshot())

        visibility.show()
        assertTrue(visibility.wantsWindow)
        visibility.hide(snapshot())
        assertFalse(visibility.wantsWindow)

        visibility.reset()
        assertFalse(visibility.wantsWindow)
        assertTrue(visibility.observe(snapshot()))
    }

    @Test
    fun unknownLengthAmbiguousAndUnavailablePredictionsStayHidden() {
        val cases = listOf(
            snapshot(expectedCount = null),
            snapshot(confirmedCount = 0),
            snapshot(confirmedCount = 1),
            snapshot(expectedCount = 4),
            snapshot(usesSequenceTable = false),
        )

        for (snapshot in cases) {
            val visibility = RealtimeOverlayVisibility()
            assertFalse("Unexpected popup for ${snapshot.outlook.state}", visibility.observe(snapshot))
            assertFalse(visibility.wantsWindow)
        }
    }

    @Test
    fun missingUncertainAndInferredSlotsDoNotProduceACompleteResult() {
        for (status in listOf(
            SlotObservation.Status.MISSING,
            SlotObservation.Status.UNCERTAIN,
            SlotObservation.Status.INFERRED,
        )) {
            val visibility = RealtimeOverlayVisibility()
            val incomplete = snapshot(slotOverrides = mapOf(2 to status))
            assertEquals(PredictionState.RESOLVED, incomplete.outlook.state)

            assertFalse("Unexpected popup with $status slot", visibility.observe(incomplete))
            assertFalse(visibility.wantsWindow)
            assertTrue(visibility.observe(snapshot()))
        }
    }

    @Test
    fun conflictsObstructionAndProvisionalEvidenceStayHidden() {
        val cases = listOf(
            snapshot(slotCountConflict = true),
            snapshot(indicatorsObstructed = true),
            snapshot(acceptance = AcceptanceMode.ProvisionalOnly("calibrating")),
            snapshot(hackID = 0),
            snapshot(liveness = CaptureLiveness.STARTING),
            snapshot(liveness = CaptureLiveness.INTERRUPTED),
            snapshot(liveness = CaptureLiveness.STOPPED),
        )

        for (snapshot in cases) {
            val visibility = RealtimeOverlayVisibility()
            assertFalse(visibility.observe(snapshot))
            assertFalse(visibility.wantsWindow)
        }
    }

    @Test
    fun onlyPresentationAndInputCanRevealAReadySequence() {
        for (state in TrackingState.entries) {
            val visibility = RealtimeOverlayVisibility()
            val expected = state == TrackingState.PRESENTING || state == TrackingState.INPUT
            assertEquals("Phase $state", expected, visibility.observe(snapshot(state = state)))
            assertEquals(expected, visibility.wantsWindow)
        }
    }

    @Test
    fun allConfirmedGlyphsCanBeShownWithoutASequenceTable() {
        val visibility = RealtimeOverlayVisibility()
        val complete = snapshot(confirmedCount = 5, usesSequenceTable = false, state = TrackingState.INPUT)
        assertEquals(PredictionState.UNAVAILABLE, complete.outlook.state)

        assertTrue(visibility.observe(complete))
        assertTrue(visibility.wantsWindow)
        visibility.hide(complete)
        assertFalse(visibility.observe(complete))
        assertFalse(visibility.wantsWindow)
    }

    @Test
    fun drawingCompletionHidesOnlyAfterTheSameRoundReachedInput() {
        val visibility = RealtimeOverlayVisibility()
        val input = snapshot(confirmedCount = 5, state = TrackingState.INPUT, phaseSource = PhaseSource.INPUT_TIMER)
        val completed = snapshot(confirmedCount = 5, state = TrackingState.COMPLETED)
        visibility.show()

        assertFalse(visibility.observe(completed, hidesAfterDrawing = true))
        assertTrue(visibility.wantsWindow)
        assertFalse(visibility.observe(input, hidesAfterDrawing = true))
        assertTrue(visibility.wantsWindow)
        assertTrue(visibility.observe(completed, hidesAfterDrawing = true))
        assertFalse(visibility.wantsWindow)
        assertEquals(sequence, completed.outlook.observed)
        assertEquals(input.slots, completed.slots)
    }

    @Test
    fun disabledAutoHideKeepsCompletedResultsVisible() {
        val visibility = RealtimeOverlayVisibility()
        assertTrue(visibility.observe(snapshot(state = TrackingState.INPUT)))

        assertFalse(visibility.observe(snapshot(state = TrackingState.COMPLETED)))
        assertTrue(visibility.wantsWindow)
    }

    @Test
    fun staleRetainedAndInterruptedEvidenceCannotHideTheDrawingWindow() {
        val visibility = RealtimeOverlayVisibility()
        assertTrue(visibility.observe(snapshot(state = TrackingState.INPUT), hidesAfterDrawing = true))
        val cases = listOf(
            snapshot(state = TrackingState.INPUT, phaseSource = PhaseSource.RETAINED),
            snapshot(state = TrackingState.INTERRUPTED, phaseSource = PhaseSource.NONE),
            snapshot(state = TrackingState.COMPLETED, phaseSource = PhaseSource.RETAINED),
            snapshot(state = TrackingState.COMPLETED, liveness = CaptureLiveness.INTERRUPTED),
            snapshot(state = TrackingState.COMPLETED, acceptance = AcceptanceMode.ProvisionalOnly("interrupted")),
            snapshot(hackID = 2, state = TrackingState.COMPLETED),
        )
        for (snapshot in cases) {
            assertFalse(visibility.observe(snapshot, hidesAfterDrawing = true))
            assertTrue(visibility.wantsWindow)
        }
        assertFalse(visibility.observe(snapshot(state = TrackingState.COMPLETED), uptime = 12.0, hidesAfterDrawing = true))
        assertTrue(visibility.wantsWindow)
        assertTrue(visibility.observe(snapshot(state = TrackingState.COMPLETED), hidesAfterDrawing = true))
        assertFalse(visibility.wantsWindow)
    }

    @Test
    fun retainedInputDoesNotArmCompletionHiding() {
        val visibility = RealtimeOverlayVisibility()
        val retainedInput = snapshot(state = TrackingState.INPUT, phaseSource = PhaseSource.RETAINED)
        visibility.show()
        assertFalse(visibility.observe(retainedInput, hidesAfterDrawing = true))
        assertFalse(visibility.observe(snapshot(state = TrackingState.COMPLETED), hidesAfterDrawing = true))
        assertTrue(visibility.wantsWindow)
    }

    @Test
    fun aCompletedRoundDoesNotImmediatelyHideAManuallyReopenedWindow() {
        val visibility = RealtimeOverlayVisibility()
        assertTrue(visibility.observe(snapshot(state = TrackingState.INPUT), hidesAfterDrawing = true))
        val completed = snapshot(state = TrackingState.COMPLETED)
        assertTrue(visibility.observe(completed, hidesAfterDrawing = true))

        visibility.show()
        repeat(3) {
            assertFalse(visibility.observe(completed, hidesAfterDrawing = true))
            assertTrue(visibility.wantsWindow)
        }
        val command = snapshot(state = TrackingState.COMMAND)
        assertTrue(visibility.observe(command, hidesAfterDrawing = true))
        assertFalse(visibility.wantsWindow)
        visibility.show()
        assertFalse(visibility.observe(command, hidesAfterDrawing = true))
        assertTrue(visibility.wantsWindow)
    }

    @Test
    fun aNewPresentationClearsTheOldResultWhenTheCompletionTitleWasMissed() {
        val visibility = RealtimeOverlayVisibility()
        assertTrue(visibility.observe(snapshot(state = TrackingState.INPUT), hidesAfterDrawing = true))
        assertFalse(visibility.observe(snapshot(state = TrackingState.INTERRUPTED), hidesAfterDrawing = true))
        assertTrue(visibility.wantsWindow)

        assertTrue(visibility.observe(snapshot(hackID = 2, confirmedCount = 1), hidesAfterDrawing = true))
        assertFalse(visibility.wantsWindow)
        assertTrue(visibility.observe(snapshot(hackID = 2), hidesAfterDrawing = true))
        assertTrue(visibility.wantsWindow)
        assertFalse(visibility.observe(snapshot(hackID = 2), hidesAfterDrawing = true))
    }

    @Test
    fun resetClearsInputAndCompletionHistory() {
        val visibility = RealtimeOverlayVisibility()
        assertTrue(visibility.observe(snapshot(state = TrackingState.INPUT), hidesAfterDrawing = true))
        assertTrue(visibility.observe(snapshot(state = TrackingState.COMPLETED), hidesAfterDrawing = true))
        visibility.reset()
        visibility.show()
        assertFalse(visibility.observe(snapshot(state = TrackingState.COMPLETED), hidesAfterDrawing = true))
        assertTrue(visibility.wantsWindow)
    }

    @Test
    fun clearingTheOldWindowDoesNotConsumeAnAlreadyReadyNextRound() {
        val visibility = RealtimeOverlayVisibility()
        assertTrue(visibility.observe(snapshot(state = TrackingState.INPUT), hidesAfterDrawing = true))
        val nextRound = snapshot(hackID = 2)

        assertTrue(visibility.observe(nextRound, hidesAfterDrawing = true))
        assertFalse(visibility.wantsWindow)
        assertTrue(visibility.observe(nextRound, hidesAfterDrawing = true))
        assertTrue(visibility.wantsWindow)
    }

    @Test
    fun staleReadySnapshotsDoNotAutomaticallyRevealTheWindow() {
        val visibility = RealtimeOverlayVisibility()
        assertFalse(visibility.observe(snapshot(), uptime = 12.0))
        assertFalse(visibility.wantsWindow)
        assertTrue(visibility.observe(snapshot(), uptime = 10.0))
    }

    private fun snapshot(
        confirmedCount: Int = 2,
        hackID: Int = 1,
        expectedCount: Int? = 5,
        usesSequenceTable: Boolean = true,
        state: TrackingState = TrackingState.PRESENTING,
        phaseSource: PhaseSource = PhaseSource.HEADER_TEXT,
        liveness: CaptureLiveness = CaptureLiveness.LIVE,
        acceptance: AcceptanceMode = AcceptanceMode.TrackingSequence,
        slotCountConflict: Boolean = false,
        indicatorsObstructed: Boolean = false,
        slotOverrides: Map<Int, SlotObservation.Status> = emptyMap(),
    ): GlyphDisplaySnapshot = GlyphDisplaySnapshot(
        captureSessionID = CaptureSessionID(1UL),
        hackID = hackID,
        revision = 1,
        liveness = liveness,
        state = state,
        phaseSource = phaseSource,
        acceptance = acceptance,
        sourceFrameTime = 5.0,
        evidenceArrivalUptime = 10.0,
        expectedSlotCount = expectedCount,
        slots = sequence.mapIndexed { index, glyph ->
            val status = slotOverrides[index] ?: if (index < confirmedCount) {
                SlotObservation.Status.CONFIRMED
            } else {
                SlotObservation.Status.PENDING
            }
            SlotObservation(
                index = index,
                status = status,
                glyph = glyph.takeIf {
                    status == SlotObservation.Status.CONFIRMED || status == SlotObservation.Status.INFERRED
                },
            )
        },
        currentSlotIndex = confirmedCount.takeIf { it < sequence.size },
        inputSlotIndex = null,
        indicatorsObstructed = indicatorsObstructed,
        slotCountConflict = slotCountConflict,
        isFrozen = state == TrackingState.INPUT,
        provisionalGlyph = null,
        provisionalExtensionCount = 0,
        outlook = if (usesSequenceTable) predictor.outlook(sequence.take(confirmedCount), expectedCount)
            else SequenceOutlook.unavailable,
        calibrationSummary = "11 rings",
    )
}
