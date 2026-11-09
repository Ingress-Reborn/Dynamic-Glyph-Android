package org.szres.glyphlens.recognition

enum class PredictionState {
    UNAVAILABLE,
    UNOBSERVED,
    AMBIGUOUS,
    RESOLVED,
    CONTRADICTED,
    LENGTH_CONFLICT,
}

/** A prediction packaged for display without conflating empty suffix states. */
data class SequenceOutlook(
    val state: PredictionState,
    val observed: List<GlyphID>,
    val expectedLength: Int?,
    val candidateCount: Int,
    val candidates: List<SequenceCandidate>,
    val predictedSuffix: List<GlyphID>,
    val suffixAlternatives: List<List<GlyphID>>,
    val observationMayBeComplete: Boolean,
) {
    companion object {
        @JvmStatic
        val unavailable: SequenceOutlook = SequenceOutlook(
            state = PredictionState.UNAVAILABLE,
            observed = emptyList(),
            expectedLength = null,
            candidateCount = 0,
            candidates = emptyList(),
            predictedSuffix = emptyList(),
            suffixAlternatives = emptyList(),
            observationMayBeComplete = false,
        )
    }
}

/** Filters a prediction with an independently counted slot count, when known. */
fun SequencePredictor.outlook(
    observed: List<GlyphID>,
    expectedLength: Int? = null,
    displayCandidateLimit: Int = 8,
): SequenceOutlook {
    fun result(
        state: PredictionState,
        matches: List<List<GlyphID>>,
        mayBeComplete: Boolean = false,
    ): SequenceOutlook {
        val weight = if (matches.isEmpty()) 0.0 else 1.0 / matches.size.toDouble()
        val shown = matches.take(displayCandidateLimit).map { SequenceCandidate(it, weight) }
        val alternatives = mutableListOf<List<GlyphID>>()
        val longest = matches.maxOfOrNull { it.size } ?: 0
        if (longest > observed.size) {
            for (position in observed.size until longest) {
                val choices = matches.mapNotNullTo(linkedSetOf()) { sequence ->
                    if (sequence.size > position) sequence[position] else null
                }
                alternatives.add(choices.sorted())
            }
        }
        val suffix = if (state == PredictionState.RESOLVED && matches.size == 1) {
            matches[0].drop(observed.size)
        } else {
            emptyList()
        }
        return SequenceOutlook(
            state = state,
            observed = observed.toList(),
            expectedLength = expectedLength,
            candidateCount = matches.size,
            candidates = shown,
            predictedSuffix = suffix,
            suffixAlternatives = alternatives,
            observationMayBeComplete = mayBeComplete,
        )
    }

    if (expectedLength != null) {
        if (expectedLength !in 1..SequenceTable.maximumLength || expectedLength < observed.size) {
            return result(PredictionState.LENGTH_CONFLICT, emptyList())
        }
    }

    val base = predict(observed)
    if (base.isContradicted) return result(PredictionState.CONTRADICTED, emptyList())

    var matches = base.candidates.map { it.glyphs }
    if (expectedLength != null) {
        matches = matches.filter { it.size == expectedLength }
        if (matches.isEmpty()) return result(PredictionState.LENGTH_CONFLICT, emptyList())
    }

    val mayBeComplete = base.observationMayBeComplete &&
        (expectedLength == null || expectedLength == observed.size)
    if (observed.isEmpty()) {
        return result(PredictionState.UNOBSERVED, matches, mayBeComplete)
    }
    val state = if (matches.size == 1) PredictionState.RESOLVED else PredictionState.AMBIGUOUS
    return result(state, matches, mayBeComplete)
}
