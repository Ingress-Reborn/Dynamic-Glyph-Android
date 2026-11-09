package org.szres.glyphlens.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.szres.glyphlens.diagnostics.PowerMeter
import org.szres.glyphlens.diagnostics.PowerMetric
import org.szres.glyphlens.ocr.GameOcrOperation
import org.szres.glyphlens.ocr.GameOcrOutcome
import org.szres.glyphlens.recognition.CaptureSessionID
import org.szres.glyphlens.recognition.GlyphID
import org.szres.glyphlens.recognition.GlyphPath
import org.szres.glyphlens.recognition.GlyphTable
import org.szres.glyphlens.recognition.RgbaFrame
import org.szres.glyphlens.recognition.TrackingState
import org.szres.glyphlens.recognition.image.AnalysisRateController
import org.szres.glyphlens.recognition.schedule.GameTextSchedule
import org.szres.glyphlens.recognition.text.GameLanguage
import org.szres.glyphlens.recognition.text.GameLanguageMemory
import org.szres.glyphlens.recognition.text.GamePhase
import org.szres.glyphlens.recognition.text.HeaderReading
import java.util.concurrent.Executor

class GlyphCaptureEngineTest {
    private val session = CaptureSessionID(91uL)
    private val power = PowerConditions.State(lowPowerMode = false, thermalStatus = 0)

    @Test
    fun realPipelineCountsDeliveredAnalysedAndThrottledFrames() {
        var now = 1_000_000_000L
        val meter = PowerMeter(clock = { now += 1_000_000L; now })
        fixture(meter = meter, uptimeNanos = { now }).use { engine ->
            meter.reset()
            engine.process(frame(1.0), power)
            engine.process(frame(1.01), power)

            val snapshot = meter.snapshot()
            assertEquals(2, snapshot.total(PowerMetric.FRAMES_DELIVERED).count)
            assertEquals(1, snapshot.total(PowerMetric.FRAMES_ANALYSED).count)
            assertEquals(1, snapshot.total(PowerMetric.SKIPPED_FRAME).count)
            assertEquals(1, snapshot.total(PowerMetric.FRAME_WORK).count)
            assertEquals(1, snapshot.total(PowerMetric.CONVERSION).count)
            assertTrue(snapshot.readingMode != null)
            assertFalse(engine.diagnostics.isReadingText)
        }
    }

    @Test
    fun powerSavingReducesAnalysisOnAQuietScreen() {
        for (powerSaving in listOf(false, true)) {
            val meter = PowerMeter()
            fixture(meter = meter, powerSaving = powerSaving).use { engine ->
                meter.reset()
                for (at in listOf(1.0, 3.1, 3.15, 3.2)) {
                    val brightFrame = frame(at)
                    brightFrame.pixels.fill(255.toByte())
                    engine.process(brightFrame, power)
                }

                val snapshot = meter.snapshot()
                assertEquals(4L, snapshot.total(PowerMetric.FRAMES_DELIVERED).count)
                assertEquals(if (powerSaving) 2L else 4L, snapshot.total(PowerMetric.FRAMES_ANALYSED).count)
                assertEquals(
                    if (powerSaving) AnalysisRateController.Mode.QUIET else AnalysisRateController.Mode.ACTIVE,
                    snapshot.readingMode?.mode,
                )
            }
        }
    }

    @Test
    fun recentlyMatchedArrayKeepsFullPaceButCachedGeometryDoesNotKeepItForever() {
        val meter = PowerMeter().apply { reset() }
        fixture(meter = meter).use { engine ->
            for (at in listOf(1.0, 1.5, 2.0, 2.5, 3.0)) {
                engine.process(glyphScreen(at, rings = true), power)
            }
            assertTrue(engine.latest.calibrationSummary.startsWith("11 rings"))
            assertEquals(TrackingState.INACTIVE, engine.latest.state)
            assertEquals(AnalysisRateController.Mode.ACTIVE, meter.snapshot().readingMode?.mode)

            for (at in listOf(3.5, 4.0, 4.5, 5.0, 5.5, 6.0, 6.5, 7.0)) {
                engine.process(glyphScreen(at), power)
            }
            assertTrue("Geometry remains cached after its screen evidence expires",
                engine.latest.calibrationSummary.startsWith("11 rings"))
            assertEquals(AnalysisRateController.Mode.RESTING, meter.snapshot().readingMode?.mode)
        }
    }

    @Test
    fun visibleSlotsOrTimerKeepFullPaceBeforeTheHeaderOrArrayIsRecognized() {
        for (timer in listOf(false, true)) {
            val meter = PowerMeter().apply { reset() }
            fixture(meter = meter).use { engine ->
                for (at in listOf(1.0, 1.5, 2.0, 2.5, 3.0, 3.5)) {
                    engine.process(glyphScreen(at, slot = !timer, timer = timer), power)
                }
                assertEquals(TrackingState.INACTIVE, engine.latest.state)
                assertEquals("Cue: ${if (timer) "timer" else "slots"}",
                    AnalysisRateController.Mode.ACTIVE, meter.snapshot().readingMode?.mode)
            }
        }
    }

    @Test
    fun aContinuityBreakCannotReuseThePreviousEpochsFreshArrayAsActivity() {
        val meter = PowerMeter().apply { reset() }
        fixture(meter = meter).use { engine ->
            engine.process(glyphScreen(1.0, rings = true), power)
            engine.process(
                CapturedFrame(glyphScreen(1.1), arrivalUptimeNanos = 0L, continuityBroken = true),
                power,
            )
            for (at in listOf(1.6, 2.1, 2.6, 3.2)) engine.process(glyphScreen(at), power)
            assertEquals(AnalysisRateController.Mode.RESTING, meter.snapshot().readingMode?.mode)
        }
    }

    @Test
    fun establishedPixelPhasesKeepTheIosHeaderCadence() {
        val submissions = mutableListOf<GameTextSchedule.Request>()
        var phase = GamePhase.presenting
        fixture(operation = GameOcrOperation { work ->
            submissions += work.request
            GameOcrOutcome(header = HeaderReading(phase, phase.rawValue, 1.0, work.request.sourceTime))
        }).use { engine ->
            engine.process(glyphScreen(1.0, rings = true, slot = true), power)
            engine.process(glyphScreen(1.05, rings = true, slot = true), power)
            for (step in 1..8) {
                engine.process(glyphScreen(1.0 + step * 0.11, rings = true, slot = true, highlighted = true), power)
            }
            assertEquals(TrackingState.PRESENTING, engine.latest.state)
            assertEquals("Pixel confirmation must not restore the old 250 ms slowdown", 9, submissions.size)
            for ((previous, current) in submissions.zipWithNext()) {
                assertEquals(0.11, current.sourceTime - previous.sourceTime, 1e-8)
            }

            phase = GamePhase.input
            engine.process(glyphScreen(2.0, rings = true, slot = true, timer = true), power)
            engine.process(glyphScreen(2.05, rings = true, slot = true, timer = true), power)
            assertEquals(TrackingState.INPUT, engine.latest.state)
            assertTrue(engine.latest.isFrozen)
            val beforeInputReads = submissions.size
            for (step in 1..4) {
                engine.process(glyphScreen(2.0 + step * 0.11, rings = true, slot = true, timer = true), power)
            }
            assertEquals(beforeInputReads + 4, submissions.size)
        }
    }

    @Test
    fun repeatedSourceFramesDoNotRefreshTheSnapshotOrReadHeaderAgain() {
        val submissions = mutableListOf<GameTextSchedule.Request>()
        fixture(operation = GameOcrOperation { work ->
            submissions += work.request
            GameOcrOutcome(header = HeaderReading(GamePhase.presenting, "GLYPH SEQUENCE", 1.0, work.request.sourceTime))
        }).use { engine ->
            engine.process(glyphScreen(1.0, rings = true, slot = true), power)
            val snapshot = engine.latest
            engine.process(
                CapturedFrame(
                    glyphScreen(1.0, rings = true, slot = true),
                    status = CaptureFrameStatus.IDLE,
                    arrivalUptimeNanos = 3_000_000_000L,
                ),
                power,
            )
            assertEquals(snapshot, engine.latest)
            assertEquals(1, submissions.size)
        }
    }

    private fun fixture(
        operation: GameOcrOperation = GameOcrOperation { GameOcrOutcome() },
        meter: PowerMeter = PowerMeter(),
        uptimeNanos: () -> Long = { 0L },
        powerSaving: Boolean = true,
    ): GlyphCaptureEngine {
        val header = HeaderOcrProcessor(
            languages = GameLanguageMemory(GameLanguage.english, isAutomatic = false),
            completionExecutor = Executor { it.run() },
            workerExecutor = Executor { it.run() },
            operation = operation,
            closeWorker = {},
        )
        return GlyphCaptureEngine(
            shapeTable = table,
            sequenceTable = null,
            sessionID = session,
            header = header,
            powerSaving = { powerSaving },
            meter = meter,
            uptimeNanos = uptimeNanos,
        )
    }

    private fun frame(at: Double) = RgbaFrame(
        20,
        40,
        (at * 1_000_000_000.0).toLong(),
        ByteArray(20 * 40 * 4),
    )

    private fun glyphScreen(
        at: Double,
        rings: Boolean = false,
        slot: Boolean = false,
        timer: Boolean = false,
        highlighted: Boolean = false,
    ): RgbaFrame {
        val width = 320
        val height = 520
        val pixels = ByteArray(width * height * 4)
        // These rings and UI marks are drawn independently of detector sampling positions.
        if (rings) {
            val centers = listOf(
                160 to 130, 40 to 202, 280 to 202, 100 to 238, 220 to 238, 160 to 274,
                100 to 310, 220 to 310, 40 to 346, 280 to 346, 160 to 418,
            )
            for ((cx, cy) in centers) {
                for (y in cy - 8..cy + 8) {
                    for (x in cx - 8..cx + 8) {
                        val squaredRadius = (x - cx) * (x - cx) + (y - cy) * (y - cy)
                        if (squaredRadius !in 31..56) continue
                        val offset = (y * width + x) * 4
                        repeat(3) { pixels[offset + it] = 230.toByte() }
                    }
                }
            }
        }
        if (slot) {
            for (y in 56 until 90) {
                for (x in 143 until 177) {
                    if (y in 59 until 87 && x in 146 until 174) continue
                    val offset = (y * width + x) * 4
                    pixels[offset] = (if (highlighted) 240 else 185).toByte()
                    pixels[offset + 1] = (if (highlighted) 180 else 165).toByte()
                    pixels[offset + 2] = (if (highlighted) 50 else 240).toByte()
                }
            }
        }
        if (timer) {
            for (y in 117 until 120) {
                for (x in 35 until 285) {
                    val offset = (y * width + x) * 4
                    val progress = x in 95 until 225
                    pixels[offset] = (if (progress) 240 else 185).toByte()
                    pixels[offset + 1] = (if (progress) 180 else 165).toByte()
                    pixels[offset + 2] = (if (progress) 50 else 240).toByte()
                }
            }
        }
        return RgbaFrame(width, height, (at * 1_000_000_000.0).toLong(), pixels)
    }

    private companion object {
        val table = GlyphTable(
            mapOf(
                GlyphID("alpha") to requireNotNull(GlyphPath.fromEncoded("038")),
                GlyphID("beta") to requireNotNull(GlyphPath.fromEncoded("456")),
            ),
        )
    }
}
