package com.dynamicglyph.recognition

/**
 * The eleven-node Glyph array, in normalised coordinates.
 *
 * Ported from Swift NodeArray.swift. Positions are measured from a real
 * 603×1311 capture of the Ingress glyph hack screen, normalised within
 * the array's bounding box.
 *
 *     x ∈ { 0, ¼, ½, ¾, 1 }        y ∈ { 0, ¼, ⅜, ½, ⅝, ¾, 1 }
 *
 * Origin is top-left, y increasing downward (image coordinates).
 */
data class NodeArray(val positions: List<Vec2>) {

    init {
        require(positions.size == NodeIndex.COUNT) {
            "The array has exactly ${NodeIndex.COUNT} nodes"
        }
    }

    fun positionOf(node: NodeIndex): Vec2 = positions[node.rawValue]

    /**
     * The six vertices of the outer hexagon: nodes on the bounding box edge.
     */
    val outerHexagon: Set<NodeIndex>
        get() = positions.mapIndexedNotNull { index, p ->
            if (p.x == 0.0 || p.x == 1.0 || p.y == 0.0 || p.y == 1.0) NodeIndex(index) else null
        }.toSet()

    /** Nodes strictly inside the hexagon. */
    val interior: Set<NodeIndex>
        get() = NodeIndex.ALL.toSet() - outerHexagon

    /**
     * Whether the segment a–b passes through node c.
     * Uses cross-product for collinearity + dot product projection.
     */
    fun segmentPassesThrough(a: NodeIndex, b: NodeIndex, c: NodeIndex): Boolean {
        if (c == a || c == b) return false
        val p = positionOf(a)
        val q = positionOf(b)
        val r = positionOf(c)
        val cross = (q.x - p.x) * (r.y - p.y) - (q.y - p.y) * (r.x - p.x)
        if (abs(cross) >= 1e-9) return false
        val dot = (r.x - p.x) * (q.x - p.x) + (r.y - p.y) * (q.y - p.y)
        val lengthSq = (q.x - p.x) * (q.x - p.x) + (q.y - p.y) * (q.y - p.y)
        return dot > 1e-9 && dot < lengthSq - 1e-9
    }

    /**
     * Nodes a path crosses without listing them.
     */
    fun unvisitedNodesCrossedBy(path: GlyphPath): List<Triple<NodeIndex, NodeIndex, NodeIndex>> {
        val found = mutableListOf<Triple<NodeIndex, NodeIndex, NodeIndex>>()
        for (i in 0 until path.nodes.size - 1) {
            val a = path.nodes[i]
            val b = path.nodes[i + 1]
            for (c in NodeIndex.ALL) {
                if (segmentPassesThrough(a, b, c)) {
                    found.add(Triple(a, b, c))
                }
            }
        }
        return found
    }

    /**
     * The node closest to a normalised point, if within `radius`.
     */
    fun nearestTo(point: Vec2, radius: Double): Pair<NodeIndex, Double>? {
        var best: Pair<NodeIndex, Double>? = null
        for ((index, pos) in positions.withIndex()) {
            val dx = pos.x - point.x
            val dy = pos.y - point.y
            val dist = sqrt(dx * dx + dy * dy)
            if (dist <= radius && (best == null || dist < best.second)) {
                best = NodeIndex(index) to dist
            }
        }
        return best
    }

    /**
     * Fits this layout onto a detected bounding box in pixel space.
     * Axis-aligned: no rotation or perspective.
     */
    fun fittedTo(origin: Vec2, size: Vec2): List<Vec2> {
        return positions.map { Vec2(origin.x + it.x * size.x, origin.y + it.y * size.y) }
    }

    companion object {
        /**
         * The layout the game draws, derived from real capture measurements.
         *
         *      0  (apex, 0.50, 0.00)
         *    /   \
         *   1     2  (0.00, 0.25)  (1.00, 0.25)
         *    \   / \   /
         *     3     4  (0.25, 0.375) (0.75, 0.375)
         *      \   /
         *       5     (centre, 0.50, 0.50)
         *      /   \
         *     6     7  (0.25, 0.625) (0.75, 0.625)
         *    /   \   /
         *   8     9  (0.00, 0.75)  (1.00, 0.75)
         *    \   /
         *      a     (base, 0.50, 1.00)
         */
        val CANONICAL = NodeArray(listOf(
            Vec2(0.50, 0.000),   // 0  apex
            Vec2(0.00, 0.250),   // 1  upper left, outer
            Vec2(1.00, 0.250),   // 2  upper right, outer
            Vec2(0.25, 0.375),   // 3  upper left, inner
            Vec2(0.75, 0.375),   // 4  upper right, inner
            Vec2(0.50, 0.500),   // 5  centre
            Vec2(0.25, 0.625),   // 6  lower left, inner
            Vec2(0.75, 0.625),   // 7  lower right, inner
            Vec2(0.00, 0.750),   // 8  lower left, outer
            Vec2(1.00, 0.750),   // 9  lower right, outer
            Vec2(0.50, 1.000)    // a  base
        ))
    }
}
