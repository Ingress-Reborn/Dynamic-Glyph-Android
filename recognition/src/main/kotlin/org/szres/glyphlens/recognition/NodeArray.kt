package org.szres.glyphlens.recognition

import kotlin.math.sqrt

/** A point or two-dimensional vector in normalized array or tile coordinates. */
data class GlyphPoint(val x: Double, val y: Double) {
    internal operator fun plus(other: GlyphPoint): GlyphPoint = GlyphPoint(x + other.x, y + other.y)

    internal operator fun minus(other: GlyphPoint): GlyphPoint = GlyphPoint(x - other.x, y - other.y)

    /** Component-wise multiplication, matching SIMD2 multiplication in the Swift source. */
    internal operator fun times(other: GlyphPoint): GlyphPoint = GlyphPoint(x * other.x, y * other.y)

    internal operator fun div(divisor: Double): GlyphPoint = GlyphPoint(x / divisor, y / divisor)
}

data class GlyphBoundingBox(val origin: GlyphPoint, val size: GlyphPoint)

data class NearestNode(val node: NodeIndex, val distance: Double)

data class NodeCrossing(val from: NodeIndex, val to: NodeIndex, val crossed: NodeIndex)

/** The eleven-node Glyph array in normalized, top-left-origin coordinates. */
class NodeArray(positions: List<GlyphPoint>) {
    val positions: List<GlyphPoint>

    init {
        require(positions.size == NodeIndex.count) {
            "The array has exactly ${NodeIndex.count} nodes"
        }
        this.positions = positions.toList()
    }

    fun position(of: NodeIndex): GlyphPoint = positions[of.rawValue]

    /** The six nodes on the bounding-box edge, derived from the coordinates. */
    val outerHexagon: Set<NodeIndex>
        get() = positions.mapIndexedNotNullTo(linkedSetOf()) { index, point ->
            if (point.x == 0.0 || point.x == 1.0 || point.y == 0.0 || point.y == 1.0) {
                NodeIndex.fromRawValue(index)
            } else {
                null
            }
        }

    val interior: Set<NodeIndex>
        get() = NodeIndex.all.filterNotTo(linkedSetOf()) { it in outerHexagon }

    /** Whether segment a-b strictly passes through another node c. */
    fun segment(a: NodeIndex, b: NodeIndex, passesThrough: NodeIndex): Boolean {
        if (passesThrough == a || passesThrough == b) return false
        val p = position(a)
        val q = position(b)
        val r = position(passesThrough)
        val cross = (q.x - p.x) * (r.y - p.y) - (q.y - p.y) * (r.x - p.x)
        if (kotlin.math.abs(cross) >= 1e-9) return false
        val dot = (r.x - p.x) * (q.x - p.x) + (r.y - p.y) * (q.y - p.y)
        val lengthSquared = (q.x - p.x) * (q.x - p.x) + (q.y - p.y) * (q.y - p.y)
        return dot > 1e-9 && dot < lengthSquared - 1e-9
    }

    /** Nodes crossed by a path segment without being listed by that path. */
    fun unvisitedNodesCrossed(by: GlyphPath): List<NodeCrossing> {
        val found = mutableListOf<NodeCrossing>()
        for ((a, b) in by.nodes.zipWithNext()) {
            for (c in NodeIndex.all) {
                if (segment(a, b, c)) found.add(NodeCrossing(a, b, c))
            }
        }
        return found
    }

    /** The closest node within radius, or null rather than a far-away guess. */
    fun nearest(to: GlyphPoint, within: Double): NearestNode? {
        var best: NearestNode? = null
        for ((index, position) in positions.withIndex()) {
            val dx = position.x - to.x
            val dy = position.y - to.y
            val distance = sqrt(dx * dx + dy * dy)
            val current = best
            if (distance <= within && (current == null || distance < current.distance)) {
                best = NearestNode(NodeIndex.all[index], distance)
            }
        }
        return best
    }

    /** Axis-aligned fit onto a detected pixel-space bounding box. */
    fun fitted(to: GlyphBoundingBox): List<GlyphPoint> =
        positions.map { to.origin + it * to.size }

    companion object {
        /** The measured layout drawn by the game. */
        @JvmStatic
        val canonical: NodeArray = NodeArray(
            listOf(
                GlyphPoint(0.50, 0.000),
                GlyphPoint(0.00, 0.250),
                GlyphPoint(1.00, 0.250),
                GlyphPoint(0.25, 0.375),
                GlyphPoint(0.75, 0.375),
                GlyphPoint(0.50, 0.500),
                GlyphPoint(0.25, 0.625),
                GlyphPoint(0.75, 0.625),
                GlyphPoint(0.00, 0.750),
                GlyphPoint(1.00, 0.750),
                GlyphPoint(0.50, 1.000),
            ),
        )
    }
}
