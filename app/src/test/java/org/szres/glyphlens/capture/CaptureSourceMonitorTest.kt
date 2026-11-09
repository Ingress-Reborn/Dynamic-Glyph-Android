package org.szres.glyphlens.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSourceMonitorTest {
    @Test
    fun repeatedSourceTimeIsIdleAndFreshTimeIsComplete() {
        val monitor = CaptureSourceMonitor()
        val first = monitor.observe(sourceTimestampNanos = 1_000_000_000L, arrivalUptimeNanos = 2_000_000_000L)
        val idle = monitor.observe(sourceTimestampNanos = 1_000_000_000L, arrivalUptimeNanos = 2_100_000_000L)
        val next = monitor.observe(sourceTimestampNanos = 1_100_000_000L, arrivalUptimeNanos = 2_200_000_000L)

        assertEquals(CaptureFrameStatus.COMPLETE, first.status)
        assertEquals(CaptureFrameStatus.IDLE, idle.status)
        assertEquals(CaptureFrameStatus.COMPLETE, next.status)
        assertFalse(first.continuityBroken)
        assertFalse(idle.continuityBroken)
        assertFalse(next.continuityBroken)
    }

    @Test
    fun backwardsClockAndCallbackStallBreakContinuity() {
        val backwards = CaptureSourceMonitor()
        backwards.observe(10_000_000_000L, 20_000_000_000L)
        assertTrue(backwards.observe(9_000_000_000L, 20_100_000_000L).continuityBroken)

        val stalled = CaptureSourceMonitor()
        stalled.observe(1_000_000_000L, 5_000_000_000L)
        val idleAfterStall = stalled.observe(1_000_000_000L, 6_100_000_000L)
        assertEquals(CaptureFrameStatus.IDLE, idleAfterStall.status)
        assertTrue(idleAfterStall.continuityBroken)
    }

    @Test
    fun deliveryStallAndUnexplainedSourceClockJumpBothBreakContinuity() {
        val ordinary = CaptureSourceMonitor()
        ordinary.observe(1_000_000_000L, 10_000_000_000L)
        // Both clocks advance together: slow delivery, not an unexplained source-clock jump.
        assertTrue(ordinary.observe(3_000_000_000L, 12_000_000_000L).continuityBroken)

        val epoch = CaptureSourceMonitor()
        epoch.observe(1_000_000_000L, 10_000_000_000L)
        assertTrue(epoch.observe(4_000_000_000L, 10_100_000_000L).continuityBroken)
    }
}
