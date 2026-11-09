package org.szres.glyphlens.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.szres.glyphlens.recognition.image.GlyphFrameRecognizer
import org.szres.glyphlens.recognition.image.NodeArrayDetector
import org.szres.glyphlens.recognition.image.SyntheticRaster
import java.nio.file.Files
import java.nio.file.Path

class BundledGlyphCorpusTest {
    private val assets = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .map { it.resolve("app/src/main/assets") }
        .first { Files.isRegularFile(it.resolve("glyph_line.csv")) }

    @Test
    fun everyBundledShapeSurvivesIndependentPixelRasterization() {
        val table = GlyphTable.parse(Files.readString(assets.resolve("glyph_line.csv")))
        assertEquals(142, table.count)
        val recognizer = GlyphFrameRecognizer(table)
        val blank = SyntheticRaster()
        val calibration = requireNotNull(NodeArrayDetector().detect(blank.image()))

        for (glyph in table.glyphs) {
            val canvas = blank.copy()
            val path = requireNotNull(table.path(glyph))
            // Render the original walk, including backtracking and long edges;
            // the test never calls the recognizer's edge normalization or sampler.
            for ((a, b) in path.nodes.zipWithNext()) {
                canvas.line(canvas.nodes[a.rawValue], canvas.nodes[b.rawValue])
            }
            val observation = recognizer.observe(canvas.image(), calibration)
            assertEquals("$glyph: ${observation.candidates}", listOf(glyph), observation.exactMatches)
            assertTrue(
                "$glyph: ${observation.state}, unexplained=${observation.unexplainedInkPixelCount}",
                observation.hasUniqueVisibleShape,
            )
        }
    }

    @Test
    fun bundledSequencePrefixesKeepEveryCompatibleSuffixAndIndependentLength() {
        val table = SequenceTable.parse(Files.readString(assets.resolve("glyph.csv")))
        assertEquals(542, table.rowCount)
        assertEquals(540, table.count)
        val predictor = SequencePredictor(table)
        val prefixes = table.sequences.flatMap { row -> (1..row.size).map(row::take) }.toSet()

        for (prefix in prefixes) {
            val compatible = table.sequences.filter { it.take(prefix.size) == prefix }.toSet()
            assertEquals(prefix.toString(), compatible, table.candidatesForPrefix(prefix).toSet())
            assertEquals(prefix.toString(), compatible, predictor.predict(prefix).candidates.map { it.glyphs }.toSet())
            assertEquals(prefix in table.sequences, predictor.predict(prefix).observationMayBeComplete)
            for (length in prefix.size..SequenceTable.maximumLength) {
                val sameLength = compatible.filter { it.size == length }.toSet()
                val outlook = predictor.outlook(prefix, expectedLength = length, displayCandidateLimit = table.count)
                assertEquals("$prefix length=$length", sameLength, outlook.candidates.map { it.glyphs }.toSet())
                assertEquals(sameLength.size, outlook.candidateCount)
                when (sameLength.size) {
                    0 -> assertEquals(PredictionState.LENGTH_CONFLICT, outlook.state)
                    1 -> {
                        assertEquals(PredictionState.RESOLVED, outlook.state)
                        assertEquals(sameLength.single().drop(prefix.size), outlook.predictedSuffix)
                    }
                    else -> {
                        assertEquals(PredictionState.AMBIGUOUS, outlook.state)
                        assertTrue(outlook.predictedSuffix.isEmpty())
                    }
                }
            }
        }
    }
}
