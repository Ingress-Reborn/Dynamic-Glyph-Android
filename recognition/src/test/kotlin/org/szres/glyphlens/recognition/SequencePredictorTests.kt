package org.szres.glyphlens.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SequencePredictorTests {
    private fun glyphs(vararg names: String): List<GlyphID> = names.map(::GlyphID)

    private val table = SequenceTable(
        listOf(
            glyphs("a", "b", "c"),
            glyphs("a", "b", "d"),
            glyphs("a", "e"),
            glyphs("f", "g"),
        ),
    )

    private fun predictor(table: SequenceTable = this.table) = SequencePredictor(table)

    @Test
    fun ambiguousPrefixListsEveryMatchShortestFirst() {
        val prediction = predictor().predict(glyphs("a"))
        assertEquals(
            listOf(glyphs("a", "e"), glyphs("a", "b", "c"), glyphs("a", "b", "d")),
            prediction.candidates.map { it.glyphs },
        )
        prediction.candidates.forEach { assertEquals(1.0 / 3.0, it.weight, 1e-12) }
        assertFalse(prediction.isResolved)
        assertFalse(prediction.isContradicted)
        assertTrue(prediction.remaining.isEmpty())
    }

    @Test
    fun resolvingFinishedPrefixHasNoRemainingGlyphs() {
        val prediction = predictor().predict(glyphs("a", "b", "c"))
        assertTrue(prediction.isResolved)
        assertEquals(1.0, prediction.candidates.single().weight, 0.0)
        assertTrue(prediction.remaining.isEmpty())
    }

    @Test
    fun resolutionMidSequenceProjectsExactSuffix() {
        val prediction = predictor().predict(glyphs("f"))
        assertTrue(prediction.isResolved)
        assertEquals(glyphs("g"), prediction.remaining)
    }

    @Test
    fun impossibleAndOverlongPrefixesAreContradicted() {
        val impossible = predictor().predict(glyphs("a", "z"))
        assertTrue(impossible.isContradicted)
        assertFalse(impossible.isResolved)
        assertTrue(impossible.remaining.isEmpty())
        assertTrue(predictor().predict(glyphs("a", "e", "x", "y")).isContradicted)
    }

    @Test
    fun emptyObservationIsNeitherResolvedNorContradicted() {
        val prediction = predictor().predict(emptyList())
        assertEquals(4, prediction.candidates.size)
        assertFalse(prediction.isResolved)
        assertFalse(prediction.isContradicted)
    }

    @Test
    fun repeatedGlyphsKeepTheirPositions() {
        val table = SequenceTable(listOf(glyphs("a", "a", "b"), glyphs("a", "b", "a")))
        val prediction = predictor(table).predict(glyphs("a", "a"))
        assertTrue(prediction.isResolved)
        assertEquals(glyphs("b"), prediction.remaining)
    }

    @Test
    fun duplicateRowsDoNotCreatePhantomAmbiguity() {
        val table = SequenceTable(listOf(glyphs("a", "b"), glyphs("a", "b"), glyphs("c", "d")))
        val prediction = predictor(table).predict(glyphs("a"))
        assertEquals(1, prediction.candidates.size)
        assertTrue(prediction.isResolved)
        assertEquals(glyphs("b"), prediction.remaining)
    }

    @Test
    fun terminalPrefixCanBeCompleteAndAmbiguousAtOnce() {
        val table = SequenceTable(listOf(glyphs("a", "b"), glyphs("a", "b", "c")))
        val prediction = predictor(table).predict(glyphs("a", "b"))
        assertTrue(prediction.observationMayBeComplete)
        assertEquals(2, prediction.candidates.size)
        assertFalse(prediction.isResolved)
    }

    @Test
    fun weightsAreConditionalSharesNotConfidence() {
        val ambiguous = predictor().predict(glyphs("a", "b"))
        assertTrue(ambiguous.candidates.all { it.weight == 0.5 })
        assertEquals(1.0, predictor().predict(glyphs("f")).candidates.single().weight, 0.0)
    }

    @Test
    fun discriminationUsesDistinctPrefixAndSequenceDenominators() {
        val one = predictor().discrimination(1)
        assertEquals(2, one.distinctPrefixes)
        assertEquals(1, one.uniquelyDetermined)
        assertEquals(3, one.medianCandidates)
        assertEquals(3, one.worstCase)
        assertEquals(4, one.eligibleSequences)
        assertEquals(1, one.resolvedSequences)
        assertEquals(0.5, one.uniqueFraction, 0.0)
        assertEquals(0.25, one.rowWeightedFraction, 0.0)

        val empty = predictor().discrimination(4)
        assertEquals(0, empty.distinctPrefixes)
        assertEquals(0.0, empty.uniqueFraction, 0.0)
        assertEquals(0.0, empty.rowWeightedFraction, 0.0)
    }

    @Test
    fun earlyResolutionExcludesAlreadyFinishedSequences() {
        val early = predictor().earlyResolution(1)
        assertEquals(1, early.resolved)
        assertEquals(4, early.eligible)
        val afterTwo = predictor().earlyResolution(2)
        assertEquals(0, afterTwo.resolved)
        assertEquals(2, afterTwo.eligible)
    }

    @Test
    fun prefixIndexAgreesWithDirectScanForEveryPrefix() {
        var checked = 0
        for (sequence in table.sequences) {
            for (length in 1..sequence.size) {
                val prefix = sequence.take(length)
                val scanned = table.sequences.filter { it.size >= length && it.take(length) == prefix }
                assertEquals(scanned.toSet(), table.candidatesForPrefix(prefix).toSet())
                assertEquals(scanned.size, predictor().predict(prefix).candidates.size)
                checked += 1
            }
        }
        assertEquals(10, checked)
    }
}
