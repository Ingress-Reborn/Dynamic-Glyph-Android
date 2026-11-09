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
    fun mapControlsHideBeforeAnyHackOrInput() {
        for (state in TrackingState.entries) {
            val visibility = RealtimeOverlayVisibility()
            val map = snapshot(hackID = 0, state = state, hasMapControl = true)
            visibility.show()
            assertTrue(visibility.observe(map, hidesAfterDrawing = true))
            assertFalse(visibility.wantsWindow)
        }
    }

    @Test
    fun disabledAutoHideKeepsTheWindowVisibleOnTheMap() {
        val visibility = RealtimeOverlayVisibility()
        visibility.show()
        assertFalse(visibility.observe(snapshot(hasMapControl = true)))
        assertTrue(visibility.wantsWindow)
    }

    @Test
    fun completionHeadersAndInputChangesDoNotHideWithoutAMapControl() {
        val visibility = RealtimeOverlayVisibility()
        assertTrue(visibility.observe(snapshot(state = TrackingState.INPUT), hidesAfterDrawing = true))
        for (state in listOf(TrackingState.INPUT, TrackingState.COMPLETED, TrackingState.INTERRUPTED)) {
            assertFalse(visibility.observe(snapshot(state = state), hidesAfterDrawing = true))
            assertTrue(visibility.wantsWindow)
        }
    }

    @Test
    fun onlyFreshLiveMapPixelsCanHideTheWindow() {
        val visibility = RealtimeOverlayVisibility()
        visibility.show()
        val cases = listOf(
            snapshot(hasMapControl = true, liveness = CaptureLiveness.INTERRUPTED),
            snapshot(hasMapControl = true, liveness = CaptureLiveness.STOPPED),
            snapshot(hasMapControl = true, acceptance = AcceptanceMode.ProvisionalOnly("interrupted")),
        )
        for (map in cases) {
            assertFalse(visibility.observe(map, hidesAfterDrawing = true))
            assertTrue(visibility.wantsWindow)
        }
        val map = snapshot(hasMapControl = true)
        assertFalse(visibility.observe(map, uptime = 12.0, hidesAfterDrawing = true))
        assertTrue(visibility.wantsWindow)
        assertTrue(visibility.observe(map, hidesAfterDrawing = true))
        assertFalse(visibility.wantsWindow)
    }

    @Test
    fun returningToMapConsumesTheOldRoundEvenBeforeItsPredictionWasReady() {
        val visibility = RealtimeOverlayVisibility()
        visibility.show()
        assertTrue(visibility.observe(snapshot(confirmedCount = 1, hasMapControl = true), hidesAfterDrawing = true))
        assertFalse(visibility.observe(snapshot(), hidesAfterDrawing = true))
        assertFalse(visibility.wantsWindow)
        assertTrue(visibility.observe(snapshot(hackID = 2), hidesAfterDrawing = true))
        assertTrue(visibility.wantsWindow)
    }

    @Test
    fun manuallyShowingOnTheMapHidesAgainWhileTheOptionIsEnabled() {
        val visibility = RealtimeOverlayVisibility()
        val map = snapshot(hasMapControl = true)
        repeat(3) {
            visibility.show()
            assertTrue(visibility.observe(map, hidesAfterDrawing = true))
            assertFalse(visibility.wantsWindow)
            assertFalse(visibility.observe(map, hidesAfterDrawing = true))
        }
    }

    @Test
    fun resetClearsTheRoundConsumedByMapHiding() {
        val visibility = RealtimeOverlayVisibility()
        visibility.observe(snapshot(hasMapControl = true), hidesAfterDrawing = true)
        assertFalse(visibility.observe(snapshot(), hidesAfterDrawing = true))
        visibility.reset()
        assertTrue(visibility.observe(snapshot(), hidesAfterDrawing = true))
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
        hasMapControl: Boolean = false,
    ): GlyphDisplaySnapshot = GlyphDisplaySnapshot(
        hasMapControl = hasMapControl,
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
