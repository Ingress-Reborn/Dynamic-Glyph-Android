package org.szres.glyphlens.recognition.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.szres.glyphlens.recognition.RgbaFrame
import kotlin.math.abs

class LumaGateTest {
    @Test
    fun darkOrDimFramesPassWhileBrightBusyFramesRest() {
        val dark = requireNotNull(LumaGate(1).read(64, 128) { _, _ -> 10 })
        assertEquals(1.0, dark.darkFraction, 0.0)
        assertTrue(dark.isCandidate)
        val grey = requireNotNull(LumaGate(1).read(64, 128) { _, _ -> 90 })
        assertEquals(1.0, grey.brightFraction, 0.0)
        assertFalse(grey.isCandidate)
    }

    @Test
    fun rgbaUsesRedFirstAndBt601WithoutVideoRangeStretch() {
        fun primary(red: Int, green: Int, blue: Int): Int {
            val frame = RgbaFrame(1, 1, 0, byteArrayOf(red.toByte(), green.toByte(), blue.toByte(), 255.toByte()))
            return requireNotNull(LumaGate(1).read(frame)).cells[0]
        }
        assertEquals(76, primary(255, 0, 0))
        assertEquals(149, primary(0, 255, 0))
        assertEquals(28, primary(0, 0, 255))
    }

    @Test
    fun sampledRowsAloneGiveTheWholeFramesReading() {
        val random = java.util.Random(11)
        for ((width, height) in listOf(603 to 1340, 320 to 520, 20 to 40, 7 to 9, 5 to 2, 1 to 1)) {
            for (step in listOf(1, 2, 4, 5)) {
                val gate = LumaGate(step)
                val pixels = ByteArray(width * height * 4).also(random::nextBytes)
                // Mostly dark, so the candidate thresholds are exercised too.
                for (index in pixels.indices) if (random.nextInt(3) != 0) pixels[index] = random.nextInt(24).toByte()
                val rows = ByteArray(gate.sampledRowCount(height) * width * 4)
                for (row in 0 until gate.sampledRowCount(height)) {
                    val source = (gate.firstSampledRow + row * step) * width * 4
                    pixels.copyInto(rows, row * width * 4, source, source + width * 4)
                }
                assertEquals(
                    "$width×$height step $step",
                    gate.read(RgbaFrame(width, height, 0, pixels)),
                    gate.readSampledRows(rows, width, height),
                )
            }
        }
    }

    @Test
    fun paddedPlaneMatchesDecodedPixelsAndVideoRangeIsExplicit() {
        val image = LuminanceImage(7, 9, ByteArray(63) { (it * 3).toByte() })
        val decoded = LumaGate(1).read(image)
        val stride = 20
        val plane = ByteArray(stride * image.height) { 255.toByte() }
        for (y in 0 until image.height) {
            for (x in 0 until image.width) plane[y * stride + x] = image.pixels[y * image.width + x]
        }
        assertEquals(decoded, LumaGate(1).readPlane(plane, 7, 9, stride, videoRange = false))
        assertEquals(0, LumaGate.VIDEO_RANGE_TABLE[16])
        assertEquals(255, LumaGate.VIDEO_RANGE_TABLE[235])
        assertEquals(0, LumaGate.VIDEO_RANGE_TABLE[0])
        assertEquals(255, LumaGate.VIDEO_RANGE_TABLE[255])
        val black = ByteArray(64 * 128) { 16 }
        val video = requireNotNull(LumaGate().readPlane(black, 64, 128, videoRange = true))
        assertEquals(1.0, video.darkFraction, 0.0)
        assertTrue(video.isCandidate)
    }

    @Test
    fun gridDifferenceAndMeanAreExplicit() {
        val first = LumaGateReading(0.9, 0.0, listOf(3, 9), true)
        assertEquals(0, first.difference(first))
        assertEquals(6.0, first.meanLuma, 0.0)
        assertEquals(12, first.difference(LumaGateReading(0.8, 0.1, listOf(3, 21), true)))
        assertEquals(Int.MAX_VALUE, first.difference(LumaGateReading(0.0, 0.0, emptyList(), false)))
    }
}

class AnalysisRateControllerTest {
    private val map = LumaGateReading(0.05, 0.4, listOf(40, 60), false)
    private val mapScrolled = LumaGateReading(0.06, 0.45, listOf(80, 20), false)
    private val hack = LumaGateReading(0.95, 0.02, listOf(3, 9), true)
    private val glyph = LumaGateReading(0.93, 0.04, listOf(3, 15), true)
    private val active = AnalysisRateController.Decision(AnalysisRateController.Mode.ACTIVE, 0.049)

    @Test
    fun mapBacksOffAndCandidateReturnGetsImmediateLookAndBurst() {
        val controller = AnalysisRateController()
        val decisions = run(controller, 10.0, 3.0) { if (it % 2 == 0) map else mapScrolled }
        assertTrue(decisions.take(40).all { it == active })
        assertTrue(decisions.takeLast(19).all {
            it == AnalysisRateController.Decision(AnalysisRateController.Mode.QUIET, 0.249)
        })
        assertEquals(active, controller.decide(13.01, hack, false, false, false))
        assertTrue(controller.enteredCandidate)
        assertEquals(AnalysisRateController.Mode.RESTING, controller.mode)
        assertEquals(active, controller.decide(13.06, glyph, false, false, false))
        assertFalse(controller.enteredCandidate)
        assertTrue(controller.switchedScreen)
        assertEquals(
            AnalysisRateController.Decision(AnalysisRateController.Mode.RESTING, 0.249),
            controller.decide(14.06, glyph, false, false, false),
        )
        run(controller, 14.0, 3.0, constrained = true) { map }
        assertEquals(active, controller.decide(17.0, hack, false, false, true))
    }

    @Test
    fun stillCandidateRestsButOnlyPositiveEvidenceWakes() {
        val controller = AnalysisRateController()
        val still = run(controller, 0.0, 5.0) { hack }
        assertTrue(still.take(40).all { it.mode == AnalysisRateController.Mode.ACTIVE })
        assertTrue(still.drop(40).all {
            it == AnalysisRateController.Decision(AnalysisRateController.Mode.RESTING, 0.249)
        })
        assertEquals(
            AnalysisRateController.Decision(AnalysisRateController.Mode.RESTING, 0.249),
            controller.decide(5.0, glyph, false, false, false),
        )
        assertEquals(active, controller.decide(5.05, glyph, true, false, false))
        run(controller, 6.0, 5.0) { hack }
        assertEquals(AnalysisRateController.Mode.QUIET, controller.decide(11.5, map, false, false, false).mode)
    }

    @Test
    fun hackOrBusyReaderNeverSlows() {
        val controller = AnalysisRateController()
        assertTrue(run(controller, 0.0, 6.0, inHack = true) { map }.all { it == active })
        assertTrue(run(controller, 6.0, 6.0, busy = true) { map }.all {
            it.mode == AnalysisRateController.Mode.ACTIVE
        })
    }

    @Test
    fun framesWithoutPixelsNeitherWakeNorRest() {
        val controller = AnalysisRateController()
        assertTrue(run(controller, 0.0, 6.0) { null }.all { it.mode == AnalysisRateController.Mode.ACTIVE })
        run(controller, 6.0, 3.0) { map }
        assertEquals(AnalysisRateController.Mode.QUIET, controller.mode)
        assertTrue(run(controller, 9.0, 1.0) { null }.all { it.mode == AnalysisRateController.Mode.QUIET })
    }

    @Test
    fun constrainedRunsHalfRateOutsideHack() {
        val controller = AnalysisRateController()
        assertEquals(
            AnalysisRateController.Decision(AnalysisRateController.Mode.ACTIVE, 0.099),
            controller.decide(0.0, hack, false, false, true),
        )
        assertEquals(active, controller.decide(0.1, glyph, false, true, true))
        run(controller, 1.0, 3.0, constrained = true) { map }
        assertEquals(
            AnalysisRateController.Decision(AnalysisRateController.Mode.QUIET, 0.499),
            controller.decide(4.0, map, false, false, true),
        )
    }

    @Test
    fun restartedClockStartsAtFullRate() {
        val controller = AnalysisRateController()
        run(controller, 100.0, 3.0) { map }
        assertEquals(AnalysisRateController.Mode.QUIET, controller.mode)
        assertEquals(AnalysisRateController.Mode.ACTIVE, controller.decide(5.0, map, false, false, false).mode)
    }

    @Test
    fun stillLooksSettleExactlyAsUnchangedFrames() {
        for (reading in listOf(hack, map)) {
            val still = AnalysisRateController()
            val frames = AnalysisRateController()
            still.decide(0.0, reading, false, false, false)
            frames.decide(0.0, reading, false, false, false)
            val looks = stillLooks(still, 0.0, 6.0)
            val unchanged = (1..24).map {
                frames.decide(it * 0.25, reading, false, false, false)
            }
            assertEquals(unchanged, looks)
        }
        val controller = AnalysisRateController()
        controller.decide(10.0, hack, false, false, false)
        val resting = stillLooks(controller, 10.0, 5.0)
        assertTrue(resting.take(7).all { it.mode == AnalysisRateController.Mode.ACTIVE })
        assertTrue(resting.drop(7).all {
            it == AnalysisRateController.Decision(AnalysisRateController.Mode.RESTING, 0.249)
        })
    }

    @Test
    fun stillLooksNeverSlowBusyOrHackAndSettleAfterRelease() {
        val controller = AnalysisRateController()
        controller.decide(0.0, map, false, true, false)
        assertTrue(stillLooks(controller, 0.0, 6.0, inHack = true).all {
            it.mode == AnalysisRateController.Mode.ACTIVE
        })
        assertTrue(stillLooks(controller, 6.0, 6.0, busy = true).all {
            it.mode == AnalysisRateController.Mode.ACTIVE
        })
        val after = stillLooks(controller, 12.0, 3.0)
        assertTrue(after.take(7).all { it.mode == AnalysisRateController.Mode.ACTIVE })
        assertEquals(AnalysisRateController.Mode.QUIET, after.last().mode)
    }

    @Test
    fun stillWithoutPixelReadingProvesNothing() {
        val controller = AnalysisRateController()
        run(controller, 0.0, 1.0) { null }
        assertTrue(stillLooks(controller, 1.0, 6.0).all { it.mode == AnalysisRateController.Mode.ACTIVE })
        controller.decide(8.0, map, false, false, false)
        assertTrue(run(controller, 8.05, 4.0) { null }.all { it.mode == AnalysisRateController.Mode.ACTIVE })
    }

    @Test
    fun frameAfterStillLooksUsesOwnEvidenceAndRealClockReversalResets() {
        val controller = AnalysisRateController()
        controller.decide(100.0, hack, false, false, false)
        stillLooks(controller, 100.0, 5.0)
        assertEquals(AnalysisRateController.Mode.RESTING, controller.mode)
        assertEquals(
            AnalysisRateController.Decision(AnalysisRateController.Mode.RESTING, 0.249),
            controller.decide(104.98, hack, false, false, false),
        )
        assertEquals(active, controller.decide(104.995, glyph, true, false, false))
        val settling = run(controller, 105.0, 2.5) { hack }
        assertTrue(settling.take(40).all { it.mode == AnalysisRateController.Mode.ACTIVE })
        assertEquals(AnalysisRateController.Mode.RESTING, settling.last().mode)
        stillLooks(controller, 105.0, 5.0)
        assertEquals(
            AnalysisRateController.Decision(AnalysisRateController.Mode.RESTING, 0.249),
            controller.advanceStill(50.0, false, false, false),
        )
        assertEquals(AnalysisRateController.Mode.ACTIVE, controller.decide(5.0, hack, false, false, false).mode)
    }

    @Test
    fun darkChangingMapDoesNotKeepWakingAnalysis() {
        for (constrained in listOf(false, true)) {
            val night = LumaGateReading(0.7, 0.05, listOf(10, 22), true)
            val moved = LumaGateReading(0.68, 0.06, listOf(22, 10), true)
            assertTrue(night.difference(moved) > AnalysisRateController.Policy().changeThreshold)
            val controller = AnalysisRateController()
            val decisions = run(controller, 50.0, 60.0, constrained = constrained) {
                if (it % 2 == 0) night else moved
            }
            val slow = if (constrained) 0.499 else 0.249
            assertTrue(decisions.take(40).all { it.mode == AnalysisRateController.Mode.ACTIVE })
            assertTrue(decisions.drop(40).all {
                it == AnalysisRateController.Decision(AnalysisRateController.Mode.RESTING, slow)
            })
            assertFalse(controller.enteredCandidate)
        }
    }

    @Test
    fun evidenceWakesOnSameLookConstrainedOrNot() {
        val night = LumaGateReading(0.7, 0.05, listOf(10, 22), true)
        for (constrained in listOf(false, true)) {
            val controller = AnalysisRateController()
            run(controller, 0.0, 3.0, constrained = constrained) { night }
            assertEquals(
                AnalysisRateController.Decision(AnalysisRateController.Mode.ACTIVE, if (constrained) 0.099 else 0.049),
                controller.decide(3.0, night, true, false, constrained),
            )
            assertEquals(active, controller.decide(3.05, night, false, true, constrained))
            val after = run(controller, 3.1, 3.0, constrained = constrained) { night }
            assertTrue(after.take(39).all { it.mode == AnalysisRateController.Mode.ACTIVE })
            assertTrue(after.takeLast(15).all { it.mode == AnalysisRateController.Mode.RESTING })
            assertEquals(
                AnalysisRateController.Mode.ACTIVE,
                controller.advanceStill(6.2, true, false, constrained).mode,
            )
        }
    }

    @Test
    fun candidateScreenSwitchGetsImmediateLookThenOneSecondBurst() {
        for (constrained in listOf(false, true)) {
            val night = LumaGateReading(0.7, 0.05, listOf(10, 22), true)
            val moved = LumaGateReading(0.68, 0.06, listOf(22, 10), true)
            assertTrue(abs(night.meanLuma - hack.meanLuma) >= AnalysisRateController.Policy().switchMeanJump)
            val slow = if (constrained) 0.499 else 0.249
            val burst = if (constrained) 0.099 else 0.049
            val controller = AnalysisRateController()
            val before = run(controller, 0.0, 3.0, constrained = constrained) {
                if (it % 2 == 0) night else moved
            }
            assertTrue(before.takeLast(20).all {
                it == AnalysisRateController.Decision(AnalysisRateController.Mode.RESTING, slow)
            })
            assertEquals(active, controller.decide(3.0, hack, false, false, constrained))
            assertTrue(controller.switchedScreen)
            val after = run(controller, 3.05, 1.5, constrained = constrained) {
                if (it % 2 == 0) hack else glyph
            }
            assertTrue(after.take(19).all {
                it == AnalysisRateController.Decision(AnalysisRateController.Mode.ACTIVE, burst)
            })
            assertTrue(after.drop(20).all {
                it == AnalysisRateController.Decision(AnalysisRateController.Mode.RESTING, slow)
            })

            val fading = AnalysisRateController()
            run(fading, 0.0, 3.0, constrained = constrained) { night }
            val steps = listOf(listOf(8, 20), listOf(7, 17), listOf(5, 13), listOf(3, 9)).map {
                LumaGateReading(0.8, 0.04, it, true)
            }
            val fade = steps.mapIndexed { index, gate ->
                fading.decide(3.0 + index * 0.05, gate, false, false, constrained)
            }
            assertEquals(active, fade.last())
        }
    }

    private fun run(
        controller: AnalysisRateController,
        start: Double,
        seconds: Double,
        busy: Boolean = false,
        inHack: Boolean = false,
        constrained: Boolean = false,
        gate: (Int) -> LumaGateReading?,
    ): List<AnalysisRateController.Decision> = (0 until (seconds * 20).toInt()).map { index ->
        controller.decide(start + index * 0.05, gate(index), busy, inHack, constrained)
    }

    private fun stillLooks(
        controller: AnalysisRateController,
        start: Double,
        seconds: Double,
        busy: Boolean = false,
        inHack: Boolean = false,
    ): List<AnalysisRateController.Decision> = (1..(seconds * 4).toInt()).map { index ->
        controller.advanceStill(start + index * 0.25, busy, inHack, false)
    }
}
