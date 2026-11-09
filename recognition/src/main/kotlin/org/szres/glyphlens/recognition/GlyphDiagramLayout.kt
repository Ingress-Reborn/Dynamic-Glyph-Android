package org.szres.glyphlens.recognition

data class DiagramSegment(val from: GlyphPoint, val to: GlyphPoint)

data class DiagramFrame(val origin: GlyphPoint, val side: Double)

/** Converts a Glyph into top-left-origin, y-down drawable geometry. */
class GlyphDiagramLayout(val inset: Double = 0.12) {
    init {
        require(inset >= 0.0 && inset < 0.5)
    }

    /** Node positions inside a unit tile, in NodeIndex.all order. */
    fun nodePositions(): List<GlyphPoint> {
        val scale = 1.0 - 2.0 * inset
        val scaleVector = GlyphPoint(scale, scale)
        val offset = GlyphPoint(inset, inset)
        return NodeArray.canonical.positions.map { offset + it * scaleVector }
    }

    /** Ordered stroke segments; revisits and backtracking are preserved. */
    fun segments(forPath: GlyphPath): List<DiagramSegment> {
        val positions = nodePositions()
        return forPath.nodes.zipWithNext().map { (from, to) ->
            DiagramSegment(positions[from.rawValue], positions[to.rawValue])
        }
    }

    /** Each visited node once, in first-visit order. */
    fun visitedNodes(of: GlyphPath): List<GlyphPoint> {
        val positions = nodePositions()
        val seen = mutableSetOf<Int>()
        return of.nodes.mapNotNull { node ->
            if (seen.add(node.rawValue)) positions[node.rawValue] else null
        }
    }

    /** The outer hexagon as a closed-loop vertex list, clockwise from the apex. */
    fun outline(): List<GlyphPoint> {
        val positions = nodePositions()
        val vertices = NodeArray.canonical.outerHexagon.map { positions[it.rawValue] }
        val right = vertices.filter { it.x > 0.5 }.sortedBy { it.y }
        val rest = vertices.filter { it.x <= 0.5 }
        val apex = rest.minByOrNull { it.y }
        val others = rest.filter { it != apex }.sortedByDescending { it.y }
        return listOfNotNull(apex) + right + others
    }

    /** Places a square unit tile in a rectangle while preserving aspect ratio. */
    fun frame(inOrigin: GlyphPoint, size: GlyphPoint): DiagramFrame {
        val side = minOf(size.x, size.y)
        val centeredOrigin = inOrigin + (size - GlyphPoint(side, side)) / 2.0
        return DiagramFrame(centeredOrigin, side)
    }
}
