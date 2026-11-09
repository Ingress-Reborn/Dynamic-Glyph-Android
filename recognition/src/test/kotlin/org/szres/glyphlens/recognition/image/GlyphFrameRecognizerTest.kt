package org.szres.glyphlens.recognition.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.szres.glyphlens.recognition.GlyphCandidate
import org.szres.glyphlens.recognition.GlyphPath
import org.szres.glyphlens.recognition.GlyphPoint
import org.szres.glyphlens.recognition.GlyphTable
import org.szres.glyphlens.recognition.NodeIndex
import kotlin.math.abs

class GlyphFrameRecognizerTest {
    @Test
    fun arrayFittingFollowsScaleAndTranslation() {
        for (scale in listOf(0.6, 1.0, 1.6)) {
            val canvas = SyntheticRaster(scale, GlyphPoint(-9.0, 17.0))
            val fit = requireNotNull(NodeArrayDetector().detect(canvas.image()))
            assertEquals(11, fit.matchedNodes.size)
            assertEquals(canvas.point(40.0, 130.0).x, fit.origin.x, 1.5)
            assertEquals(canvas.point(40.0, 130.0).y, fit.origin.y, 1.5)
            assertEquals(240 * scale, fit.size.x, 2.0)
        }
    }

    @Test
    fun independentlyDrawnShapesResolveWithoutOrderedPathInput() {
        val recognizer = GlyphFrameRecognizer(pixelTestTable())
        val cases = listOf(
            "advance" to listOf(160.0 to 130.0, 100.0 to 238.0, 40.0 to 346.0),
            "clear" to listOf(160.0 to 130.0, 160.0 to 418.0),
            "all" to listOf(
                160.0 to 130.0, 40.0 to 202.0, 40.0 to 346.0, 160.0 to 418.0,
                280.0 to 346.0, 280.0 to 202.0, 160.0 to 130.0,
            ),
            "balance" to listOf(
                160.0 to 130.0, 160.0 to 274.0, 100.0 to 310.0, 40.0 to 346.0,
                160.0 to 418.0, 280.0 to 346.0, 220.0 to 310.0,
                160.0 to 274.0,
            ),
        )
        for ((name, points) in cases) {
            val canvas = SyntheticRaster()
            val calibration = requireNotNull(NodeArrayDetector().detect(canvas.image()))
            draw(canvas, points)
            val result = recognizer.observe(canvas.image(), calibration)
            assertEquals(name, GlyphFrameObservation.State.UNIQUE_VISIBLE_SHAPE, result.state)
            assertEquals(name, listOf(id(name)), result.exactMatches)
        }
    }

    @Test
    fun faintTranslatedStrokesSurviveIndependentSparseNoise() {
        for (scale in listOf(0.6, 1.6)) {
            val canvas = SyntheticRaster(scale, GlyphPoint(-9.0, 17.0))
            val fit = requireNotNull(NodeArrayDetector().detect(canvas.image()))
            canvas.line(canvas.point(160.0, 130.0), canvas.point(160.0, 418.0), 85)
            var index = 17
            while (index < canvas.pixels.size) {
                canvas.pixels[index] = 180.toByte()
                index += 379
            }
            val result = GlyphFrameRecognizer(pixelTestTable()).observe(canvas.image(), fit)
            assertEquals(listOf(id("clear")), result.exactMatches)
        }
    }

    @Test
    fun isolatedPartialSegmentIsNotACompleteEdge() {
        val canvas = SyntheticRaster()
        val fit = requireNotNull(NodeArrayDetector().detect(canvas.image()))
        canvas.line(canvas.point(160.0, 170.0), canvas.point(160.0, 195.0))
        val result = GlyphFrameRecognizer(pixelTestTable()).observe(canvas.image(), fit)
        assertEquals(GlyphFrameObservation.State.UNRECOGNIZED_STROKE, result.state)
        assertTrue(result.edges.isEmpty())
        assertTrue(result.candidates.isEmpty())
    }

    @Test
    fun fadingPartsCombineWithinOneGlyphThenReset() {
        val recognizer = GlyphFrameRecognizer(pixelTestTable())
        val blank = SyntheticRaster()
        val fit = requireNotNull(NodeArrayDetector().detect(blank.image()))
        val accumulator = GlyphStrokeAccumulator(fit, recognizer)
        assertNull(accumulator.finish())
        for ((start, end) in listOf(130.0 to 230.0, 215.0 to 325.0, 310.0 to 418.0)) {
            val canvas = blank.copy()
            canvas.line(canvas.point(160.0, start), canvas.point(160.0, end))
            assertTrue(accumulator.append(canvas.image()).exactMatches.isEmpty())
        }
        val combined = requireNotNull(accumulator.finish(GlyphCompletion.PRESENTATION_BOUNDARY))
        assertEquals(listOf(id("clear")), combined.observation.exactMatches)
        assertEquals(id("clear"), combined.acceptedGlyph)
        assertEquals(3, combined.frameCount)
        assertNull(accumulator.finish())
        accumulator.append(blank.image())
        assertEquals(GlyphFrameObservation.State.NO_STROKE, accumulator.finish()?.observation?.state)
    }

    @Test
    fun geometryChangesRejectStaleCalibrationAndAccumulation() {
        val recognizer = GlyphFrameRecognizer(pixelTestTable())
        val blank = SyntheticRaster().image()
        val fit = requireNotNull(recognizer.calibrateAndObserve(blank).calibration)
        val resized = SyntheticRaster(0.6).image()
        assertEquals(
            GlyphFrameObservation.State.CALIBRATION_MISMATCH,
            recognizer.observe(resized, fit).state,
        )
        val accumulator = GlyphStrokeAccumulator(fit, recognizer)
        accumulator.append(blank)
        assertThrows(GlyphStrokeAccumulator.AccumulationError.ImageSizeChanged::class.java) {
            accumulator.append(resized)
        }
        assertNull(accumulator.finish())
    }

    @Test
    fun fixedCalibrationReusesSparseSamplingPlanAcrossFrames() {
        val recognizer = GlyphFrameRecognizer(pixelTestTable())
        val blank = SyntheticRaster()
        val fit = requireNotNull(NodeArrayDetector().detect(blank.image()))
        assertEquals(GlyphFrameObservation.State.NO_STROKE, recognizer.observe(blank.image(), fit).state)
        val samples = recognizer.lastSampleCount
        assertTrue(samples > 0)
        assertEquals(1, recognizer.samplingPlanBuildCount)

        val drawn = blank.copy()
        drawn.line(drawn.point(160.0, 130.0), drawn.point(160.0, 418.0))
        repeat(3) {
            assertEquals(listOf(id("clear")), recognizer.observe(drawn.image(), fit).exactMatches)
            assertEquals(samples, recognizer.lastSampleCount)
            assertEquals(1, recognizer.samplingPlanBuildCount)
        }

        val shifted = SyntheticRaster(offset = GlyphPoint(3.0, 7.0))
        val shiftedFit = requireNotNull(NodeArrayDetector().detect(shifted.image()))
        assertEquals(GlyphFrameObservation.State.NO_STROKE, recognizer.observe(shifted.image(), shiftedFit).state)
        assertEquals(2, recognizer.samplingPlanBuildCount)
    }

    @Test
    fun pixelReadsStaySparseAcrossArraySizes() {
        for (scale in listOf(0.6, 1.0, 1.6, 2.5)) {
            val canvas = SyntheticRaster(scale)
            val fit = requireNotNull(NodeArrayDetector().detect(canvas.image()))
            canvas.line(canvas.point(160.0, 130.0), canvas.point(160.0, 418.0))
            val recognizer = GlyphFrameRecognizer(pixelTestTable())
            assertEquals(GlyphFrameObservation.State.UNIQUE_VISIBLE_SHAPE, recognizer.observe(canvas.image(), fit).state)
            assertTrue("$scale: ${recognizer.lastSampleCount} pixel reads", recognizer.lastSampleCount < fit.size.x * fit.size.y / 2)
        }
    }

    @Test
    fun smallPartialStrokesStayVisibleBetweenGuardSamples() {
        for (scale in listOf(0.6, 1.0, 1.6)) {
            val blank = SyntheticRaster(scale)
            val fit = requireNotNull(NodeArrayDetector().detect(blank.image()))
            val recognizer = GlyphFrameRecognizer(pixelTestTable())
            for (offset in 0..5) {
                val canvas = blank.copy()
                canvas.line(canvas.point(160.0 + offset, 170.0), canvas.point(160.0 + offset, 195.0))
                val result = recognizer.observe(canvas.image(), fit)
                assertEquals("scale=$scale offset=$offset", GlyphFrameObservation.State.UNRECOGNIZED_STROKE, result.state)
                assertTrue(result.edges.isEmpty())
            }
        }
    }

    @Test
    fun offLatticeInkIsRejectedAcrossArraySizesAndPositions() {
        for (scale in listOf(0.6, 1.0, 1.6)) {
            val blank = SyntheticRaster(scale, GlyphPoint(-9.0, 17.0))
            val fit = requireNotNull(NodeArrayDetector().detect(blank.image()))
            val recognizer = GlyphFrameRecognizer(pixelTestTable())
            for (offset in listOf(0.0, 2.0, 5.0)) {
                val canvas = blank.copy()
                canvas.line(canvas.point(160.0, 130.0), canvas.point(160.0, 418.0))
                for (y in 170..190) {
                    canvas.line(canvas.point(60.0 + offset, y.toDouble()), canvas.point(135.0 + offset, y.toDouble()))
                    canvas.line(canvas.point(185.0 - offset, y + 190.0), canvas.point(260.0 - offset, y + 190.0))
                }
                val result = recognizer.observe(canvas.image(), fit)
                assertEquals(listOf(id("clear")), result.exactMatches)
                assertEquals("scale=$scale offset=$offset", GlyphFrameObservation.State.UNEXPLAINED_INK, result.state)
                assertFalse(result.hasUniqueVisibleShape)
                assertTrue(result.unexplainedInkPixelCount > result.strokePixelCount / 2)
            }
        }
    }

    @Test
    fun sameShapeAliasesRemainAmbiguous() {
        val path = requireNotNull(GlyphPath.fromEncoded("05a"))
        val recognizer = GlyphFrameRecognizer(GlyphTable(mapOf(id("a") to path, id("b") to path)))
        val canvas = SyntheticRaster()
        val fit = requireNotNull(NodeArrayDetector().detect(canvas.image()))
        canvas.line(canvas.point(160.0, 130.0), canvas.point(160.0, 418.0))
        val result = recognizer.observe(canvas.image(), fit)
        assertEquals(GlyphFrameObservation.State.AMBIGUOUS_SHAPE, result.state)
        assertEquals(listOf(id("a"), id("b")), result.exactMatches)
    }

    @Test
    fun malformedImagesAreRejectedBeforeIndexing() {
        assertThrows(IllegalArgumentException::class.java) { LuminanceImage(0, 1, ByteArray(0)) }
        assertThrows(IllegalArgumentException::class.java) {
            LuminanceImage(Int.MAX_VALUE, 2, ByteArray(0))
        }
        assertThrows(IllegalArgumentException::class.java) { LuminanceImage(2, 2, ByteArray(1)) }
        assertThrows(IllegalArgumentException::class.java) { ColorImage(2, 2, ByteArray(1)) }
    }

    @Test
    fun exactPartialDrawingExposesLongerTemplatesButNeedsBoundary() {
        val recognizer = GlyphFrameRecognizer(pixelTestTable())
        val cases = listOf(
            Triple("see", "advance", listOf(160.0 to 130.0, 100.0 to 238.0)),
            Triple("advance", "attack", listOf(40.0 to 346.0, 100.0 to 238.0, 160.0 to 130.0)),
            Triple("use", "capture", listOf(280.0 to 202.0, 220.0 to 310.0, 160.0 to 274.0)),
            Triple(
                "all", "clear_all",
                listOf(
                    160.0 to 130.0, 40.0 to 202.0, 40.0 to 346.0, 160.0 to 418.0,
                    280.0 to 346.0, 280.0 to 202.0, 160.0 to 130.0,
                ),
            ),
        )
        for ((visible, longer, points) in cases) {
            val canvas = SyntheticRaster()
            val fit = requireNotNull(NodeArrayDetector().detect(canvas.image()))
            draw(canvas, points)
            val result = recognizer.observe(canvas.image(), fit)
            assertEquals(visible, listOf(id(visible)), result.exactMatches)
            assertTrue("$visible -> $longer", result.extensions.contains(id(longer)))
            val accumulator = GlyphStrokeAccumulator(fit, recognizer)
            accumulator.append(canvas.image())
            assertNull(accumulator.finish()?.acceptedGlyph)
            accumulator.append(canvas.image())
            assertEquals(id(visible), accumulator.finish(GlyphCompletion.PRESENTATION_BOUNDARY)?.acceptedGlyph)
        }
    }

    @Test
    fun nonNestedExactShapesFlagPossibleBoundaryCrossing() {
        val recognizer = GlyphFrameRecognizer(pixelTestTable())
        val blank = SyntheticRaster()
        val fit = requireNotNull(NodeArrayDetector().detect(blank.image()))
        val clear = blank.copy()
        clear.line(clear.point(160.0, 130.0), clear.point(160.0, 418.0))
        val all = blank.copy()
        draw(
            all,
            listOf(
                160.0 to 130.0, 40.0 to 202.0, 40.0 to 346.0, 160.0 to 418.0,
                280.0 to 346.0, 280.0 to 202.0, 160.0 to 130.0,
            ),
        )
        val accumulator = GlyphStrokeAccumulator(fit, recognizer)
        accumulator.append(clear.image())
        accumulator.append(all.image())
        val result = requireNotNull(accumulator.finish(GlyphCompletion.PRESENTATION_BOUNDARY))
        assertEquals(listOf(id("clear_all")), result.observation.exactMatches)
        assertEquals(listOf(listOf(id("clear")), listOf(id("all"))), result.frames.map { it.exactMatches })
        assertTrue(result.possibleBoundaryCrossing)
        assertNull(result.acceptedGlyph)
        repeat(2) {
            accumulator.append(clear.image())
            val repeated = accumulator.finish(GlyphCompletion.PRESENTATION_BOUNDARY)
            assertEquals(id("clear"), repeated?.acceptedGlyph)
            assertEquals(1, repeated?.frameCount)
        }
    }

    @Test
    fun fragmentUnionIsProvisionalWithoutExternalBoundary() {
        val recognizer = GlyphFrameRecognizer(pixelTestTable())
        val blank = SyntheticRaster()
        val fit = requireNotNull(NodeArrayDetector().detect(blank.image()))
        val first = blank.copy()
        val second = blank.copy()
        first.line(first.point(160.0, 130.0), first.point(100.0, 238.0))
        second.line(second.point(100.0, 238.0), second.point(40.0, 346.0))
        val accumulator = GlyphStrokeAccumulator(fit, recognizer)
        accumulator.append(first.image())
        accumulator.append(second.image())
        val result = requireNotNull(accumulator.finish())
        assertEquals(listOf(id("advance")), result.observation.exactMatches)
        assertNull(result.acceptedGlyph)
        assertEquals(2, result.frameCount)
    }

    @Test
    fun substantialOffLatticeInkDowngradesExactMatch() {
        val recognizer = GlyphFrameRecognizer(pixelTestTable())
        val canvas = SyntheticRaster()
        val fit = requireNotNull(NodeArrayDetector().detect(canvas.image()))
        canvas.line(canvas.point(160.0, 130.0), canvas.point(160.0, 418.0))
        for (y in 170..190) {
            canvas.line(canvas.point(60.0, y.toDouble()), canvas.point(135.0, y.toDouble()))
            canvas.line(canvas.point(185.0, y + 190.0), canvas.point(260.0, y + 190.0))
        }
        val result = recognizer.observe(canvas.image(), fit)
        assertEquals(listOf(id("clear")), result.exactMatches)
        assertEquals(GlyphFrameObservation.State.UNEXPLAINED_INK, result.state)
        assertFalse(result.hasUniqueVisibleShape)
        assertTrue(result.unexplainedInkPixelCount > result.strokePixelCount / 2)
        val accumulator = GlyphStrokeAccumulator(fit, recognizer)
        accumulator.append(canvas.image())
        assertNull(accumulator.finish(GlyphCompletion.PRESENTATION_BOUNDARY)?.acceptedGlyph)
    }

    @Test
    fun drawnOverArraysRequireCalibrationAcquiredBeforeStroke() {
        val table = pixelTestTable()
        for (name in listOf("clear", "balance", "clear_all")) {
            val canvas = SyntheticRaster()
            val fit = requireNotNull(NodeArrayDetector().detect(canvas.image()))
            val path = requireNotNull(table.path(name.let(::id)))
            for ((a, b) in path.nodes.zipWithNext()) canvas.line(canvas.nodes[a.rawValue], canvas.nodes[b.rawValue])
            val recognizer = GlyphFrameRecognizer(table)
            assertNull(NodeArrayDetector().detect(canvas.image()))
            assertEquals(
                GlyphFrameObservation.State.CALIBRATION_UNAVAILABLE,
                recognizer.calibrateAndObserve(canvas.image()).state,
            )
            assertEquals(listOf(id(name)), recognizer.observe(canvas.image(), fit).exactMatches)
        }
    }

    @Test
    fun frameLimitDiscardsDrawingInsteadOfLosingBoundary() {
        val image = SyntheticRaster().image()
        val fit = requireNotNull(NodeArrayDetector().detect(image))
        val accumulator = GlyphStrokeAccumulator(fit, GlyphFrameRecognizer(pixelTestTable()), 2)
        accumulator.append(image)
        accumulator.append(image)
        assertThrows(GlyphStrokeAccumulator.AccumulationError.FrameLimitExceeded::class.java) {
            accumulator.append(image)
        }
        assertNull(accumulator.finish())
    }

    @Test
    fun spatialCoverageAcceptsSmallGapAndRejectsTwentyPercentGap() {
        for (percent in listOf(10, 16, 20, 25)) {
            val canvas = SyntheticRaster()
            val fit = requireNotNull(NodeArrayDetector().detect(canvas.image()))
            val halfGap = 144.0 * percent / 200.0
            canvas.line(canvas.point(160.0, 130.0), canvas.point(160.0, 202.0 - halfGap))
            canvas.line(canvas.point(160.0, 202.0 + halfGap), canvas.point(160.0, 274.0))
            val result = GlyphFrameRecognizer(pixelTestTable()).observe(canvas.image(), fit)
            val edge = GlyphPath.Edge(node(0), node(5))
            val evidence = requireNotNull(result.evidence.firstOrNull { it.edge == edge })
            assertEquals("gap $percent: ${evidence.coverage}", percent < 20, evidence.isPresent)
        }
    }

    @Test
    fun twentyOfTwentyFourStrongContrastBinsAreRequired() {
        val edge = GlyphPath.Edge(node(0), node(5))
        assertFalse(GlyphEdgeEvidence(edge, List(19) { 0.18 } + List(5) { 0.0 }).isPresent)
        assertTrue(GlyphEdgeEvidence(edge, List(20) { 0.18 } + List(4) { 0.0 }).isPresent)
        assertFalse(GlyphEdgeEvidence(edge, List(24) { 0.179 }).isPresent)
    }

    @Test
    fun lineNeedsAbsoluteBrightnessAsWellAsContrast() {
        for (value in listOf(59, 60)) {
            val canvas = SyntheticRaster()
            val fit = requireNotNull(NodeArrayDetector().detect(canvas.image()))
            canvas.line(canvas.point(160.0, 130.0), canvas.point(160.0, 418.0), value)
            val result = GlyphFrameRecognizer(pixelTestTable()).observe(canvas.image(), fit)
            assertEquals(if (value == 60) 2 else 0, result.edges.size)
        }
    }

    @Test
    fun nearMatchCandidatesObeyConfiguredDistanceCutoff() {
        val table = glyphTable("exact,03\none,038\ntwo,038a\nthree,038a9")
        val canvas = SyntheticRaster()
        val fit = requireNotNull(NodeArrayDetector().detect(canvas.image()))
        canvas.line(canvas.point(160.0, 130.0), canvas.point(100.0, 238.0))
        val result = GlyphFrameRecognizer(table).observe(canvas.image(), fit)
        assertEquals(
            listOf(GlyphCandidate(id("exact"), 0), GlyphCandidate(id("one"), 1), GlyphCandidate(id("two"), 2)),
            result.candidates,
        )
        assertEquals(listOf(id("one"), id("three"), id("two")), result.extensions)
        val strict = GlyphFrameRecognizer(table, 0).observe(canvas.image(), fit)
        assertEquals(listOf(GlyphCandidate(id("exact"), 0)), strict.candidates)
        assertEquals(result.extensions, strict.extensions)
    }

    @Test
    fun lowerArrayEdgeKeepsAliasesDistancesAndStrictExtensions() {
        val table = glyphTable("bottom,9a\nreverse,a9\nextension,89a\ntop,01\nclear,05a")
        val canvas = SyntheticRaster()
        val fit = requireNotNull(NodeArrayDetector().detect(canvas.image()))
        canvas.line(canvas.point(280.0, 346.0), canvas.point(160.0, 418.0))

        val result = GlyphFrameRecognizer(table).observe(canvas.image(), fit)

        assertEquals(GlyphFrameObservation.State.AMBIGUOUS_SHAPE, result.state)
        assertEquals(setOf(GlyphPath.Edge(node(9), node(10))), result.edges)
        assertEquals(
            listOf(
                GlyphCandidate(id("bottom"), 0), GlyphCandidate(id("reverse"), 0),
                GlyphCandidate(id("extension"), 1), GlyphCandidate(id("top"), 2),
            ),
            result.candidates,
        )
        assertEquals(listOf(id("extension")), result.extensions)
        assertEquals(
            listOf(id("bottom"), id("reverse")),
            GlyphFrameRecognizer(table, 0).observe(canvas.image(), fit).exactMatches,
        )
    }

    @Test
    fun switchingBetweenDistantEdgesDoesNotKeepPreviousCandidates() {
        val recognizer = GlyphFrameRecognizer(glyphTable("bottom,9a\ntop,01"))
        val blank = SyntheticRaster()
        val fit = requireNotNull(NodeArrayDetector().detect(blank.image()))
        for ((name, points) in listOf(
            "top" to listOf(160.0 to 130.0, 40.0 to 202.0),
            "bottom" to listOf(280.0 to 346.0, 160.0 to 418.0),
        )) {
            val canvas = blank.copy()
            draw(canvas, points)
            val result = recognizer.observe(canvas.image(), fit)
            assertEquals(GlyphFrameObservation.State.UNIQUE_VISIBLE_SHAPE, result.state)
            assertEquals(listOf(id(name)), result.exactMatches)
            assertEquals(listOf(0, 2), result.candidates.map { it.distance })
            assertTrue(result.extensions.isEmpty())
        }
        val empty = recognizer.observe(blank.image(), fit)
        assertEquals(GlyphFrameObservation.State.NO_STROKE, empty.state)
        assertTrue(empty.candidates.isEmpty())
        assertTrue(empty.extensions.isEmpty())
    }

    @Test
    fun reflectedAsymmetricPixelShapesDoNotKeepOriginalName() {
        val cases = listOf(
            "see" to listOf(160.0 to 130.0, 220.0 to 238.0),
            "advance" to listOf(160.0 to 130.0, 220.0 to 238.0, 280.0 to 346.0),
            "use" to listOf(40.0 to 202.0, 100.0 to 310.0, 160.0 to 274.0),
        )
        val recognizer = GlyphFrameRecognizer(pixelTestTable())
        for ((original, points) in cases) {
            val canvas = SyntheticRaster()
            val fit = requireNotNull(NodeArrayDetector().detect(canvas.image()))
            draw(canvas, points)
            assertFalse(recognizer.observe(canvas.image(), fit).exactMatches.contains(id(original)))
        }
    }

    @Test
    fun strayInkInsideHexagonCountsButBoundingBoxCornerDoesNot() {
        val empty = SyntheticRaster().image()
        val recognizer = GlyphFrameRecognizer(glyphTable("test,03"))
        val fit = requireNotNull(recognizer.calibrateAndObserve(empty).calibration)
        val positions = fit.positions

        fun blot(center: GlyphPoint): LuminanceImage {
            val pixels = empty.pixels.copyOf()
            for (y in center.y.toInt() - 8..center.y.toInt() + 8) {
                for (x in center.x.toInt() - 8..center.x.toInt() + 8) {
                    val dx = x - center.x
                    val dy = y - center.y
                    if (dx * dx + dy * dy <= 64) pixels[y * empty.width + x] = 200.toByte()
                }
            }
            return LuminanceImage(empty.width, empty.height, pixels)
        }

        val inside = GlyphPoint(
            (positions[0].x + positions[1].x + positions[3].x) / 3,
            (positions[0].y + positions[1].y + positions[3].y) / 3,
        )
        assertEquals(
            GlyphFrameObservation.State.UNRECOGNIZED_STROKE,
            recognizer.observe(blot(inside), fit).state,
        )
        val corner = GlyphPoint(fit.origin.x + fit.size.x - 10, fit.origin.y + 10)
        assertEquals(GlyphFrameObservation.State.NO_STROKE, recognizer.observe(blot(corner), fit).state)
    }

    private fun draw(canvas: SyntheticRaster, points: List<Pair<Double, Double>>) {
        for ((a, b) in points.zipWithNext()) {
            canvas.line(canvas.point(a.first, a.second), canvas.point(b.first, b.second))
        }
    }

    private fun node(rawValue: Int): NodeIndex = requireNotNull(NodeIndex.fromRawValue(rawValue))
}
