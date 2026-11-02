package com.dynamicglyph.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The brightness gate and the analysis rate.
 *
 * The gate exists to keep the phone from analysing the map at 20 Hz, so the two
 * things worth pinning are that a dark game screen is a candidate and a bright
 * map is not.
 */
class AnalysisGateTest {

    private val gate = LumaGate()

    private fun frame(width: Int, height: Int, argb: Int): IntArray =
        IntArray(width * height) { argb }

    @Test
    fun `a black frame is a candidate`() {
        val reading = gate.read(100, 200) { _, _ -> 0 }
        assertNotNull(reading)
        assertEquals(1.0, reading!!.darkFraction, 1e-9)
        assertTrue(reading.isCandidate)
    }

    @Test
    fun `a bright frame is not a candidate`() {
        val reading = gate.read(100, 200) { _, _ -> 255 }
        assertNotNull(reading)
        assertEquals(0.0, reading!!.darkFraction, 1e-9)
        assertEquals(1.0, reading.brightFraction, 1e-9)
        assertFalse(reading.isCandidate)
    }

    @Test
    fun `a mid-grey frame is a candidate because it is not bright`() {
        // The rule is "very dark, or hardly bright at all": a dim game screen
        // with a glyph drawn on it passes on the second clause.
        val reading = gate.read(100, 200) { _, _ -> 40 }
        assertNotNull(reading)
        assertFalse(reading!!.darkFraction >= 0.6)
        assertTrue(reading.brightFraction <= 0.25)
        assertTrue(reading.isCandidate)
    }

    @Test
    fun `the grid covers the whole frame`() {
        val reading = gate.read(120, 240) { _, _ -> 0 }
        assertEquals(LumaGate.GRID_COLUMNS * LumaGate.GRID_ROWS, reading!!.cells.size)
    }

    @Test
    fun `luma is BT 601 on full range pixels`() {
        // No video-range stretch: Android delivers full-range buffers, and
        // stretching would move the gate's own thresholds.
        assertEquals(0, LumaGate.lumaOfArgb(0xFF000000.toInt()))
        assertEquals(255, LumaGate.lumaOfArgb(0xFFFFFFFF.toInt()))
        // Pure green dominates BT.601: 150/256 of 255 is 149.
        assertEquals(149, LumaGate.lumaOfArgb(0xFF00FF00.toInt()))
        // Pure red: 77/256 of 255 is 76.
        assertEquals(76, LumaGate.lumaOfArgb(0xFFFF0000.toInt()))
        // Pure blue: 29/256 of 255 is 28.
        assertEquals(28, LumaGate.lumaOfArgb(0xFF0000FF.toInt()))
    }

    @Test
    fun `a reading of the same screen has no difference`() {
        val a = gate.read(100, 200) { _, _ -> 10 }!!
        val b = gate.read(100, 200) { _, _ -> 10 }!!
        assertEquals(0, a.differenceFrom(b))
        assertEquals(a.meanLuma, b.meanLuma, 1e-9)
    }

    @Test
    fun `readings of different sizes never compare equal`() {
        val a = gate.read(100, 200) { _, _ -> 10 }!!
        val b = gate.read(120, 240) { _, _ -> 10 }!!
        assertEquals(Int.MAX_VALUE, a.differenceFrom(b))
    }

    @Test
    fun `the rate controller throttles to the active interval`() {
        val controller = AnalysisRateController()
        val dark = gate.read(100, 200) { _, _ -> 0 }!!

        // First look is always allowed.
        assertNotNull(controller.decide(0.000, dark, inHack = true, constrained = false))
        // Too soon.
        assertNull(controller.decide(0.020, dark, inHack = true, constrained = false))
        // Past the 49 ms active interval.
        assertNotNull(controller.decide(0.060, dark, inHack = true, constrained = false))
    }

    @Test
    fun `a constrained device halves the rate outside a hack`() {
        val controller = AnalysisRateController()
        val dark = gate.read(100, 200) { _, _ -> 0 }!!

        assertNotNull(controller.decide(0.000, dark, inHack = false, constrained = true))
        // 60 ms would pass at the unconstrained 49 ms, but not at 99 ms.
        assertNull(controller.decide(0.060, dark, inHack = false, constrained = true))
        assertNotNull(controller.decide(0.100, dark, inHack = false, constrained = true))
    }

    @Test
    fun `inside a hack the full rate survives a constrained device`() {
        val controller = AnalysisRateController()
        val dark = gate.read(100, 200) { _, _ -> 0 }!!

        assertNotNull(controller.decide(0.000, dark, inHack = true, constrained = true))
        // The blank between two Glyphs is about 100 ms and needs two samples, so
        // the full 49 ms rate has to hold even in low power mode.
        assertNotNull(controller.decide(0.060, dark, inHack = true, constrained = true))
    }
}
