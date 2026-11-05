package com.dynamicglyph.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phase and slot state machine, driven by scripted evidence.
 *
 * This is the component with the most rules and the least obvious failure mode:
 * it will happily produce a confident, wrong sequence if a boundary is treated
 * as one when it is not. The tests below pin the behaviours that matter —
 * a slot is only committed on an independent boundary, a released last indicator
 * closes the last slot, and the countdown bar overrides a stale header.
 */
class PresentationTrackerTest {

    private val session = CaptureSessionID(1)

    private fun reading(
        slotCount: Int,
        highlighted: Int?,
        timerBar: Boolean = false
    ) = SlotStripReader.Reading(
        slotCount = slotCount,
        highlightedIndex = highlighted,
        markerCentersX = (0 until slotCount).map { 0.5 + (it - (slotCount - 1) / 2.0) * 0.16 },
        hasInputTimerBar = timerBar,
        isPartlyCovered = false
    )

    private fun presenting(time: Double) =
        HeaderReading(GamePhase.PRESENTING, "incoming glyph sequence", 0.9, time)

    private fun evidence(
        time: Double,
        header: HeaderReading? = presenting(time),
        slots: SlotStripReader.Reading? = null,
        shape: ShapeEvidence? = ShapeEvidence(ShapeKind.NO_STROKE, null, emptySet(), emptyList()),
        timerBar: Boolean = false
    ) = FrameEvidence(
        sessionID = session,
        time = time,
        header = header,
        slots = slots,
        slotRejection = null,
        shape = shape,
        hasValidCalibration = true,
        hasInputTimerBar = timerBar
    )

    private fun unique(glyph: String) = ShapeEvidence(
        ShapeKind.UNIQUE_VISIBLE_SHAPE,
        GlyphID(glyph),
        GlyphPath("05a").undirectedEdges,
        emptyList()
    )

    @Test
    fun `a presentation header starts a hack and opens slot zero`() {
        val tracker = PresentationTracker(session)
        tracker.accept(evidence(0.0, slots = reading(2, 0)))

        assertEquals(TrackingState.PRESENTING, tracker.state)
        assertEquals(1, tracker.hackID)
        assertEquals(0, tracker.currentSlotIndex!!)
        assertTrue(tracker.observedPresentationForCurrentHack)
        assertEquals(SlotObservation.Status.OBSERVING, tracker.slots[0].status)
    }

    @Test
    fun `a shape is not committed until the indicator advances`() {
        val tracker = PresentationTracker(session)
        tracker.accept(evidence(0.00, slots = reading(2, 0)))
        tracker.accept(evidence(0.05, slots = reading(2, 0), shape = unique("alpha")))
        tracker.accept(evidence(0.12, slots = reading(2, 0), shape = unique("alpha")))

        // Seen twice, stable, but no boundary yet: the shape must not be in the
        // sequence, only provisionally displayed.
        assertEquals(GlyphID("alpha"), tracker.provisionalGlyph)
        assertTrue(tracker.confirmedPrefix.isEmpty())

        tracker.accept(evidence(0.20, slots = reading(2, 1)))

        assertEquals(SlotObservation.Status.CONFIRMED, tracker.slots[0].status)
        assertEquals(listOf(GlyphID("alpha")), tracker.confirmedPrefix)
    }

    @Test
    fun `a released last indicator closes the last slot over a clear array`() {
        val tracker = PresentationTracker(session)
        tracker.accept(evidence(0.00, slots = reading(2, 0)))
        tracker.accept(evidence(0.05, slots = reading(2, 0), shape = unique("alpha")))
        tracker.accept(evidence(0.12, slots = reading(2, 0), shape = unique("alpha")))
        tracker.accept(evidence(0.20, slots = reading(2, 1)))
        tracker.accept(evidence(0.25, slots = reading(2, 1), shape = unique("beta")))
        tracker.accept(evidence(0.32, slots = reading(2, 1), shape = unique("beta")))

        // Highlight gone but the strip is still up: the tail of this
        // presentation. It takes a held blank interval to close the slot.
        tracker.accept(evidence(0.40, slots = reading(2, null)))
        assertEquals(SlotObservation.Status.OBSERVING, tracker.slots[1].status)

        tracker.accept(evidence(0.47, slots = reading(2, null)))

        assertEquals(SlotObservation.Status.CONFIRMED, tracker.slots[1].status)
        assertEquals(listOf(GlyphID("alpha"), GlyphID("beta")), tracker.confirmedPrefix)
    }

    @Test
    fun `a single observation is not stable enough to commit`() {
        val tracker = PresentationTracker(session)
        tracker.accept(evidence(0.00, slots = reading(2, 0)))
        tracker.accept(evidence(0.05, slots = reading(2, 0), shape = unique("alpha")))
        tracker.accept(evidence(0.06, slots = reading(2, 1)))

        // One sample: the slot closes as uncertain rather than guessing.
        assertEquals(SlotObservation.Status.UNCERTAIN, tracker.slots[0].status)
        assertTrue(tracker.confirmedPrefix.isEmpty())
    }

    @Test
    fun `the countdown bar overrides a lagging presentation header`() {
        val tracker = PresentationTracker(session)
        tracker.accept(evidence(0.00, slots = reading(2, 0)))
        tracker.accept(evidence(0.05, slots = reading(2, 0), shape = unique("alpha")))
        tracker.accept(evidence(0.12, slots = reading(2, 0), shape = unique("alpha")))

        // The header still says "presenting" because OCR runs behind the
        // sampler. The bar in this frame says the player is drawing, and the
        // current frame wins: collecting player strokes as presentation
        // evidence is the failure this design exists to prevent.
        tracker.accept(
            evidence(0.20, slots = reading(2, 0), timerBar = true)
        )

        assertEquals(TrackingState.INPUT, tracker.state)
        assertEquals(PhaseSource.INPUT_TIMER, tracker.phaseSource)
        assertTrue(tracker.isFrozen)
        // The last slot is closed by the boundary, so the sequence is kept.
        assertEquals(listOf(GlyphID("alpha")), tracker.confirmedPrefix)
    }

    @Test
    fun `time moving backwards interrupts the session`() {
        val tracker = PresentationTracker(session)
        tracker.accept(evidence(1.0, slots = reading(2, 0)))
        // No header in this frame: a fresh header would legitimately
        // re-establish presentation, which is a different situation.
        tracker.accept(evidence(0.5, slots = reading(2, 0), header = null))

        assertEquals(TrackingState.INTERRUPTED, tracker.state)
    }

    @Test
    fun `a frame gap beyond the maximum interrupts the session`() {
        val tracker = PresentationTracker(session)
        tracker.accept(evidence(0.0, slots = reading(2, 0)))
        tracker.accept(evidence(5.0, slots = reading(2, 0), header = null))

        assertEquals(TrackingState.INTERRUPTED, tracker.state)
    }

    @Test
    fun `evidence from another session is rejected`() {
        val tracker = PresentationTracker(session)
        val other = evidence(0.0, slots = reading(2, 0)).copy(sessionID = CaptureSessionID(99))
        val events = tracker.accept(other)

        assertEquals(TrackingState.INACTIVE, tracker.state)
        assertEquals(1, events.size)
        assertEquals(TrackingEvent.Kind.STALE_EVIDENCE_REJECTED, events[0].kind)
    }

    @Test
    fun `an advancing indicator marks skipped slots missing`() {
        val tracker = PresentationTracker(session)
        tracker.accept(evidence(0.00, slots = reading(3, 0)))
        tracker.accept(evidence(0.05, slots = reading(3, 0), shape = unique("alpha")))
        tracker.accept(evidence(0.12, slots = reading(3, 0), shape = unique("alpha")))

        // Jump from slot 0 to slot 2: slot 1 never produced evidence.
        tracker.accept(evidence(0.20, slots = reading(3, 2)))

        assertEquals(SlotObservation.Status.MISSING, tracker.slots[1].status)
        assertEquals(SlotObservation.Status.OBSERVING, tracker.slots[2].status)
        assertEquals(listOf(GlyphID("alpha")), tracker.confirmedPrefix)
    }

    @Test
    fun `the slot count is only trusted after repeated agreement`() {
        val tracker = PresentationTracker(session)
        tracker.accept(evidence(0.00, slots = reading(3, 0)))
        assertNull(tracker.expectedSlotCount)

        tracker.accept(evidence(0.05, slots = reading(3, 0)))
        assertEquals(3, tracker.expectedSlotCount)
    }

    @Test
    fun `a changing slot count is reported as a conflict`() {
        val tracker = PresentationTracker(session)
        tracker.accept(evidence(0.00, slots = reading(3, 0)))
        tracker.accept(evidence(0.05, slots = reading(3, 0)))
        tracker.accept(evidence(0.10, slots = reading(4, 0)))
        tracker.accept(evidence(0.15, slots = reading(4, 0)))

        assertTrue(tracker.slotCountConflict)
        assertNull(tracker.expectedSlotCount)
    }

    @Test
    fun `input without an observed presentation discards the retained sequence`() {
        val tracker = PresentationTracker(session)
        tracker.accept(evidence(0.00, slots = reading(2, 0)))
        tracker.accept(evidence(0.05, slots = reading(2, 0), shape = unique("alpha")))
        tracker.accept(evidence(0.12, slots = reading(2, 0), shape = unique("alpha")))
        tracker.accept(evidence(0.20, slots = reading(2, 1)))
        assertEquals(1, tracker.confirmedPrefix.size)

        // The command channel, then input with no presentation in between: the
        // previous answer must not be shown as this hack's.
        tracker.accept(
            evidence(0.30, header = HeaderReading(GamePhase.COMMAND, "command channel open", 0.9, 0.30))
        )
        assertEquals(TrackingState.COMMAND, tracker.state)

        tracker.accept(
            evidence(0.40, header = HeaderReading(GamePhase.INPUT, "go", 0.9, 0.40))
        )
        assertTrue(tracker.confirmedPrefix.isEmpty())
    }

    @Test
    fun `reset clears everything`() {
        val tracker = PresentationTracker(session)
        tracker.accept(evidence(0.0, slots = reading(2, 0)))
        tracker.reset()

        assertEquals(TrackingState.INACTIVE, tracker.state)
        assertTrue(tracker.slots.isEmpty())
        assertNull(tracker.currentSlotIndex)
        assertFalse(tracker.observedPresentationForCurrentHack)
    }
}
