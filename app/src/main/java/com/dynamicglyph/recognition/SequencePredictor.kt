package com.dynamicglyph.recognition

/**
 * Predicts the full Glyph sequence from a partial observation.
 * Ported from Swift SequencePredictor.swift.
 */
class SequencePredictor(private val table: SequenceTable) {

    data class SequenceCandidate(
        val glyphs: List<GlyphID>,
        val weight: Double
    ) : Comparable<SequenceCandidate> {
        override fun compareTo(other: SequenceCandidate): Int {
            // Sort by length then alphabetically for stable display
            val c = glyphs.size.compareTo(other.glyphs.size)
            return if (c != 0) c else glyphs.joinToString(",").compareTo(other.glyphs.joinToString(","))
        }
    }

    data class Prediction(
        val observed: List<GlyphID>,
        val candidates: List<SequenceCandidate>,
        val observationMayBeComplete: Boolean,
        val isResolved: Boolean,
        val isContradicted: Boolean,
        val remaining: List<GlyphID>
    )

    /**
     * Given observed prefix, return all consistent complete sequences.
     */
    fun predict(observed: List<GlyphID>): Prediction {
        val rawCandidates = table.candidatesForPrefix(observed)
        val sorted = rawCandidates.sortedWith(
            compareBy({ it.size }, { it.joinToString(",") })
        )
        val candidates = sorted.map {
            SequenceCandidate(it, 1.0 / sorted.size)
        }

        val mayBeComplete = table.isComplete(observed)
        val resolved = candidates.size == 1
        val contradicted = candidates.isEmpty() && observed.isNotEmpty()

        val remaining = if (resolved && candidates.isNotEmpty()) {
            val full = candidates.first().glyphs
            full.drop(observed.size)
        } else {
            emptyList()
        }

        return Prediction(
            observed = observed,
            candidates = candidates,
            observationMayBeComplete = mayBeComplete,
            isResolved = resolved,
            isContradicted = contradicted,
            remaining = remaining
        )
    }
}
