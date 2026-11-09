package org.szres.glyphlens.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureFrameAdmissionTest {
    private val complete = CapturedFrameMetadata(CaptureFrameStatus.COMPLETE, false)

    @Test
    fun sixtyAndOneTwentyHzCallbacksCopyOnlyTwentyFramesPerSecond() {
        for (rate in listOf(60, 120)) {
            val admission = CaptureFrameAdmission()
            val accepted = (0 until rate).count { admission.accept(it * 1_000_000_000L / rate, complete) }
            assertEquals(20, accepted)
        }
    }

    @Test
    fun idleFramesDoNotCopyPixelsButInterruptedContinuityStillReachesTheEngine() {
        val admission = CaptureFrameAdmission()
        assertTrue(admission.accept(1_000_000_000L, complete))
        assertFalse(admission.accept(1_000_000_000L, complete.copy(status = CaptureFrameStatus.IDLE)))
        assertTrue(admission.accept(1_000_000_000L, complete.copy(status = CaptureFrameStatus.IDLE, continuityBroken = true)))
        assertTrue(admission.accept(1_010_000_000L, complete.copy(continuityBroken = true)))
    }

    @Test
    fun sourceClockReversalAndNewSessionStartImmediately() {
        val admission = CaptureFrameAdmission()
        assertTrue(admission.accept(5_000_000_000L, complete))
        assertFalse(admission.accept(5_010_000_000L, complete))
        assertTrue(admission.accept(1_000_000_000L, complete))
        assertTrue(CaptureFrameAdmission().accept(1_010_000_000L, complete))
    }
}
