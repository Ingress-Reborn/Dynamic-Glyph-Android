package org.szres.glyphlens.recognition

/** Turns a partial observation into the sequences still consistent with it. */
class SequencePredictor(private val table: SequenceTable) {
    fun predict(observed: List<GlyphID>): Prediction {
        val matches = table.candidatesForPrefix(observed)
        val ordering = compareBy<List<GlyphID>> { it.size }
            .thenBy { glyphs -> glyphs.joinToString(",") { it.name } }
        val ranked = matches.sortedWith(ordering).map {
            SequenceCandidate(it, 1.0 / matches.size.toDouble())
        }
        return Prediction(
            observed = observed.toList(),
            candidates = ranked,
            observationMayBeComplete = table.isComplete(observed),
        )
    }

    /** Statistics per distinct prefix and per distinct sequence. */
    fun discrimination(afterObserving: Int): Discrimination {
        require(afterObserving > 0) { "A zero-length prefix discriminates nothing" }
        val eligible = table.sequences.filter { it.size >= afterObserving }
        val bucketSizes = mutableMapOf<List<GlyphID>, Int>()
        for (sequence in eligible) {
            val prefix = sequence.take(afterObserving)
            bucketSizes[prefix] = (bucketSizes[prefix] ?: 0) + 1
        }
        val sizes = bucketSizes.values.sorted()
        if (sizes.isEmpty()) {
            return Discrimination(afterObserving, 0, 0, 0, 0, 0, 0)
        }
        val resolved = eligible.count { bucketSizes[it.take(afterObserving)] == 1 }
        return Discrimination(
            prefixLength = afterObserving,
            distinctPrefixes = sizes.size,
            uniquelyDetermined = sizes.count { it == 1 },
            medianCandidates = sizes[sizes.size / 2],
            worstCase = sizes.last(),
            eligibleSequences = eligible.size,
            resolvedSequences = resolved,
        )
    }

    /** Resolution among only those sequences that still have a suffix to predict. */
    fun earlyResolution(afterObserving: Int): EarlyResolution {
        val eligible = table.sequences.filter { it.size > afterObserving }
        val bucketSizes = mutableMapOf<List<GlyphID>, Int>()
        for (sequence in table.sequences) {
            if (sequence.size >= afterObserving) {
                val prefix = sequence.take(afterObserving)
                bucketSizes[prefix] = (bucketSizes[prefix] ?: 0) + 1
            }
        }
        val resolved = eligible.count { bucketSizes[it.take(afterObserving)] == 1 }
        return EarlyResolution(resolved, eligible.size)
    }

    data class Discrimination(
        val prefixLength: Int,
        val distinctPrefixes: Int,
        val uniquelyDetermined: Int,
        val medianCandidates: Int,
        val worstCase: Int,
        val eligibleSequences: Int,
        val resolvedSequences: Int,
    ) {
        val uniqueFraction: Double
            get() = if (distinctPrefixes == 0) 0.0
            else uniquelyDetermined.toDouble() / distinctPrefixes.toDouble()

        val rowWeightedFraction: Double
            get() = if (eligibleSequences == 0) 0.0
            else resolvedSequences.toDouble() / eligibleSequences.toDouble()
    }

    data class EarlyResolution(val resolved: Int, val eligible: Int)
}

data class SequenceCandidate(val glyphs: List<GlyphID>, val weight: Double)

/** The confirmed prefix and its conditional table candidates. */
data class Prediction(
    val observed: List<GlyphID>,
    val candidates: List<SequenceCandidate>,
    val observationMayBeComplete: Boolean = false,
) {
    val isResolved: Boolean
        get() = candidates.size == 1

    val isContradicted: Boolean
        get() = candidates.isEmpty() && observed.isNotEmpty()

    /** Empty for finished, ambiguous, and contradicted results alike. */
    val remaining: List<GlyphID>
        get() = if (isResolved) candidates.first().glyphs.drop(observed.size) else emptyList()
}
