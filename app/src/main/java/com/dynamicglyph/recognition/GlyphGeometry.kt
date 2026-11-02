package com.dynamicglyph.recognition

/**
 * Splits a rendered segment at every intervening lattice node.
 *
 * Ported from Swift GlyphGeometry.swift. A long edge and the adjacent collinear
 * edges produce identical pixels — notably for `imperfect`, which backtracks
 * through a node it does not list — so the template and the measurement have to
 * be compared in the same vocabulary.
 */
object GlyphGeometry {

    /**
     * Splits each edge at the lattice nodes that lie on it.
     *
     * Signature matches the original: it takes and returns an edge set, not a
     * path, so it can normalise measured edges as well as template ones.
     */
    fun normalizedEdges(edges: Set<GlyphPath.Edge>): Set<GlyphPath.Edge> {
        val array = NodeArray.CANONICAL
        val result = LinkedHashSet<GlyphPath.Edge>()

        for (edge in edges) {
            if (edge.lower == edge.upper) continue
            val a = array.positionOf(edge.lower)
            val b = array.positionOf(edge.upper)
            val delta = b - a

            // Every node on the segment, including both endpoints, ordered by
            // projection along it.
            val nodes = NodeIndex.ALL.filter { node ->
                node == edge.lower || node == edge.upper ||
                    array.segmentPassesThrough(edge.lower, edge.upper, node)
            }.sortedBy { node ->
                (array.positionOf(node) - a).dot(delta)
            }

            for (i in 0 until nodes.size - 1) {
                result.add(GlyphPath.Edge(nodes[i], nodes[i + 1]))
            }
        }
        return result
    }

    /**
     * Every pair of nodes with no other node lying between them.
     *
     * These are the smallest units the pixel sampler can measure, and the only
     * ones it samples.
     */
    val elementaryEdges: List<GlyphPath.Edge> by lazy {
        val edges = mutableListOf<GlyphPath.Edge>()
        for (a in NodeIndex.ALL) {
            for (b in NodeIndex.ALL) {
                if (a >= b) continue
                val hasIntermediate = NodeIndex.ALL.any { c ->
                    NodeArray.CANONICAL.segmentPassesThrough(a, b, c)
                }
                if (!hasIntermediate) edges.add(GlyphPath.Edge(a, b))
            }
        }
        edges
    }
}
