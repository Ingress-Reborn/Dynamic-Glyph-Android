package org.szres.glyphlens.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlyphGeometryTests {
    private fun node(rawValue: Int): NodeIndex = requireNotNull(NodeIndex.fromRawValue(rawValue))

    private fun path(encoded: String): GlyphPath = requireNotNull(GlyphPath.fromEncoded(encoded))

    @Test
    fun longAndAdjacentCollinearEdgesNormalizeToSamePixels() {
        val long = GlyphGeometry.normalizedEdges(path("0a").undirectedEdges)
        val adjacent = GlyphGeometry.normalizedEdges(path("05a").undirectedEdges)
        assertEquals(setOf(GlyphPath.Edge(node(0), node(5)), GlyphPath.Edge(node(5), node(10))), long)
        assertEquals(adjacent, long)
    }

    @Test
    fun normalizationDropsZeroLengthEdgesAndHandlesBacktracking() {
        val loopOnly = GlyphGeometry.normalizedEdges(setOf(GlyphPath.Edge(node(3), node(3))))
        assertTrue(loopOnly.isEmpty())
        val backtracking = path("0568a975")
        assertEquals(
            GlyphGeometry.normalizedEdges(backtracking.reversed.undirectedEdges),
            GlyphGeometry.normalizedEdges(backtracking.undirectedEdges),
        )
    }

    @Test
    fun elementaryEdgesHaveNoInterveningNode() {
        val edges = GlyphGeometry.elementaryEdges
        assertTrue(GlyphPath.Edge(node(0), node(5)) in edges)
        assertTrue(GlyphPath.Edge(node(5), node(10)) in edges)
        assertFalse(GlyphPath.Edge(node(0), node(10)) in edges)
        assertEquals(edges.size, edges.toSet().size)
        for (edge in edges) {
            assertTrue(NodeIndex.all.none { NodeArray.canonical.segment(edge.lower, edge.upper, it) })
        }
    }
}
