package org.szres.glyphlens.recognition

/** The ordered walk of nodes that draws a single Glyph. */
class GlyphPath(nodes: List<NodeIndex>) {
    val nodes: List<NodeIndex> = nodes.toList()

    val encoded: String
        get() = buildString(nodes.size) {
            nodes.forEach { append(it.character) }
        }

    /** The same walk traced backwards. */
    val reversed: GlyphPath
        get() = GlyphPath(nodes.asReversed())

    /** Node pairs connected by the stroke, ignoring direction and repetitions. */
    val undirectedEdges: Set<Edge>
        get() = nodes.zipWithNext().mapTo(linkedSetOf()) { (from, to) -> Edge(from, to) }

    /** Levenshtein distance over the ordered node walk. */
    fun editDistance(to: GlyphPath): Int {
        val a = nodes
        val b = to.nodes
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

    override fun equals(other: Any?): Boolean = other is GlyphPath && nodes == other.nodes

    override fun hashCode(): Int = nodes.hashCode()

    override fun toString(): String = encoded

    /** An undirected node pair, always stored in ascending node order. */
    class Edge private constructor(val lower: NodeIndex, val upper: NodeIndex) {
        override fun equals(other: Any?): Boolean =
            other is Edge && lower == other.lower && upper == other.upper

        override fun hashCode(): Int = 31 * lower.hashCode() + upper.hashCode()

        override fun toString(): String = "$lower-$upper"

        companion object {
            @JvmStatic
            operator fun invoke(a: NodeIndex, b: NodeIndex): Edge =
                if (a <= b) Edge(a, b) else Edge(b, a)
        }
    }

    companion object {
        /** Parses the data set's encoding, for example `038` or `0568a975`. */
        @JvmStatic
        fun fromEncoded(encoded: String): GlyphPath? {
            if (encoded.isEmpty()) return null
            val parsed = ArrayList<NodeIndex>(encoded.length)
            for (character in encoded) {
                parsed.add(NodeIndex.fromCharacter(character) ?: return null)
            }
            return GlyphPath(parsed)
        }
    }
}
