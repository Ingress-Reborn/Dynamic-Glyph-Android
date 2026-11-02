package com.dynamicglyph.recognition

/**
 * The enumerated set of Glyph sequences the game draws from.
 * Ported from Swift SequenceTable.swift.
 *
 * Sequences are deduplicated; prefix index built once for O(1) lookup.
 */
class SequenceTable private constructor(
    val sequences: List<List<GlyphID>>,
    val rowCount: Int,
    private val weights: Map<List<GlyphID>, Int>,
    private val byPrefix: Map<List<GlyphID>, List<List<GlyphID>>>,
    private val complete: Set<List<GlyphID>>
) {
    enum class ParseError(val description: String) {
        EMPTY("no rows"),
        INTERIOR_GAP("empty field between names"),
        TOO_MANY_GLYPHS("maximum is 5");

        fun withLine(line: Int, extra: String = ""): String =
            "line $line: $description" + if (extra.isNotEmpty()) " \"$extra\"" else ""
    }

    class ParseException(val error: ParseError, val line: Int, val extra: String) : Exception(
        error.withLine(line, extra)
    )

    val count: Int get() = sequences.size

    fun weightOf(sequence: List<GlyphID>): Int = weights[sequence] ?: 0

    /**
     * Every sequence consistent with the observed prefix.
     * Empty prefix returns all; unknown prefix returns empty.
     */
    fun candidatesForPrefix(prefix: List<GlyphID>): List<List<GlyphID>> {
        if (prefix.isEmpty()) return sequences
        return byPrefix[prefix] ?: emptyList()
    }

    /** Whether the observation is itself a complete sequence. */
    fun isComplete(observed: List<GlyphID>): Boolean = complete.contains(observed)

    /** Every distinct Glyph that could come next. */
    fun continuationsAfter(prefix: List<GlyphID>): Set<GlyphID> {
        return candidatesForPrefix(prefix).mapNotNull { seq ->
            if (seq.size > prefix.size) seq[prefix.size] else null
        }.toSet()
    }

    companion object {
        const val MAXIMUM_LENGTH = 5

        fun parse(csv: String): SequenceTable {
            val parsed = mutableListOf<List<GlyphID>>()
            var lineNumber = 0

            for (rawLine in csv.lines()) {
                lineNumber++
                val line = rawLine.trim()
                if (line.isEmpty()) continue

                var fields = line.split(",").map { it.trim() }.toMutableList()
                // Strip trailing empty fields only
                while (fields.isNotEmpty() && fields.last().isEmpty()) fields.removeAt(fields.lastIndex)
                if (fields.isEmpty()) continue

                if (fields.any { it.isEmpty() }) {
                    throw ParseException(ParseError.INTERIOR_GAP, lineNumber, line)
                }
                if (fields.size > MAXIMUM_LENGTH) {
                    throw ParseException(ParseError.TOO_MANY_GLYPHS, lineNumber, "found ${fields.size}")
                }
                parsed.add(fields.map { GlyphID(it) })
            }

            if (parsed.isEmpty()) throw ParseException(ParseError.EMPTY, 0, "")
            return fromSequences(parsed)
        }

        fun fromSequences(input: List<List<GlyphID>>): SequenceTable {
            val nonEmpty = input.filter { it.isNotEmpty() }
            val rowCount = nonEmpty.size

            val seen = mutableSetOf<List<GlyphID>>()
            val distinct = mutableListOf<List<GlyphID>>()
            val counts = mutableMapOf<List<GlyphID>, Int>()
            for (seq in nonEmpty) {
                counts[seq] = (counts[seq] ?: 0) + 1
                if (seen.add(seq)) distinct.add(seq)
            }

            // Build prefix index
            val index = mutableMapOf<List<GlyphID>, MutableList<List<GlyphID>>>()
            for (seq in distinct) {
                for (length in 1..seq.size) {
                    val prefix = seq.take(length)
                    index.getOrPut(prefix) { mutableListOf() }.add(seq)
                }
            }

            return SequenceTable(
                sequences = distinct,
                rowCount = rowCount,
                weights = counts,
                byPrefix = index,
                complete = seen
            )
        }
    }
}
