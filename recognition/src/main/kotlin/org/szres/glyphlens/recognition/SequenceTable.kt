package org.szres.glyphlens.recognition

/** The enumerated Glyph sequences, deduplicated in first-seen order. */
class SequenceTable(input: List<List<GlyphID>>) {
    sealed class ParseError(message: String) : Exception(message) {
        data object Empty : ParseError("no rows")

        data class InteriorGap(val line: Int, val contents: String) :
            ParseError("line $line: empty field between names in \"$contents\"")

        data class TooManyGlyphs(val line: Int, val found: Int) :
            ParseError("line $line: $found glyphs, maximum is 5")
    }

    /** Distinct sequences in first-seen order. */
    val sequences: List<List<GlyphID>>

    /** Total nonempty input rows, including duplicate rows. */
    val rowCount: Int

    private val weights: Map<List<GlyphID>, Int>
    private val byPrefix: Map<List<GlyphID>, List<List<GlyphID>>>
    private val complete: Set<List<GlyphID>>

    init {
        val nonEmpty = input.filter { it.isNotEmpty() }.map { it.toList() }
        rowCount = nonEmpty.size

        val seen = linkedSetOf<List<GlyphID>>()
        val distinct = mutableListOf<List<GlyphID>>()
        val counts = mutableMapOf<List<GlyphID>, Int>()
        for (sequence in nonEmpty) {
            counts[sequence] = (counts[sequence] ?: 0) + 1
            if (seen.add(sequence)) distinct.add(sequence)
        }
        sequences = distinct.toList()
        weights = counts
        complete = seen

        val index = mutableMapOf<List<GlyphID>, MutableList<List<GlyphID>>>()
        for (sequence in distinct) {
            for (length in 1..sequence.size) {
                index.getOrPut(sequence.take(length)) { mutableListOf() }.add(sequence)
            }
        }
        byPrefix = index
    }

    val count: Int
        get() = sequences.size

    fun weight(of: List<GlyphID>): Int = weights[of] ?: 0

    /** All distinct sequences with this prefix; empty returns everything. */
    fun candidatesForPrefix(prefix: List<GlyphID>): List<List<GlyphID>> =
        if (prefix.isEmpty()) sequences else byPrefix[prefix].orEmpty()

    /** Whether this exact observation is itself a complete table sequence. */
    fun isComplete(observed: List<GlyphID>): Boolean = observed in complete

    /** Every distinct Glyph that could immediately follow this prefix. */
    fun continuationsAfter(prefix: List<GlyphID>): Set<GlyphID> =
        candidatesForPrefix(prefix).mapNotNullTo(linkedSetOf()) { sequence ->
            if (sequence.size > prefix.size) sequence[prefix.size] else null
        }

    companion object {
        const val maximumLength: Int = 5

        /** Parses comma-separated names, removing only trailing empty padding. */
        @JvmStatic
        fun parse(csv: String): SequenceTable {
            val parsed = mutableListOf<List<GlyphID>>()
            var lineNumber = 0
            for (rawLine in csv.splitIntoLines()) {
                lineNumber += 1
                val line = rawLine.trimmingWhitespace()
                if (line.isEmpty()) continue
                val fields = line.split(',').map { it.trimmingWhitespace() }.toMutableList()
                while (fields.lastOrNull()?.isEmpty() == true) fields.removeAt(fields.lastIndex)
                if (fields.isEmpty()) continue
                if (fields.any { it.isEmpty() }) {
                    throw ParseError.InteriorGap(lineNumber, line)
                }
                if (fields.size > maximumLength) {
                    throw ParseError.TooManyGlyphs(lineNumber, fields.size)
                }
                parsed.add(fields.map(::GlyphID))
            }
            if (parsed.isEmpty()) throw ParseError.Empty
            return SequenceTable(parsed)
        }
    }
}
