package com.dynamicglyph.recognition

/**
 * Maps Glyph names to the node walk that draws them.
 *
 * Ported from Swift GlyphTable.swift.
 *
 * The table is supplied at run time rather than compiled in: the reference data
 * set carries no licence, so it is treated as an input, not as source.
 *
 * Two lookups are offered and they are not interchangeable. [glyphByEdges] is
 * the one recognition uses — it identifies a Glyph by the node pairs the stroke
 * connects, so it is unaffected by which way the stroke was drawn.
 * [glyphByPath] additionally requires the walk order to agree, which is only
 * useful when comparing against the data set's own notation.
 */
class GlyphTable private constructor(
    private val pathsByID: Map<GlyphID, GlyphPath>,
    private val idsByPath: Map<GlyphPath, GlyphID>,
    private val idsByEdges: Map<Set<GlyphPath.Edge>, GlyphID>
) {

    enum class ParseError(val description: String) {
        EMPTY("no rows"),
        WRONG_COLUMN_COUNT("expected 2 columns"),
        EMPTY_NAME("empty name"),
        INVALID_SHAPE("invalid shape"),
        DUPLICATE_NAME("duplicate name")
    }

    class ParseException(
        val error: ParseError,
        val line: Int,
        val detail: String
    ) : Exception(buildMessage(error, line, detail))

    val count: Int get() = pathsByID.size

    /** All names, sorted, for deterministic iteration. */
    val glyphs: List<GlyphID> get() = pathsByID.keys.sorted()

    fun pathFor(id: GlyphID): GlyphPath? = pathsByID[id]

    // -- Direction-independent lookup (use this for recognition) --

    /**
     * Identifies a Glyph by the node pairs its stroke connects.
     * Direction-independent, so a stroke drawn either way resolves to the same
     * Glyph. This is the lookup recognition should use.
     */
    fun glyphByEdges(path: GlyphPath): GlyphID? = idsByEdges[path.undirectedEdges]

    /**
     * Nearest matches by how many edges differ, closest first.
     *
     * The distance is the size of the symmetric difference: one missed or one
     * spurious segment costs 1. Also direction-independent.
     */
    fun nearestByEdges(path: GlyphPath, maxDistance: Int = 2): List<GlyphCandidate> {
        val edges = path.undirectedEdges
        return pathsByID.entries
            .map { (id, p) -> GlyphCandidate(id, edges.symmetricDifference(p.undirectedEdges).size) }
            .filter { it.distance <= maxDistance }
            .sorted()
    }

    // -- Order-sensitive lookup (for comparing against the data set) --

    /**
     * Exact match on the ordered walk. Fails for a stroke drawn in reverse —
     * prefer [glyphByEdges] unless order genuinely matters.
     */
    fun glyphByPath(path: GlyphPath): GlyphID? = idsByPath[path]

    /** Nearest matches by edit distance over the ordered walk. */
    fun nearestByEditDistance(path: GlyphPath, maxDistance: Int = 2): List<GlyphCandidate> =
        pathsByID.entries
            .map { (id, p) -> GlyphCandidate(id, editDistance(path.nodes, p.nodes)) }
            .filter { it.distance <= maxDistance }
            .sorted()

    /**
     * Names that trace the same nodes in the opposite order.
     *
     * Empty for this vocabulary, which is what makes edge-set lookup a complete
     * key rather than a lossy one.
     */
    val mirrorPairs: List<Pair<GlyphID, GlyphID>>
        get() {
            val seen = mutableSetOf<GlyphID>()
            val pairs = mutableListOf<Pair<GlyphID, GlyphID>>()
            for (id in glyphs) {
                if (id in seen) continue
                val path = pathsByID[id] ?: continue
                val partner = idsByPath[path.reversed] ?: continue
                if (partner == id) continue
                seen.add(id)
                seen.add(partner)
                pairs.add(if (id <= partner) id to partner else partner to id)
            }
            return pairs
        }

    /**
     * Glyphs sharing an undirected edge set — the collisions edge-set lookup
     * could not resolve. Empty for this vocabulary.
     */
    val edgeSetCollisions: List<List<GlyphID>>
        get() = glyphs
            .groupBy { pathsByID[it]!!.undirectedEdges }
            .values
            .filter { it.size > 1 }
            .map { it.sorted() }

    companion object {

        fun fromEntries(entries: Map<GlyphID, GlyphPath>): GlyphTable {
            // Distinct names can share a walk or an edge set; keep the
            // lexicographically first so lookup is deterministic across runs.
            val byPath = mutableMapOf<GlyphPath, GlyphID>()
            val byEdges = mutableMapOf<Set<GlyphPath.Edge>, GlyphID>()
            for (id in entries.keys.sorted()) {
                val path = entries.getValue(id)
                byPath.putIfAbsent(path, id)
                byEdges.putIfAbsent(path.undirectedEdges, id)
            }
            return GlyphTable(entries.toMap(), byPath, byEdges)
        }

        /**
         * Parses the reference shape table: exactly `name,shape` per row.
         *
         * Strict on purpose. Surplus columns, an empty name, an unparseable
         * shape or a repeated name are all errors rather than rows to skip,
         * because a silently dropped Glyph becomes a recognition failure that
         * looks like a vision bug. An optional header is tolerated only as the
         * very first line.
         */
        fun parse(csv: String): GlyphTable {
            val entries = mutableMapOf<GlyphID, GlyphPath>()
            var lineNumber = 0

            for (rawLine in csv.lines()) {
                lineNumber++
                val line = rawLine.trim()
                if (line.isEmpty()) continue

                val fields = line.split(",").map { it.trim() }

                // A header is only a header on line 1. The same text later in
                // the file is a malformed row and is reported as one.
                if (lineNumber == 1 && fields.size >= 2 && fields[1].startsWith("shape")) continue

                if (fields.size != 2) {
                    throw ParseException(ParseError.WRONG_COLUMN_COUNT, lineNumber, "found ${fields.size}")
                }
                if (fields[0].isEmpty()) {
                    throw ParseException(ParseError.EMPTY_NAME, lineNumber, "")
                }
                val path = try {
                    GlyphPath(fields[1])
                } catch (e: IllegalArgumentException) {
                    throw ParseException(ParseError.INVALID_SHAPE, lineNumber, fields[1])
                }
                val id = GlyphID(fields[0])
                if (entries.containsKey(id)) {
                    throw ParseException(ParseError.DUPLICATE_NAME, lineNumber, id.name)
                }
                entries[id] = path
            }

            if (entries.isEmpty()) throw ParseException(ParseError.EMPTY, 0, "")
            return fromEntries(entries)
        }

        /**
         * Standard Levenshtein distance over two node walks.
         */
        fun editDistance(a: List<NodeIndex>, b: List<NodeIndex>): Int {
            if (a.isEmpty()) return b.size
            if (b.isEmpty()) return a.size
            var previous = IntArray(b.size + 1) { it }
            var current = IntArray(b.size + 1)
            for (i in 1..a.size) {
                current[0] = i
                for (j in 1..b.size) {
                    val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                    current[j] = minOf(previous[j] + 1, current[j - 1] + 1, substitution)
                }
                val swap = previous
                previous = current
                current = swap
            }
            return previous[b.size]
        }

        private fun buildMessage(error: ParseError, line: Int, detail: String): String {
            val prefix = if (line > 0) "line $line: " else ""
            val suffix = if (detail.isNotEmpty()) " \"$detail\"" else ""
            return prefix + error.description + suffix
        }
    }
}

/**
 * A recognition candidate with an edge or edit distance.
 */
data class GlyphCandidate(val id: GlyphID, val distance: Int) : Comparable<GlyphCandidate> {
    override fun compareTo(other: GlyphCandidate): Int {
        val byDistance = distance.compareTo(other.distance)
        return if (byDistance != 0) byDistance else id.compareTo(other.id)
    }
}
