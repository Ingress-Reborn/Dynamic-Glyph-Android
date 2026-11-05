package com.dynamicglyph.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lattice and its geometry.
 *
 * These are the invariants the whole recogniser rests on, so they are asserted
 * rather than assumed: if the canonical positions or the edge normalisation are
 * wrong, every downstream reading is wrong in a way that still looks plausible.
 */
class LatticeTest {

    @Test
    fun `canonical array has eleven nodes`() {
        assertEquals(11, NodeArray.CANONICAL.positions.size)
        assertEquals(11, NodeIndex.COUNT)
    }

    @Test
    fun `canonical positions are the measured lattice`() {
        val positions = NodeArray.CANONICAL.positions
        assertEquals(Vec2(0.50, 0.000), positions[0])   // apex
        assertEquals(Vec2(0.00, 0.250), positions[1])   // upper left outer
        assertEquals(Vec2(1.00, 0.250), positions[2])   // upper right outer
        assertEquals(Vec2(0.25, 0.375), positions[3])   // upper left inner
        assertEquals(Vec2(0.75, 0.375), positions[4])   // upper right inner
        assertEquals(Vec2(0.50, 0.500), positions[5])   // centre
        assertEquals(Vec2(0.25, 0.625), positions[6])   // lower left inner
        assertEquals(Vec2(0.75, 0.625), positions[7])   // lower right inner
        assertEquals(Vec2(0.00, 0.750), positions[8])   // lower left outer
        assertEquals(Vec2(1.00, 0.750), positions[9])   // lower right outer
        assertEquals(Vec2(0.50, 1.000), positions[10])  // base
    }

    @Test
    fun `outer hexagon is the six nodes on the bounding box`() {
        val expected = setOf(
            NodeIndex(0), NodeIndex(1), NodeIndex(2),
            NodeIndex(8), NodeIndex(9), NodeIndex(10)
        )
        assertEquals(expected, NodeArray.CANONICAL.outerHexagon)
        assertEquals(5, NodeArray.CANONICAL.interior.size)
    }

    @Test
    fun `the axis segment passes through the centre`() {
        // clear = 05a is the one shape that pins nodes 0, 5 and a independently.
        assertTrue(NodeArray.CANONICAL.segmentPassesThrough(NodeIndex(0), NodeIndex(10), NodeIndex(5)))
        assertFalse(NodeArray.CANONICAL.segmentPassesThrough(NodeIndex(0), NodeIndex(10), NodeIndex(3)))
    }

    @Test
    fun `a long edge is split at the node it passes through`() {
        val edges = GlyphGeometry.normalizedEdges(GlyphPath("0a").undirectedEdges)
        val expected = setOf(
            GlyphPath.Edge(NodeIndex(0), NodeIndex(5)),
            GlyphPath.Edge(NodeIndex(5), NodeIndex(10))
        )
        assertEquals(expected, edges)
    }

    @Test
    fun `elementary edges exclude pairs with a node between them`() {
        // Derived from the lattice: 38 of the 55 node pairs have no third node
        // on the segment. 0-a is not one of them, 0-5 is.
        assertEquals(38, GlyphGeometry.elementaryEdges.size)
        val pairs = GlyphGeometry.elementaryEdges.map { it.lower to it.upper }.toSet()
        assertTrue(pairs.contains(NodeIndex(0) to NodeIndex(5)))
        assertFalse(pairs.contains(NodeIndex(0) to NodeIndex(10)))
    }

    @Test
    fun `an edge ignores the direction it was built from`() {
        // Load-bearing: the recognition key is the undirected edge set, so
        // Edge(0,5) and Edge(5,0) must be the same set element.
        val forward = GlyphPath.Edge(NodeIndex(0), NodeIndex(5))
        val backward = GlyphPath.Edge(NodeIndex(5), NodeIndex(0))
        assertEquals(forward, backward)
        assertEquals(forward.hashCode(), backward.hashCode())
        assertEquals(1, setOf(forward, backward).size)
    }

    @Test
    fun `an undirected edge set ignores the walk direction`() {
        val drawn = GlyphPath("05a").undirectedEdges
        val reversed = GlyphPath("a50").undirectedEdges
        assertEquals(drawn, reversed)
    }

    @Test
    fun `nearest node returns null outside the radius`() {
        val hit = NodeArray.CANONICAL.nearestTo(Vec2(0.51, 0.49), 0.05)
        assertEquals(NodeIndex(5), hit?.first)
        // Halfway between the apex and the centre: no node within 0.01.
        assertNull(NodeArray.CANONICAL.nearestTo(Vec2(0.5, 0.25), 0.01))
    }

    @Test
    fun `fitted maps the unit lattice onto a pixel box`() {
        val fitted = NodeArray.CANONICAL.fittedTo(Vec2(100.0, 200.0), Vec2(300.0, 400.0))
        assertEquals(Vec2(250.0, 200.0), fitted[0])   // apex, horizontally centred
        assertEquals(Vec2(250.0, 600.0), fitted[10])  // base
    }

    @Test
    fun `path decoding rejects characters that are not nodes`() {
        val thrown = runCatching { GlyphPath("05z") }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
        val empty = runCatching { GlyphPath("") }.exceptionOrNull()
        assertTrue(empty is IllegalArgumentException)
    }

    @Test
    fun `path encoding round trips`() {
        assertEquals("0568a975", GlyphPath("0568a975").encoded)
        // Reversing a walk reverses the node order; "05a" backwards is "a50".
        assertEquals("a50", GlyphPath("05a").reversed.encoded)
        assertEquals("05a", GlyphPath("05a").reversed.reversed.encoded)
    }
}
