package org.szres.glyphlens.recognition

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GlyphDiagramLayoutTests {
    private val layout = GlyphDiagramLayout()

    private fun path(encoded: String): GlyphPath = requireNotNull(GlyphPath.fromEncoded(encoded))

    @Test
    fun asymmetricGlyphsKeepSideAndDirection() {
        val future = layout.segments(path("2479"))
        assertEquals(3, future.size)
        assertTrue(future.all { it.from.x > 0.5 && it.to.x > 0.5 })
        assertTrue(future.first().from.y < future.last().to.y)

        val begin = layout.segments(path("06a7"))
        val apex = begin.first().from
        assertEquals(layout.nodePositions().minOf { it.y }, apex.y, 0.0)
        assertTrue(abs(apex.x - 0.5) < 1e-9)
        assertEquals(layout.nodePositions().maxOf { it.y }, begin[1].to.y, 0.0)
    }

    @Test
    fun representativeGlyphsProduceInsetDrawableGeometry() {
        for (path in listOf(path("038"), path("0568a975"), path("05a"), path("018a920"), path("2479"))) {
            val segments = layout.segments(path)
            assertEquals(path.nodes.size - 1, segments.size)
            for (segment in segments) {
                for (point in listOf(segment.from, segment.to)) {
                    assertTrue(point.x >= layout.inset - 1e-9 && point.x <= 1.0 - layout.inset + 1e-9)
                    assertTrue(point.y >= layout.inset - 1e-9 && point.y <= 1.0 - layout.inset + 1e-9)
                }
            }
            assertEquals(path.nodes.toSet().size, layout.visitedNodes(path).size)
        }
    }

    @Test
    fun outlineIsOuterHexagonClockwiseFromApex() {
        val positions = layout.nodePositions()
        assertEquals(listOf(0, 2, 9, 10, 8, 1).map { positions[it] }, layout.outline())
    }

    @Test
    fun tileIsCentredInWideAndTallRectangles() {
        val wide = layout.frame(GlyphPoint(0.0, 0.0), GlyphPoint(200.0, 100.0))
        assertEquals(100.0, wide.side, 0.0)
        assertEquals(GlyphPoint(50.0, 0.0), wide.origin)
        val tall = layout.frame(GlyphPoint(10.0, 20.0), GlyphPoint(80.0, 160.0))
        assertEquals(80.0, tall.side, 0.0)
        assertEquals(GlyphPoint(10.0, 60.0), tall.origin)
    }

    @Test
    fun invalidInsetIsRejectedAtEntry() {
        assertThrows(IllegalArgumentException::class.java) { GlyphDiagramLayout(-0.1) }
        assertThrows(IllegalArgumentException::class.java) { GlyphDiagramLayout(0.5) }
    }
}
