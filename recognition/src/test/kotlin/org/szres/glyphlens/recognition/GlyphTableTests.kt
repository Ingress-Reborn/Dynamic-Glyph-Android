package org.szres.glyphlens.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GlyphTableTests {
    private fun path(encoded: String): GlyphPath = requireNotNull(GlyphPath.fromEncoded(encoded))

    private fun table(): GlyphTable = GlyphTable.parse(
        """
        advance,038
        balance,0568a975
        clear,05a
        all,018a920
        future,2479
        begin,06a7
        """.trimIndent(),
    )

    @Test
    fun lfCrLfAndCrParseIdentically() {
        for (separator in listOf("\n", "\r\n", "\r")) {
            val shapes = GlyphTable.parse(listOf("advance,038", "clear,05a").joinToString(separator))
            assertEquals(2, shapes.count)
        }
    }

    @Test
    fun surplusColumnsAreRejected() {
        val error = assertThrows(GlyphTable.ParseError.WrongColumnCount::class.java) {
            GlyphTable.parse("advance,038,extra")
        }
        assertEquals(GlyphTable.ParseError.WrongColumnCount(line = 1, found = 3), error)
        assertEquals("line 1: expected 2 columns, found 3", error.message)
    }

    @Test
    fun invalidShapeAndEmptyNameAreRejected() {
        val invalid = assertThrows(GlyphTable.ParseError.InvalidShape::class.java) {
            GlyphTable.parse("advance,0z8")
        }
        assertEquals(GlyphTable.ParseError.InvalidShape(1, "0z8"), invalid)
        val emptyName = assertThrows(GlyphTable.ParseError.EmptyName::class.java) {
            GlyphTable.parse(",038")
        }
        assertEquals(1, emptyName.line)
    }

    @Test
    fun repeatedNameIsRejected() {
        val error = assertThrows(GlyphTable.ParseError.DuplicateName::class.java) {
            GlyphTable.parse("advance,038\nadvance,05a")
        }
        assertEquals(GlyphTable.ParseError.DuplicateName(2, GlyphID("advance")), error)
    }

    @Test
    fun headerIsAcceptedOnlyOnFirstLine() {
        assertEquals(1, GlyphTable.parse("name,shape(using 0..9 and a)\nadvance,038").count)
        val error = assertThrows(GlyphTable.ParseError.InvalidShape::class.java) {
            GlyphTable.parse("advance,038\nmid,shapefile")
        }
        assertEquals(GlyphTable.ParseError.InvalidShape(2, "shapefile"), error)
    }

    @Test
    fun entirelyEmptyInputIsRejected() {
        val error = assertThrows(GlyphTable.ParseError.Empty::class.java) { GlyphTable.parse("\n\n") }
        assertSame(GlyphTable.ParseError.Empty, error)
    }

    @Test
    fun everyRepresentativeGlyphResolvesFromReversedWalk() {
        val table = table()
        for (id in table.glyphs) {
            val path = requireNotNull(table.path(forID = id))
            assertEquals(id, table.glyphMatchingEdges(path))
            assertEquals(id, table.glyphMatchingEdges(path.reversed))
        }
        assertTrue(table.mirrorPairs.isEmpty())
        assertTrue(table.edgeSetCollisions.isEmpty())
    }

    @Test
    fun orderedLookupRemainsOrderSensitive() {
        val table = table()
        val advance = requireNotNull(table.path(GlyphID("advance")))
        assertEquals(GlyphID("advance"), table.glyphMatching(advance))
        assertNull(table.glyphMatching(advance.reversed))
    }

    @Test
    fun edgeDistanceToleratesMissingAndSpuriousSegments() {
        val table = table()
        val advance = requireNotNull(table.path(GlyphID("advance")))
        assertEquals(GlyphCandidate(GlyphID("advance"), 0), table.nearestByEdges(advance, 0).first())
        val truncated = path("03")
        assertTrue(table.nearestByEdges(truncated, 1).contains(GlyphCandidate(GlyphID("advance"), 1)))
        val spurious = path("0385")
        assertTrue(table.nearestByEdges(spurious, 1).contains(GlyphCandidate(GlyphID("advance"), 1)))
    }

    @Test
    fun orderedNearestRanksExactHitFirst() {
        val exact = path("038")
        assertEquals(GlyphCandidate(GlyphID("advance"), 0), table().nearest(exact).first())
    }

    @Test
    fun collidingNamesAreResolvedLexicographicallyAndReported() {
        val colliding = GlyphTable(
            mapOf(
                GlyphID("zeta") to path("038"),
                GlyphID("alpha") to path("830"),
            ),
        )
        assertEquals(GlyphID("alpha"), colliding.glyphMatchingEdges(path("038")))
        assertEquals(GlyphID("zeta"), colliding.glyphMatching(path("038")))
        assertEquals(listOf(GlyphID("alpha") to GlyphID("zeta")), colliding.mirrorPairs)
        assertEquals(listOf(listOf(GlyphID("alpha"), GlyphID("zeta"))), colliding.edgeSetCollisions)
    }
}
