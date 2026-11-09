package org.szres.glyphlens.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlyphDisplaySnapshotTests {
    private fun snapshot(
        liveness: CaptureLiveness = CaptureLiveness.LIVE,
        state: TrackingState = TrackingState.PRESENTING,
        phaseSource: PhaseSource = PhaseSource.INDICATOR_TRANSITION,
        acceptance: AcceptanceMode = AcceptanceMode.TrackingSequence,
        evidenceArrivalUptime: Double = 10.0,
        slots: List<SlotObservation> = emptyList(),
        indicatorsObstructed: Boolean = false,
        slotCountConflict: Boolean = false,
        revision: Int = 1,
    ): GlyphDisplaySnapshot = GlyphDisplaySnapshot(
        captureSessionID = CaptureSessionID(1UL),
        hackID = 1,
        revision = revision,
        liveness = liveness,
        state = state,
        phaseSource = phaseSource,
        acceptance = acceptance,
        sourceFrameTime = 5.0,
        evidenceArrivalUptime = evidenceArrivalUptime,
        expectedSlotCount = slots.size,
        slots = slots,
        currentSlotIndex = null,
        inputSlotIndex = null,
        indicatorsObstructed = indicatorsObstructed,
        slotCountConflict = slotCountConflict,
        isFrozen = false,
        provisionalGlyph = null,
        provisionalExtensionCount = 0,
        outlook = SequenceOutlook.unavailable,
        calibrationSummary = "11 rings",
    )

    @Test
    fun everyDisplayStatusHasExpectedTone() {
        val expected = mapOf(
            DisplayStatus.STOPPED to StatusTone.WARNING,
            DisplayStatus.STALE to StatusTone.WARNING,
            DisplayStatus.OBSTRUCTED to StatusTone.WARNING,
            DisplayStatus.CONFLICTED to StatusTone.WARNING,
            DisplayStatus.INFERRED to StatusTone.TENTATIVE,
            DisplayStatus.PROVISIONAL to StatusTone.TENTATIVE,
            DisplayStatus.WAITING to StatusTone.IDLE,
            DisplayStatus.UNKNOWN_PHASE to StatusTone.IDLE,
            DisplayStatus.COLLECTING to StatusTone.COLLECTING,
            DisplayStatus.YOUR_TURN to StatusTone.DRAWING,
            DisplayStatus.COMPLETE to StatusTone.DONE,
        )
        assertEquals(expected.keys, DisplayStatus.entries.toSet())
        for ((status, tone) in expected) assertEquals(tone, status.tone)
        assertEquals(expected.values.toSet(), StatusTone.entries.toSet())
    }

    @Test
    fun warningToneAlwaysMeansUntrustworthy() {
        for (status in DisplayStatus.entries.filter { it.tone == StatusTone.WARNING }) {
            assertFalse(status.isTrustworthy)
        }
        assertTrue(DisplayStatus.INFERRED.isTrustworthy)
        assertFalse(DisplayStatus.UNKNOWN_PHASE.isTrustworthy)
    }

    @Test
    fun displayStatusUsesSwiftPriorityOrder() {
        val inferred = SlotObservation(0, SlotObservation.Status.INFERRED, GlyphID("see"))
        assertEquals(
            DisplayStatus.STOPPED,
            snapshot(liveness = CaptureLiveness.STOPPED, slots = listOf(inferred)).displayStatus(100.0),
        )
        assertEquals(DisplayStatus.STALE, snapshot().displayStatus(13.0))
        assertEquals(
            DisplayStatus.INFERRED,
            snapshot(
                slots = listOf(inferred),
                indicatorsObstructed = true,
                acceptance = AcceptanceMode.ProvisionalOnly("starting"),
            ).displayStatus(10.0),
        )
        assertEquals(
            DisplayStatus.OBSTRUCTED,
            snapshot(
                indicatorsObstructed = true,
                acceptance = AcceptanceMode.ProvisionalOnly("starting"),
            ).displayStatus(10.0),
        )
        assertEquals(
            DisplayStatus.PROVISIONAL,
            snapshot(
                acceptance = AcceptanceMode.ProvisionalOnly("starting"),
                slotCountConflict = true,
            ).displayStatus(10.0),
        )
        assertEquals(DisplayStatus.CONFLICTED, snapshot(slotCountConflict = true).displayStatus(10.0))
    }

    @Test
    fun statusLinesMatchDisplayState() {
        assertEquals("Watching the sequence", snapshot().statusLine(10.0))
        assertEquals("Paused", snapshot().statusLine(13.0))
        assertEquals(
            "Watching the sequence · waiting for indicators",
            snapshot(phaseSource = PhaseSource.RETAINED).statusLine(10.0),
        )
        assertEquals(
            "Get ready · the sequence is coming",
            snapshot(state = TrackingState.COMMAND).statusLine(10.0),
        )
        assertEquals("Hack complete", snapshot(state = TrackingState.COMPLETED).statusLine(10.0))
        assertEquals("Lost sight of the game", snapshot(state = TrackingState.INTERRUPTED).statusLine(10.0))
    }

    @Test
    fun ageAndStalenessUseEvidenceArrivalClockAndStrictThreshold() {
        val model = snapshot()
        assertEquals(1.5, model.age(11.5), 0.0)
        assertFalse(model.isStale(11.5, 1.5))
        assertTrue(model.isStale(11.5001, 1.5))
        val noEvidence = snapshot(evidenceArrivalUptime = 0.0)
        assertEquals(0.0, noEvidence.age(100.0), 0.0)
        assertFalse(noEvidence.isStale(100.0, 1.5))
    }

    @Test
    fun revisionAndEqualityFollowGlyphTrackerChanges() {
        val first = snapshot(revision = 5)
        val same = snapshot(revision = 5)
        assertEquals(5, first.revision)
        assertEquals(first, same)
        assertEquals(first.hashCode(), same.hashCode())
        assertFalse(first == snapshot(revision = 6))
    }

    @Test
    fun waitingSnapshotHasNoGlyphTiles() {
        assertTrue(GlyphDisplaySnapshot.waiting(
            CaptureSessionID(1UL),
            CaptureLiveness.LIVE,
            AcceptanceMode.TrackingSequence,
        ).tileStyles.isEmpty())
    }
}
