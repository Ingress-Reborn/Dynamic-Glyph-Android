package com.dynamicglyph.recognition

import kotlin.math.atan2

/**
 * Layout of a Glyph diagram within a tile.
 *
 * Ported from Swift GlyphDiagramLayout.swift.
 *
 * Fixed coordinate system: x increases right, y increases down, matching
 * [NodeArray.CANONICAL] and image coordinates. The renderer flips the canvas to
 * match; getting that wrong mirrors every Glyph vertically into another
 * plausible Glyph.
 */
data class GlyphDiagramLayout(
    val tileX: Double,
    val tileY: Double,
    val tileWidth: Double,
    val tileHeight: Double,
    val inset: Double = 0.12
) {

    /** Node positions in tile coordinates. */
    val nodePositions: List<Vec2>
        get() {
            val side = minOf(tileWidth, tileHeight)
            val ox = tileX + (tileWidth - side) / 2.0 + side * inset
            val oy = tileY + (tileHeight - side) / 2.0 + side * inset
            val inner = side * (1.0 - 2.0 * inset)
            return NodeArray.CANONICAL.positions.map {
                Vec2(ox + it.x * inner, oy + it.y * inner)
            }
        }

    /**
     * Segments of the ordered walk, so the drawn order (including `imperfect`'s
     * backtrack) is preserved.
     */
    fun segments(path: GlyphPath): List<Pair<Vec2, Vec2>> {
        val positions = nodePositions
        val result = mutableListOf<Pair<Vec2, Vec2>>()
        for (i in 0 until path.nodes.size - 1) {
            result.add(positions[path.nodes[i].rawValue] to positions[path.nodes[i + 1].rawValue])
        }
        return result
    }

    /**
     * The outer hexagon, in polygon order.
     *
     * Ordered by angle around the centroid rather than by node index: sorting by
     * index gives 0, 1, 2, 8, 9, a, which draws a bow tie.
     */
    val outline: List<Vec2>
        get() {
            val positions = nodePositions
            val outer = NodeArray.CANONICAL.outerHexagon
            if (outer.size < 3) return emptyList()
            val points = outer.map { positions[it.rawValue] }
            val cx = points.sumOf { it.x } / points.size
            val cy = points.sumOf { it.y } / points.size
            return points.sortedBy { atan2(it.y - cy, it.x - cx) }
        }

    /** The 11 node positions, for the small dots the app draws instead of rings. */
    val nodes: List<Vec2> get() = nodePositions

    companion object {
        /** Place the diagram into a pixel rectangle, leaving a margin. */
        fun inRect(left: Double, top: Double, right: Double, bottom: Double): GlyphDiagramLayout {
            val width = right - left
            val height = bottom - top
            val margin = minOf(width, height) * 0.06
            return GlyphDiagramLayout(
                tileX = left + margin,
                tileY = top + margin,
                tileWidth = width - 2 * margin,
                tileHeight = height - 2 * margin
            )
        }
    }
}
