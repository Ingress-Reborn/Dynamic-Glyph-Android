package com.dynamicglyph.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shape table parsing and the direction-independent lookup.
 *
 * Fixtures here are synthetic on purpose: the real tables are third-party data
 * that is not redistributed with this project, and the behaviour under test is
 * the parser and the lookup, not the corpus.
 */
class GlyphTableTest {

    private val fixture = """
        alpha,05a
        beta,038
        gamma,0568a975
    """.trimIndent()

    @Test
    fun `parses name and shape rows`() {
        val table = GlyphTable.parse(fixture)
        assertEquals(3, table.count)
        assertEquals(listOf(GlyphID("alpha"), GlyphID("beta"), GlyphID("gamma")), table.glyphs)
        assertEquals(GlyphPath("05a"), table.pathFor(GlyphID("alpha")))
    }

    @Test
    fun `tolerates a header on the first line only`() {
        val table = GlyphTable.parse("name,shape\nalpha,05a")
        assertEquals(1, table.count)
    }

    @Test
    fun `rejects a duplicate name rather than dropping a row`() {
        val error = runCatching {
            GlyphTable.parse("alpha,05a\nalpha,038")
        }.exceptionOrNull()
        assertTrue(error is GlyphTable.ParseException)
        assertEquals(GlyphTable.ParseError.DUPLICATE_NAME, (error as GlyphTable.ParseException).error)
    }

    @Test
    fun `rejects the wrong column count`() {
        val error = runCatching { GlyphTable.parse("alpha,05a,extra") }.exceptionOrNull()
        assertEquals(
            GlyphTable.ParseError.WRONG_COLUMN_COUNT,
            (error as GlyphTable.ParseException).error
        )
    }

    @Test
    fun `rejects a shape with a character that is not a node`() {
        val error = runCatching { GlyphTable.parse("alpha,05z") }.exceptionOrNull()
        assertEquals(
            GlyphTable.ParseError.INVALID_SHAPE,
            (error as GlyphTable.ParseException).error
        )
    }

    @Test
    fun `rejects an empty table`() {
        val error = runCatching { GlyphTable.parse("") }.exceptionOrNull()
        assertEquals(GlyphTable.ParseError.EMPTY, (error as GlyphTable.ParseException).error)
    }

    @Test
    fun `lookup by edges resolves a stroke drawn either way`() {
        val table = GlyphTable.parse(fixture)
        // The recognition key is the undirected edge set, so all three walks
        // must resolve to the same Glyph. This is the invariant that makes a
        // player drawing right-to-left still recognisable.
        assertEquals(GlyphID("alpha"), table.glyphByEdges(GlyphPath("05a")))
        assertEquals(GlyphID("alpha"), table.glyphByEdges(GlyphPath("a50")))
        assertEquals(GlyphID("alpha"), table.glyphByEdges(GlyphPath("50a")))
    }

    @Test
    fun `order-sensitive lookup does not resolve a reversed stroke`() {
        val table = GlyphTable.parse(fixture)
        assertEquals(GlyphID("alpha"), table.glyphByPath(GlyphPath("05a")))
        assertNull(table.glyphByPath(GlyphPath("a50")))
    }

    @Test
    fun `nearest by edges ranks the closest shape first`() {
        val table = GlyphTable.parse(fixture)
        // 0-3-8 is beta. Dropping the last segment leaves 0-3, one edge short.
        val candidates = table.nearestByEdges(GlyphPath("03"), maxDistance = 2)
        assertEquals(GlyphID("beta"), candidates.first().id)
        assertEquals(1, candidates.first().distance)
    }

    @Test
    fun `nearest by edges respects the distance limit`() {
        val table = GlyphTable.parse(fixture)
        // A single edge is at least two edges away from every fixture shape.
        assertTrue(table.nearestByEdges(GlyphPath("09"), maxDistance = 1).isEmpty())
    }

    @Test
    fun `edit distance is symmetric and zero for equal walks`() {
        val a = listOf(NodeIndex(0), NodeIndex(5), NodeIndex(10))
        val b = listOf(NodeIndex(0), NodeIndex(3), NodeIndex(8))
        assertEquals(0, GlyphTable.editDistance(a, a))
        assertEquals(
            GlyphTable.editDistance(a, b),
            GlyphTable.editDistance(b, a)
        )
        assertEquals(2, GlyphTable.editDistance(a, b))
    }

    @Test
    fun `this vocabulary has no mirror pairs or edge-set collisions`() {
        // Both are empty for the reference table, which is what makes the edge
        // set a complete key rather than a lossy one.
        val table = GlyphTable.parse(fixture)
        assertTrue(table.mirrorPairs.isEmpty())
        assertTrue(table.edgeSetCollisions.isEmpty())
    }
}
