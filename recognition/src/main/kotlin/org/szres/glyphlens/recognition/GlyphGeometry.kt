package org.szres.glyphlens.recognition

/** Geometry shared by shape recognition and drawing. */
object GlyphGeometry {
    /** Splits rendered segments at every intervening lattice node. */
    @JvmStatic
    fun normalizedEdges(edges: Set<GlyphPath.Edge>): Set<GlyphPath.Edge> {
        val array = NodeArray.canonical
        val result = linkedSetOf<GlyphPath.Edge>()
        for (edge in edges) {
            if (edge.lower == edge.upper) continue
            val a = array.position(edge.lower)
            val b = array.position(edge.upper)
            val delta = b - a
            val nodes = NodeIndex.all.filter {
                it == edge.lower || it == edge.upper || array.segment(edge.lower, edge.upper, it)
            }.sortedBy {
                val relative = array.position(it) - a
                relative.x * delta.x + relative.y * delta.y
            }
            for ((from, to) in nodes.zipWithNext()) {
                result.add(GlyphPath.Edge(from, to))
            }
        }
        return result
    }

    /** Every pair with no third lattice node strictly between its endpoints. */
    @JvmStatic
    val elementaryEdges: List<GlyphPath.Edge> = buildList {
        for (a in NodeIndex.all) {
            for (b in NodeIndex.all) {
                if (a < b && NodeIndex.all.none { NodeArray.canonical.segment(a, b, it) }) {
                    add(GlyphPath.Edge(a, b))
                }
            }
        }
    }
}
