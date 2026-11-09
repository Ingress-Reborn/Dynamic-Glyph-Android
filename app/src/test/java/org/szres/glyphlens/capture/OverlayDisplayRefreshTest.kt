package org.szres.glyphlens.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.szres.glyphlens.recognition.AcceptanceMode
import org.szres.glyphlens.recognition.CaptureLiveness
import org.szres.glyphlens.recognition.CaptureSessionID
import org.szres.glyphlens.recognition.DisplayStatus
import org.szres.glyphlens.recognition.GlyphDisplaySnapshot
import org.szres.glyphlens.recognition.PhaseSource
import org.szres.glyphlens.recognition.SequenceOutlook
import org.szres.glyphlens.recognition.TrackingState

class OverlayDisplayRefreshTest {
    @Test
    fun staleDeadlineRendersSameSnapshotWithoutChangingEvidenceTimes() {
        val snapshot = snapshot()

        assertEquals(1_501L, OverlayDisplayRefresh.nextDelayMillis(snapshot, uptime = 10.0))
        val before = OverlayDisplayRefresh.renderKey(snapshot, uptime = 10.0)
        val after = OverlayDisplayRefresh.renderKey(snapshot, uptime = 11.501)

        assertNotEquals(before, after)
        assertEquals(DisplayStatus.STALE, snapshot.displayStatus(11.501))
        assertEquals(5.0, snapshot.sourceFrameTime, 0.0)
        assertEquals(10.0, snapshot.evidenceArrivalUptime, 0.0)
        assertNull(OverlayDisplayRefresh.nextDelayMillis(snapshot, uptime = 11.501))
    }

    private fun snapshot() = GlyphDisplaySnapshot(
        captureSessionID = CaptureSessionID(1UL),
        hackID = 1,
        revision = 1,
        liveness = CaptureLiveness.LIVE,
        state = TrackingState.PRESENTING,
        phaseSource = PhaseSource.HEADER_TEXT,
        acceptance = AcceptanceMode.TrackingSequence,
        sourceFrameTime = 5.0,
        evidenceArrivalUptime = 10.0,
        expectedSlotCount = null,
        slots = emptyList(),
        currentSlotIndex = null,
        inputSlotIndex = null,
        slotCountConflict = false,
        isFrozen = false,
        provisionalGlyph = null,
        provisionalExtensionCount = 0,
        outlook = SequenceOutlook.unavailable,
        calibrationSummary = "11 rings",
    )
}
