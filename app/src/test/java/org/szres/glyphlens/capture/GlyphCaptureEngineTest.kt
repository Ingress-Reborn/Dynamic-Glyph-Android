package org.szres.glyphlens.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.szres.glyphlens.diagnostics.PowerMeter
import org.szres.glyphlens.diagnostics.PowerMetric
import org.szres.glyphlens.recognition.CaptureSessionID
import org.szres.glyphlens.recognition.GlyphID
import org.szres.glyphlens.recognition.GlyphPath
import org.szres.glyphlens.recognition.GlyphTable
import org.szres.glyphlens.recognition.PhaseSource
import org.szres.glyphlens.recognition.RgbaFrame
import org.szres.glyphlens.recognition.TrackingState
import org.szres.glyphlens.recognition.image.AnalysisRateController

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
    fun visibleSlotsOrTimerKeepFullPaceBeforeTheArrayIsRecognized() {
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
    fun pixelsStartPresentationAndTheInputTimerFreezesResultsAndSkipsShapeWork() {
        val meter = PowerMeter().apply { reset() }
        fixture(meter = meter).use { engine ->
            for (at in listOf(1.0, 1.11, 1.22)) {
                engine.process(glyphScreen(at, rings = true, slot = true), power)
            }
            assertEquals(TrackingState.INACTIVE, engine.latest.state)
            engine.process(glyphScreen(1.33, rings = true, slot = true, highlighted = true), power)
            assertEquals(TrackingState.PRESENTING, engine.latest.state)
            assertEquals(PhaseSource.INDICATOR_TRANSITION, engine.latest.phaseSource)

            engine.process(glyphScreen(2.0, rings = true, slot = true, timer = true), power)
            assertEquals(TrackingState.INPUT, engine.latest.state)
            assertEquals(PhaseSource.INPUT_TIMER, engine.latest.phaseSource)
            assertTrue(engine.latest.isFrozen)
            val frozen = engine.latest
            val sampledMetrics = listOf(PowerMetric.FRAMES_ANALYSED, PowerMetric.GLYPH_READ, PowerMetric.GRID_HUNT)
            val beforeInputCounts = sampledMetrics.associateWith { meter.snapshot().total(it).count }
            for (at in listOf(2.05, 2.11, 2.22, 2.33, 2.44, 2.55, 2.66)) {
                engine.process(glyphScreen(at, rings = true, slot = true, timer = true), power)
                assertEquals(TrackingState.INPUT, engine.latest.state)
                assertEquals(PhaseSource.INPUT_TIMER, engine.latest.phaseSource)
                assertTrue(engine.latest.isFrozen)
                assertEquals(frozen.hackID, engine.latest.hackID)
                assertEquals(frozen.slots, engine.latest.slots)
                assertEquals(at, engine.latest.sourceFrameTime, 1e-8)
            }
            val afterInputCounts = sampledMetrics.associateWith { meter.snapshot().total(it).count }
            assertEquals(beforeInputCounts.getValue(PowerMetric.FRAMES_ANALYSED) + 7,
                afterInputCounts.getValue(PowerMetric.FRAMES_ANALYSED))
            for (metric in listOf(PowerMetric.GLYPH_READ, PowerMetric.GRID_HUNT)) {
                assertEquals("Frozen input must skip $metric", beforeInputCounts.getValue(metric),
                    afterInputCounts.getValue(metric))
            }

            engine.process(glyphScreen(2.8, rings = true, slot = true), power)
            assertEquals(TrackingState.INPUT, engine.latest.state)
            assertEquals(PhaseSource.RETAINED, engine.latest.phaseSource)
            assertEquals(afterInputCounts.getValue(PowerMetric.GLYPH_READ) + 1,
                meter.snapshot().total(PowerMetric.GLYPH_READ).count)
            assertTrue(engine.latest.isFrozen)
            assertEquals(frozen.slots, engine.latest.slots)

            engine.process(glyphScreen(2.91, rings = true, slot = true), power)
            assertEquals(PhaseSource.RETAINED, engine.latest.phaseSource)
            assertEquals(frozen.slots, engine.latest.slots)
        }
    }

    @Test
    fun aMapControlPreservesResultsAndLetsTheNextPixelSequenceStartANewHack() {
        val meter = PowerMeter().apply { reset() }
        fixture(meter = meter).use { engine ->
            engine.process(mapScreen(1.0), power)
            assertTrue(engine.latest.hasMapControl)
            assertEquals(0, engine.latest.hackID)
            assertEquals(TrackingState.INACTIVE, engine.latest.state)
            assertEquals(0L, meter.snapshot().total(PowerMetric.GLYPH_READ).count)
            assertEquals(0L, meter.snapshot().total(PowerMetric.GRID_HUNT).count)

            for (at in listOf(1.4, 1.51, 1.62)) engine.process(glyphScreen(at, rings = true, slot = true), power)
            engine.process(glyphScreen(1.73, rings = true, slot = true, highlighted = true), power)
            assertFalse(engine.latest.hasMapControl)
            assertEquals(TrackingState.PRESENTING, engine.latest.state)
            engine.process(glyphScreen(2.0, rings = true, slot = true, timer = true), power)
            val input = engine.latest
            assertEquals(TrackingState.INPUT, input.state)
            val shapes = meter.snapshot().total(PowerMetric.GLYPH_READ).count
            val hunts = meter.snapshot().total(PowerMetric.GRID_HUNT).count
            for (at in listOf(2.4, 2.7, 3.0)) {
                engine.process(mapScreen(at), power)
                assertTrue(engine.latest.hasMapControl)
                assertEquals(input.hackID, engine.latest.hackID)
                assertEquals(TrackingState.INTERRUPTED, engine.latest.state)
                assertEquals(input.slots, engine.latest.slots)
                assertEquals(input.outlook, engine.latest.outlook)
            }
            assertEquals(shapes, meter.snapshot().total(PowerMetric.GLYPH_READ).count)
            assertEquals(hunts, meter.snapshot().total(PowerMetric.GRID_HUNT).count)

            for (at in listOf(4.0, 4.11, 4.22)) engine.process(glyphScreen(at, rings = true, slot = true), power)
            engine.process(glyphScreen(4.33, rings = true, slot = true, highlighted = true), power)
            assertFalse(engine.latest.hasMapControl)
            assertEquals(TrackingState.PRESENTING, engine.latest.state)
            assertEquals(input.hackID + 1, engine.latest.hackID)
            assertFalse(engine.latest.isFrozen)
        }
    }

    @Test
    fun repeatedSourceFramesDoNotRefreshTheSnapshotOrAnalyseAgain() {
        val meter = PowerMeter().apply { reset() }
        fixture(meter = meter).use { engine ->
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
            assertEquals(1, meter.snapshot().total(PowerMetric.FRAMES_ANALYSED).count)
        }
    }

    @Test
    fun pixelsCopiedOnRequestGiveTheSameSnapshotsAndCopyOnlyAnalysedFrames() {
        val frames = buildList {
            // A quiet bright screen, the map, an empty array, then strokes: every pace the engine has.
            for (step in 0 until 60) add(frame(1.0 + step * 0.05).also { it.pixels.fill(255.toByte()) })
            for (step in 0 until 60) add(mapScreen(4.0 + step * 0.05))
            for (step in 0 until 60) add(glyphScreen(7.0 + step * 0.05, rings = true, slot = true))
            for (step in 0 until 20) {
                add(glyphScreen(10.0 + step * 0.05, rings = true, slot = true, highlighted = true))
            }
            for (step in 0 until 20) add(glyphScreen(11.0 + step * 0.05, rings = true, slot = true, timer = true))
        }
        val copiedMeter = PowerMeter()
        val lazyMeter = PowerMeter()
        fixture(meter = copiedMeter).use { copied ->
            fixture(meter = lazyMeter).use { lazy ->
                var rowReads = 0
                var frameReads = 0
                for (frame in frames) {
                    val expected = copied.process(frame, power)
                    val actual = lazy.process(
                        object : GlyphCaptureEngine.FramePixels {
                            override fun rows(firstRow: Int, rowStep: Int): ByteArray {
                                rowReads++
                                return FrameConverter.readRows(
                                    frame.width, frame.height, 4, frame.width * 4,
                                    java.nio.ByteBuffer.wrap(frame.pixels), firstRow, rowStep,
                                )
                            }

                            override fun frame(): RgbaFrame {
                                frameReads++
                                return frame
                            }
                        },
                        frame.timestampNanos, frame.width, frame.height,
                        CaptureFrameStatus.COMPLETE, continuityBroken = false, power,
                    )
                    assertEquals("snapshot at ${frame.timestampNanos}", expected, actual)
                }
                val analysed = lazyMeter.snapshot().total(PowerMetric.FRAMES_ANALYSED).count
                assertEquals(copiedMeter.snapshot().total(PowerMetric.FRAMES_ANALYSED).count, analysed)
                assertEquals(analysed, frameReads.toLong())
                assertTrue("quiet frames must be skipped ($analysed of ${frames.size})", analysed < frames.size - 40)
                assertTrue("the gate reads rows outside a hack only ($rowReads)", rowReads in 1 until frames.size)
                assertEquals(TrackingState.INPUT, lazy.latest.state)
            }
        }
    }

    private fun fixture(
        meter: PowerMeter = PowerMeter(),
        uptimeNanos: () -> Long = { 0L },
        powerSaving: Boolean = true,
    ): GlyphCaptureEngine = GlyphCaptureEngine(
        shapeTable = table,
        sequenceTable = null,
        sessionID = session,
        powerSaving = { powerSaving },
        meter = meter,
        uptimeNanos = uptimeNanos,
    )

    private fun frame(at: Double) = RgbaFrame(
        20,
        40,
        (at * 1_000_000_000.0).toLong(),
        ByteArray(20 * 40 * 4),
    )

    private fun mapScreen(at: Double): RgbaFrame {
        val width = 320
        val height = 520
        val pixels = ByteArray(width * height * 4)
        for (y in 130..160) {
            for (x in 10..40) {
                val outer = x == 10 || x == 40 || y == 130 || y == 160
                val inner = x in 15..35 && y in 135..155 && (x == 15 || x == 35 || y == 135 || y == 155)
                val plus = x in 24..26 && y in 140..150 || y in 144..146 && x in 20..30
                val color = when {
                    plus -> intArrayOf(230, 230, 240)
                    inner -> intArrayOf(185, 185, 220)
                    outer -> intArrayOf(100, 110, 160)
                    else -> continue
                }
                val offset = (y * width + x) * 4
                for (channel in 0..2) pixels[offset + channel] = color[channel].toByte()
            }
        }
        return RgbaFrame(width, height, (at * 1_000_000_000.0).toLong(), pixels)
    }

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
