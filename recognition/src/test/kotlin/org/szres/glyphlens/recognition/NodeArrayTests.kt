package org.szres.glyphlens.recognition

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeArrayTests {
    private val array = NodeArray.canonical

    private fun path(encoded: String): GlyphPath = requireNotNull(GlyphPath.fromEncoded(encoded))

    private fun node(rawValue: Int): NodeIndex = requireNotNull(NodeIndex.fromRawValue(rawValue))

    @Test
    fun latticeIsExact() {
        assertEquals(11, array.positions.size)
        assertEquals(setOf(0.0, 0.25, 0.5, 0.75, 1.0), array.positions.map { it.x }.toSet())
        assertEquals(
            setOf(0.0, 0.25, 0.375, 0.5, 0.625, 0.75, 1.0),
            array.positions.map { it.y }.toSet(),
        )
    }

    @Test
    fun hexagonAndInteriorAreDerivedFromGeometry() {
        assertEquals(listOf(0, 1, 2, 8, 9, 10), array.outerHexagon.map { it.rawValue }.sorted())
        assertEquals(listOf(3, 4, 5, 6, 7), array.interior.map { it.rawValue }.sorted())
    }

    @Test
    fun clearTracesTheVerticalAxis() {
        val clear = path("05a")
        clear.nodes.forEach { assertEquals(0.5, array.position(it).x, 0.0) }
    }

    @Test
    fun allTracesTheClosedOuterHexagon() {
        val all = path("018a920")
        assertEquals(array.outerHexagon, all.nodes.toSet())
        assertTrue(all.nodes.toSet().intersect(array.interior).isEmpty())
        assertEquals(all.nodes.first(), all.nodes.last())
    }

    @Test
    fun unlistedInteriorCrossingIsReportedStrictlyInsideSegment() {
        val crossed = array.unvisitedNodesCrossed(path("0a"))
        assertEquals(listOf(NodeCrossing(node(0), node(10), node(5))), crossed)
        assertTrue(array.unvisitedNodesCrossed(path("05a")).isEmpty())
    }

    @Test
    fun wholeArrayReflectionHasTheSameCollinearityEvidence() {
        val reflected = NodeArray(array.positions.map { GlyphPoint(1.0 - it.x, it.y) })
        assertEquals(
            array.unvisitedNodesCrossed(path("0a")),
            reflected.unvisitedNodesCrossed(path("0a")),
        )
    }

    @Test
    fun representativeGlyphsContainNoZeroLengthSegment() {
        for (path in listOf(path("038"), path("0568a975"), path("05a"), path("018a920"))) {
            path.nodes.zipWithNext().forEach { (a, b) ->
                assertTrue(array.position(a) != array.position(b))
            }
        }
    }

    @Test
    fun snappingReturnsNullRatherThanFarAwayGuess() {
        val centre = array.position(node(5))
        assertEquals(5, array.nearest(centre, within = 0.05)?.node?.rawValue)
        assertNull(array.nearest(GlyphPoint(0.5, 0.44), within = 0.01))
    }

    @Test
    fun fittingMapsLatticeOntoPixelBoundingBox() {
        val fitted = array.fitted(
            GlyphBoundingBox(GlyphPoint(74.8, 454.7), GlyphPoint(451.7, 542.3)),
        )
        assertTrue(abs(fitted[0].x - 300.65) < 0.5)
        assertTrue(abs(fitted[0].y - 454.7) < 0.5)
        assertTrue(abs(fitted[10].y - 997.0) < 0.5)
    }
}
