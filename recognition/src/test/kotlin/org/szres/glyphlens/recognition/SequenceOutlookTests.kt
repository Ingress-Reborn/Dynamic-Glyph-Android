package org.szres.glyphlens.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SequenceOutlookTests {
    private fun glyphs(vararg names: String): List<GlyphID> = names.map(::GlyphID)

    private val table = SequenceTable(
        listOf(
            glyphs("change", "perspective"),
            glyphs("change", "perspective", "technology"),
            glyphs("change", "perspective", "attack"),
            glyphs("change", "perspective", "begin", "new"),
            glyphs("change", "perspective", "begin", "new", "resistance"),
            glyphs("see", "truth", "see", "future", "begin"),
        ),
    )

    private val predictor = SequencePredictor(table)

    @Test
    fun completePrefixStaysAmbiguousUntilSlotsAreCounted() {
        val observed = glyphs("change", "perspective")
        val unknown = predictor.outlook(observed)
        assertEquals(PredictionState.AMBIGUOUS, unknown.state)
        assertEquals(5, unknown.candidateCount)
        assertTrue(unknown.observationMayBeComplete)
        assertTrue(unknown.predictedSuffix.isEmpty())

        val four = predictor.outlook(observed, expectedLength = 4)
        assertEquals(PredictionState.RESOLVED, four.state)
        assertEquals(glyphs("begin", "new"), four.predictedSuffix)
        assertFalse(four.observationMayBeComplete)

        val five = predictor.outlook(observed, expectedLength = 5)
        assertEquals(glyphs("begin", "new", "resistance"), five.predictedSuffix)

        val two = predictor.outlook(observed, expectedLength = 2)
        assertEquals(PredictionState.RESOLVED, two.state)
        assertTrue(two.predictedSuffix.isEmpty())
        assertTrue(two.observationMayBeComplete)
    }

    @Test
    fun recordingStylePrefixResolvesOnlyWithIndependentLength() {
        val observed = glyphs("see", "truth", "see", "future")
        assertTrue(predictor.outlook(observed).state != PredictionState.CONTRADICTED)
        val five = predictor.outlook(observed, expectedLength = 5)
        assertEquals(PredictionState.RESOLVED, five.state)
        assertEquals(glyphs("begin"), five.predictedSuffix)
    }

    @Test
    fun unobservedStillListsPossibilitiesAndLengthNarrowsThem() {
        val empty = predictor.outlook(emptyList())
        assertEquals(PredictionState.UNOBSERVED, empty.state)
        assertEquals(table.count, empty.candidateCount)
        assertTrue(empty.predictedSuffix.isEmpty())

        val twoSlots = predictor.outlook(emptyList(), expectedLength = 2)
        assertEquals(PredictionState.UNOBSERVED, twoSlots.state)
        assertEquals(1, twoSlots.candidateCount)
        assertTrue(twoSlots.candidateCount < empty.candidateCount)
    }

    @Test
    fun contradictionAndLengthConflictRemainDistinct() {
        val impossible = predictor.outlook(glyphs("see", "see", "see", "see"))
        assertEquals(PredictionState.CONTRADICTED, impossible.state)
        assertEquals(0, impossible.candidateCount)
        assertEquals(4, impossible.observed.size)

        val tooShort = predictor.outlook(glyphs("change", "perspective"), expectedLength = 1)
        assertEquals(PredictionState.LENGTH_CONFLICT, tooShort.state)
        assertEquals(2, tooShort.observed.size)
        assertEquals(
            PredictionState.LENGTH_CONFLICT,
            predictor.outlook(glyphs("change"), expectedLength = 6).state,
        )
        assertEquals(
            PredictionState.LENGTH_CONFLICT,
            predictor.outlook(glyphs("change", "perspective", "technology"), expectedLength = 5).state,
        )
    }

    @Test
    fun alternativesAreReportedPerPosition() {
        val outlook = predictor.outlook(glyphs("change", "perspective"), expectedLength = 3)
        assertEquals(PredictionState.AMBIGUOUS, outlook.state)
        assertEquals(listOf(glyphs("attack", "technology")), outlook.suffixAlternatives)
        assertTrue(outlook.predictedSuffix.isEmpty())
    }

    @Test
    fun unknownLengthPreservesBasePredictionSemantics() {
        for (observed in listOf(
            emptyList(),
            glyphs("change"),
            glyphs("change", "perspective"),
            glyphs("see", "truth"),
            glyphs("see", "see", "see"),
        )) {
            val base = predictor.predict(observed)
            val outlook = predictor.outlook(observed)
            assertEquals(base.candidates.size, outlook.candidateCount)
            assertEquals(base.observationMayBeComplete, outlook.observationMayBeComplete)
            assertEquals(base.isResolved && observed.isNotEmpty(), outlook.state == PredictionState.RESOLVED)
            assertEquals(base.isContradicted, outlook.state == PredictionState.CONTRADICTED)
        }
    }

    @Test
    fun displayLimitBoundsSampleButNotCountOrWeights() {
        val outlook = predictor.outlook(
            glyphs("change", "perspective"),
            displayCandidateLimit = 2,
        )
        assertEquals(5, outlook.candidateCount)
        assertEquals(2, outlook.candidates.size)
        assertTrue(outlook.candidates.all { it.weight == 0.2 })
    }
}
