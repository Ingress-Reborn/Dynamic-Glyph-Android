package org.szres.glyphlens.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SequenceTableTests {
    private fun glyphs(vararg names: String): List<GlyphID> = names.map(::GlyphID)

    @Test
    fun lfCrLfAndCrParseIdentically() {
        for (separator in listOf("\n", "\r\n", "\r")) {
            val table = SequenceTable.parse(listOf("a,b,,,", "c,d,e,,").joinToString(separator))
            assertEquals(2, table.count)
            assertEquals(3, table.sequences[1].size)
        }
    }

    @Test
    fun trailingPaddingIsStripped() {
        val table = SequenceTable.parse("gain,safety,,,")
        assertEquals(listOf(glyphs("gain", "safety")), table.sequences)
    }

    @Test
    fun interiorGapIsRejectedRatherThanClosedUp() {
        val error = assertThrows(SequenceTable.ParseError.InteriorGap::class.java) {
            SequenceTable.parse("advance,,attack,,,")
        }
        assertEquals(SequenceTable.ParseError.InteriorGap(1, "advance,,attack,,,"), error)
    }

    @Test
    fun sequenceLongerThanFiveIsRejected() {
        val error = assertThrows(SequenceTable.ParseError.TooManyGlyphs::class.java) {
            SequenceTable.parse("a,b,c,d,e,f")
        }
        assertEquals(SequenceTable.ParseError.TooManyGlyphs(1, 6), error)
    }

    @Test
    fun entirelyEmptyInputIsRejected() {
        val error = assertThrows(SequenceTable.ParseError.Empty::class.java) {
            SequenceTable.parse("\n\n")
        }
        assertSame(SequenceTable.ParseError.Empty, error)
    }

    @Test
    fun duplicateRowsCollapseButRetainWeight() {
        val table = SequenceTable(listOf(glyphs("a", "b"), glyphs("a", "b"), glyphs("c", "d")))
        assertEquals(3, table.rowCount)
        assertEquals(2, table.count)
        assertEquals(2, table.weight(glyphs("a", "b")))
        assertEquals(1, table.weight(glyphs("c", "d")))
        assertEquals(0, table.weight(glyphs("missing")))
    }

    @Test
    fun prefixLookupHandlesEmptyUnknownAndRepeatedGlyphs() {
        val table = SequenceTable(
            listOf(glyphs("a", "a", "b"), glyphs("a", "b", "a"), glyphs("c", "d")),
        )
        assertEquals(table.sequences, table.candidatesForPrefix(emptyList()))
        assertEquals(listOf(glyphs("a", "a", "b")), table.candidatesForPrefix(glyphs("a", "a")))
        assertTrue(table.candidatesForPrefix(glyphs("z")).isEmpty())
        assertEquals(setOf(GlyphID("b")), table.continuationsAfter(glyphs("a", "a")))
    }

    @Test
    fun terminalPrefixReportsCompletionAndContinuationTogether() {
        val table = SequenceTable(listOf(glyphs("a", "b"), glyphs("a", "b", "c")))
        assertTrue(table.isComplete(glyphs("a", "b")))
        assertEquals(setOf(GlyphID("c")), table.continuationsAfter(glyphs("a", "b")))
        assertEquals(2, table.candidatesForPrefix(glyphs("a", "b")).size)
    }

    @Test
    fun directEmptyRowsDoNotCountOrBecomeComplete() {
        val table = SequenceTable(listOf(emptyList(), glyphs("a"), emptyList()))
        assertEquals(1, table.rowCount)
        assertEquals(1, table.count)
        assertTrue(!table.isComplete(emptyList()))
    }
}
