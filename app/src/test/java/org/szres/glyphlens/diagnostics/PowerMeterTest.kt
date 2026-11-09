package org.szres.glyphlens.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.szres.glyphlens.recognition.image.AnalysisRateController
import kotlin.math.abs

class PowerMeterTest {
    private class Clock(var now: Long = 1_000 * SECOND) {
        var cpu: Long? = 0L
        var thermal: Int? = 0
        var lowPower: Boolean? = false
        var memory: Long? = 32 * 1_048_576L
    }

    private fun meter(clock: Clock, sampleCapacity: Int = PowerMeter.SAMPLE_CAPACITY) = PowerMeter(
        clock = { clock.now },
        gauges = PowerGaugeReader {
            PowerGaugeSnapshot(clock.cpu, clock.thermal, clock.lowPower, clock.memory)
        },
        sampleCapacity = sampleCapacity,
    )

    @Test
    fun lightweightReadoutsReuseSampledGaugesAndFreezeWithTheSession() {
        val clock = Clock().apply { cpu = 10 * SECOND }
        var gaugeReads = 0
        val meter = PowerMeter(
            clock = { clock.now },
            gauges = PowerGaugeReader {
                gaugeReads++
                PowerGaugeSnapshot(clock.cpu, clock.thermal, clock.lowPower, clock.memory)
            },
        )
        assertNull(meter.readingMode)
        assertNull(meter.sampledCpuSeconds)
        assertEquals(0, gaugeReads)

        meter.reset()
        val mode = PowerReadingMode(AnalysisRateController.Mode.ACTIVE, 0.049, true, true)
        meter.noteAnalysisMode(mode.mode, 0.0, mode)
        clock.cpu = 11 * SECOND
        repeat(100) {
            assertEquals(mode, meter.readingMode)
            assertEquals(0.0, meter.sampledCpuSeconds!!, 0.0)
        }
        assertEquals(1, gaugeReads)

        clock.now += SECOND
        meter.count(PowerMetric.FRAMES_DELIVERED)
        assertEquals(1.0, meter.sampledCpuSeconds!!, 0.0)
        assertEquals(2, gaugeReads)

        clock.cpu = 12 * SECOND
        assertEquals(2.0, meter.snapshot().cpuSeconds!!, 0.0)
        assertEquals(2.0, meter.sampledCpuSeconds!!, 0.0)
        assertEquals(3, gaugeReads)

        clock.cpu = 13 * SECOND
        meter.end()
        clock.cpu = 15 * SECOND
        clock.now += SECOND
        meter.count(PowerMetric.FRAMES_DELIVERED)
        meter.noteAnalysisMode(AnalysisRateController.Mode.QUIET, 2.0)
        assertEquals(3.0, meter.sampledCpuSeconds!!, 0.0)
        assertEquals(mode, meter.readingMode)
        assertEquals(4, gaugeReads)

        meter.reset()
        assertEquals(0.0, meter.sampledCpuSeconds!!, 0.0)
        assertNull(meter.readingMode)
        assertEquals(5, gaugeReads)
    }

    @Test
    fun lightweightCpuReadoutDoesNotInventMissingMeasurements() {
        val clock = Clock().apply { cpu = null }
        val meter = meter(clock)
        meter.reset()
        assertNull(meter.sampledCpuSeconds)
        clock.cpu = SECOND
        clock.now += SECOND
        meter.count(PowerMetric.FRAMES_DELIVERED)
        assertNull(meter.sampledCpuSeconds)

        meter.reset()
        clock.cpu = null
        clock.now += SECOND
        meter.count(PowerMetric.FRAMES_DELIVERED)
        assertNull(meter.sampledCpuSeconds)
        meter.end()
        assertNull(meter.sampledCpuSeconds)
    }

    @Test
    fun rollingWindowKeepsSixtySecondsAndTotalsEverything() {
        val window = PowerWindow(2)
        window.record(0, second = 100)
        window.record(0, count = 2, second = 130)
        window.record(0, second = 159)
        window.record(1, second = 159)
        assertEquals(4, window.recent(0, 159).count)
        assertEquals(1, window.recent(1, 159).count)
        assertEquals(3, window.recent(0, 160).count)
        assertEquals(0, window.recent(0, 219).count)
        assertEquals(4, window.totals()[0].count)

        window.record(0, nanoseconds = 5, second = 220)
        window.record(0, nanoseconds = 9, second = 280)
        window.record(0, nanoseconds = 1, second = 220) // Reused bucket: totals only.
        assertEquals(9, window.recent(0, 280).nanoseconds)
        assertEquals(4 + 3, window.totals()[0].count)
        assertEquals(15, window.totals()[0].nanoseconds)
    }

    @Test
    fun spanAndCumulativeRateUseRealSamplesOnly() {
        assertEquals(30.5, PowerWindow.span(30 * SECOND + SECOND / 2, 0), 0.0)
        assertEquals(59.25, PowerWindow.span(200 * SECOND + SECOND / 4, 0), 0.0)
        assertEquals(50.25, PowerWindow.span(200 * SECOND + SECOND / 4, 150 * SECOND), 0.0)
        assertEquals(60.0, PowerWindow.perMinute(30, 30.0)!!, 0.0)
        assertNull(PowerWindow.perMinute(30, 0.5))

        val cumulative = CumulativeWindow()
        cumulative.note(0, 0)
        cumulative.note(5 * SECOND, 10 * SECOND)
        cumulative.note(6 * SECOND, 10 * SECOND + SECOND / 2)
        assertEquals(0.5, cumulative.rate(10 * SECOND, 20 * SECOND)!!, 1e-9)
        assertNull(cumulative.rate(40 * SECOND, 70 * SECOND))
        assertNull(CumulativeWindow().rate(1, 5 * SECOND))
    }

    @Test
    fun separateTimersRetainNearestRankPercentilesAndBoundedSamples() {
        val clock = Clock()
        val meter = meter(clock, sampleCapacity = 4)
        meter.reset()
        for (milliseconds in listOf(10L, 20L, 30L, 40L, 50L)) {
            clock.now += SECOND / 100
            meter.record(PowerMetric.FRAME_WORK, milliseconds * 1_000_000L)
        }
        clock.now += SECOND / 10
        meter.record(PowerMetric.HEADER_READ, 80_000_000L)
        clock.now += SECOND / 100
        meter.record(PowerMetric.CAPTURE_COPY, 12_000_000L)
        clock.now += SECOND / 100
        meter.record(PowerMetric.DISPLAY_LATENCY, 35_000_000L)
        val snapshot = meter.snapshot()
        assertEquals(5, snapshot.total(PowerMetric.FRAME_WORK).count)
        assertEquals(4, snapshot.timing(PowerMetric.FRAME_WORK).retainedSamples)
        assertEquals(30.0, snapshot.timing(PowerMetric.FRAME_WORK).p50Milliseconds!!, 1e-9)
        assertEquals(50.0, snapshot.timing(PowerMetric.FRAME_WORK).p95Milliseconds!!, 1e-9)
        assertEquals(80.0, snapshot.timing(PowerMetric.HEADER_READ).p50Milliseconds!!, 1e-9)
        assertEquals(12.0, snapshot.timing(PowerMetric.CAPTURE_COPY).p95Milliseconds!!, 1e-9)
        assertEquals(35.0, snapshot.timing(PowerMetric.DISPLAY_LATENCY).p50Milliseconds!!, 1e-9)
        assertNull(snapshot.timing(PowerMetric.GLYPH_READ).p50Milliseconds)

        val lines = PowerReport.lines(snapshot)
        assertTrue(lines.any { it.startsWith("text read") })
        assertTrue(lines.any { it.startsWith("recognition") })
        assertTrue(lines.any { it.startsWith("display") })
        assertTrue(lines.any { it.startsWith("longest") })
        assertTrue(lines.all { it.length <= PowerReport.width })
    }

    @Test
    fun firstFrameDisplayLatencyIsNotMistakenForEarlierCpuWork() {
        val clock = Clock()
        val meter = meter(clock)
        meter.reset()
        meter.record(PowerMetric.DISPLAY_LATENCY, 50_000_000L)
        meter.record(PowerMetric.HEADER_READ, 50_000_000L)
        val snapshot = meter.snapshot()
        assertEquals(1, snapshot.total(PowerMetric.DISPLAY_LATENCY).count)
        assertEquals(50.0, snapshot.timing(PowerMetric.DISPLAY_LATENCY).p95Milliseconds!!, 1e-9)
        assertEquals(0, snapshot.total(PowerMetric.HEADER_READ).count)
    }

    @Test
    fun resetAndStopFreezeSessionAndRejectEarlierWork() {
        val clock = Clock()
        val meter = meter(clock)
        meter.count(PowerMetric.FRAMES_DELIVERED)
        assertFalse(meter.snapshot().hasSession)
        meter.reset()
        assertEquals(0, meter.snapshot().total(PowerMetric.FRAMES_DELIVERED).count)

        for (index in 0 until 20) {
            clock.now += SECOND / 2
            clock.cpu = clock.cpu!! + SECOND / 4
            meter.count(PowerMetric.FRAMES_DELIVERED)
            val started = meter.now()
            clock.now += 2_000_000
            meter.finish(PowerMetric.CONVERSION, started)
        }
        meter.count(PowerMetric.FRAMES_ANALYSED, 10)
        clock.thermal = 4
        clock.now += SECOND
        meter.count(PowerMetric.FRAMES_DELIVERED)
        clock.thermal = 2
        val snapshot = meter.snapshot()
        assertEquals(21, snapshot.total(PowerMetric.FRAMES_DELIVERED).count)
        assertEquals(20, snapshot.total(PowerMetric.CONVERSION).count)
        assertEquals(2.0, snapshot.total(PowerMetric.CONVERSION).averageMilliseconds!!, 1e-9)
        assertEquals(5.0, snapshot.cpuSeconds!!, 1e-9)
        assertEquals(2, snapshot.thermalStatus)
        assertEquals(4, snapshot.worstThermalStatus)

        meter.end()
        val stopped = meter.snapshot()
        clock.now += 30 * SECOND
        clock.cpu = clock.cpu!! + 9 * SECOND
        clock.thermal = 6
        clock.lowPower = true
        clock.memory = 99 * 1_048_576L
        meter.count(PowerMetric.FRAMES_DELIVERED)
        meter.record(PowerMetric.HEADER_READ, 1_000_000)
        assertTrue(meter.snapshot().ended)
        assertEquals(stopped.elapsedSeconds, meter.snapshot().elapsedSeconds, 0.0)
        assertEquals(stopped.cpuSeconds!!, meter.snapshot().cpuSeconds!!, 0.0)
        assertEquals(21, meter.snapshot().total(PowerMetric.FRAMES_DELIVERED).count)
        assertEquals(0, meter.snapshot().total(PowerMetric.HEADER_READ).count)
        assertEquals(2, meter.snapshot().thermalStatus)
        assertEquals(false, meter.snapshot().lowPowerMode)
        assertEquals(32 * 1_048_576L, meter.snapshot().memoryFootprintBytes)

        meter.reset()
        clock.now += SECOND
        meter.record(PowerMetric.HEADER_READ, 2 * SECOND) // Began before this reset.
        val started = meter.now() - SECOND / 2
        clock.now += SECOND
        meter.finish(PowerMetric.CONVERSION, started)
        assertEquals(0, meter.snapshot().total(PowerMetric.HEADER_READ).count)
        assertEquals(1, meter.snapshot().total(PowerMetric.CONVERSION).count)
    }

    @Test
    fun memoryEventsRetainCaptureAllocationMeasurements() {
        val clock = Clock()
        val meter = meter(clock)
        meter.reset()
        meter.noteMemory(PowerMemoryKind.RGBA_FRAME, 123_456)
        meter.noteMemory(PowerMemoryKind.RGBA_FRAME)
        meter.count(PowerMetric.FRAMES_DELIVERED)
        val snapshot = meter.snapshot()
        assertEquals(2, snapshot.memoryEvents.size)
        assertEquals(PowerMemoryKind.RGBA_FRAME, snapshot.memoryEvents[0].kind)
        assertEquals(123_456L, snapshot.memoryEvents[0].byteCount)
        assertEquals(1, snapshot.total(PowerMetric.FRAMES_DELIVERED).count)
        assertTrue(PowerReport.lines(snapshot).any { it.contains("memory events") })
    }

    @Test
    fun analysisModesCapPausedGapsAndRestartCleanly() {
        var modes = AnalysisModeClock()
        assertNull(modes.percent(AnalysisRateController.Mode.ACTIVE))
        modes = modes.note(AnalysisRateController.Mode.ACTIVE, 0.0)
        modes = modes.note(AnalysisRateController.Mode.ACTIVE, 1.0)
        modes = modes.note(AnalysisRateController.Mode.RESTING, 1.5)
        modes = modes.note(AnalysisRateController.Mode.QUIET, 2.0)
        modes = modes.note(AnalysisRateController.Mode.QUIET, 12.0)
        modes = modes.note(AnalysisRateController.Mode.ACTIVE, 3.0)
        modes = modes.note(AnalysisRateController.Mode.ACTIVE, 3.5)
        assertEquals(2.0, modes.seconds(AnalysisRateController.Mode.ACTIVE), 1e-9)
        assertEquals(0.5, modes.seconds(AnalysisRateController.Mode.RESTING), 1e-9)
        assertEquals(2.0, modes.seconds(AnalysisRateController.Mode.QUIET), 1e-9)
        assertEquals(4.5, modes.total, 1e-9)
        assertTrue(abs(modes.percent(AnalysisRateController.Mode.QUIET)!! - 44.444) < 0.01)

        val clock = Clock()
        val meter = meter(clock)
        meter.noteAnalysisMode(AnalysisRateController.Mode.QUIET, 0.0)
        meter.noteAnalysisMode(AnalysisRateController.Mode.QUIET, 1.0)
        assertEquals(0.0, meter.snapshot().modes.total, 0.0)
        meter.reset()
        meter.noteAnalysisMode(AnalysisRateController.Mode.ACTIVE, 0.0)
        meter.noteAnalysisMode(AnalysisRateController.Mode.RESTING, 0.5)
        val beforeStop = meter.snapshot()
        meter.end()
        meter.noteAnalysisMode(AnalysisRateController.Mode.QUIET, 1.0)
        assertEquals(beforeStop.modes, meter.snapshot().modes)
        meter.reset()
        assertEquals(0.0, meter.snapshot().modes.total, 0.0)
    }

    companion object {
        private const val SECOND = 1_000_000_000L
    }
}
