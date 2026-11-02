package com.dynamicglyph.recognition

/**
 * A Glyph identifier — the name of a shape (e.g. "clear", "attack").
 *
 * Ported from Swift GlyphID.swift.
 */
@JvmInline
value class GlyphID(val name: String) : Comparable<GlyphID> {
    override fun compareTo(other: GlyphID): Int = name.compareTo(other.name)
    override fun toString(): String = name
}

/**
 * A Glyph path: an ordered walk through node indices.
 *
 * Ported from Swift GlyphPath.swift. The ordered walk is the data-set
 * representation; the undirected edge set is the recognition key.
 */
data class GlyphPath(val nodes: List<NodeIndex>) {

    init {
        require(nodes.isNotEmpty()) { "GlyphPath must have at least one node" }
    }

    /**
     * An undirected edge between two nodes.
     *
     * Equality and hashing are on the *sorted* node pair, so `Edge(0, 5)` and
     * `Edge(5, 0)` are the same edge. This is load-bearing: the whole
     * recognition path identifies a Glyph by its edge set, and if the edge type
     * were order-sensitive a stroke drawn the other way would produce a
     * different set and fail to match.
     *
     * A Swift `struct Edge` with a synthesised `Hashable` on the already-sorted
     * `lower`/`upper` fields behaves the same way. A Kotlin data class on the
     * raw constructor arguments would not, which is why this is a plain class.
     */
    class Edge(a: NodeIndex, b: NodeIndex) : Comparable<Edge> {
        val lower: NodeIndex = if (a.rawValue <= b.rawValue) a else b
        val upper: NodeIndex = if (a.rawValue <= b.rawValue) b else a

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Edge) return false
            return lower == other.lower && upper == other.upper
        }

        override fun hashCode(): Int = lower.rawValue * 31 + upper.rawValue

        override fun compareTo(other: Edge): Int {
            val byLower = lower.rawValue.compareTo(other.lower.rawValue)
            return if (byLower != 0) byLower else upper.rawValue.compareTo(other.upper.rawValue)
        }

        override fun toString(): String = "${lower.character}${upper.character}"
    }

    /** Parse from encoded string, e.g. "038" or "0568a975". */
    constructor(encoded: String) : this(decode(encoded))

    /** Encode back to string. */
    val encoded: String
        get() = nodes.joinToString("") { it.character.toString() }

    /** Reverse the walk order. */
    val reversed: GlyphPath
        get() = GlyphPath(nodes.reversed())

    /**
     * The set of undirected edges — the direction-independent key.
     */
    val undirectedEdges: Set<Edge>
        get() {
            val edges = LinkedHashSet<Edge>()
            for (i in 0 until nodes.size - 1) {
                edges.add(Edge(nodes[i], nodes[i + 1]))
            }
            return edges
        }

    override fun toString(): String = encoded

    companion object {
        /**
         * Decode a walk. Rejects an empty string and any character that is not
         * a node label, rather than silently dropping it — a dropped node turns
         * into a recognition failure that looks like a vision bug.
         */
        fun decode(encoded: String): List<NodeIndex> {
            require(encoded.isNotEmpty()) { "Empty path" }
            return encoded.map { ch ->
                NodeIndex.fromCharacter(ch)
                    ?: throw IllegalArgumentException("Invalid node character '$ch' in \"$encoded\"")
            }
        }
    }
}
