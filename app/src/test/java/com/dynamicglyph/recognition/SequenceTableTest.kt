package com.dynamicglyph.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sequence table parsing and prefix prediction.
 *
 * The padding and line-ending behaviour is not incidental: the reference file
 * pads rows to five columns with trailing commas and uses CRLF, and a parser
 * that mishandles either invents sequences that do not exist.
 */
class SequenceTableTest {

    @Test
    fun `parses rows padded with trailing commas`() {
        val table = SequenceTable.parse("a,b,c,,\na,b,,,\na,,,,")
        assertEquals(3, table.count)
        assertEquals(listOf(listOf(GlyphID("a"), GlyphID("b"), GlyphID("c"))), table.candidatesForPrefix(listOf(GlyphID("a"), GlyphID("b"), GlyphID("c"))).filter { it.size == 3 })
    }

    @Test
    fun `parses CRLF and LF alike`() {
        val crlf = SequenceTable.parse("a,b,,\r\na,c,,\r\n")
        val lf = SequenceTable.parse("a,b,,\na,c,,\n")
        assertEquals(crlf.count, lf.count)
        assertEquals(crlf.candidatesForPrefix(listOf(GlyphID("a"))), lf.candidatesForPrefix(listOf(GlyphID("a"))))
    }

    @Test
    fun `rejects an empty field between two names`() {
        val error = runCatching { SequenceTable.parse("a,,b") }.exceptionOrNull()
        assertEquals(
            SequenceTable.ParseError.INTERIOR_GAP,
            (error as SequenceTable.ParseException).error
        )
    }

    @Test
    fun `rejects a row longer than the game's maximum`() {
        val error = runCatching { SequenceTable.parse("a,b,c,d,e,f") }.exceptionOrNull()
        assertEquals(
            SequenceTable.ParseError.TOO_MANY_GLYPHS,
            (error as SequenceTable.ParseException).error
        )
    }

    @Test
    fun `deduplicates repeated rows but keeps the count`() {
        val table = SequenceTable.parse("a,b,,\na,b,,\na,c,,\n")
        assertEquals(2, table.count)
        assertEquals(3, table.rowCount)
        assertEquals(2, table.weightOf(listOf(GlyphID("a"), GlyphID("b"))))
    }

    @Test
    fun `an empty prefix returns every sequence`() {
        val table = SequenceTable.parse("a,b,,\nc,d,,\n")
        assertEquals(2, table.candidatesForPrefix(emptyList()).size)
    }

    @Test
    fun `an unknown prefix returns nothing`() {
        // This is the signal that a Glyph was misread, not a reason to guess.
        val table = SequenceTable.parse("a,b,,\n")
        assertTrue(table.candidatesForPrefix(listOf(GlyphID("z"))).isEmpty())
    }

    @Test
    fun `prefix lookup narrows to the consistent sequences`() {
        val table = SequenceTable.parse("a,b,c,,\na,b,d,,\na,c,d,,\n")
        val byA = table.candidatesForPrefix(listOf(GlyphID("a")))
        assertEquals(3, byA.size)
        val byAb = table.candidatesForPrefix(listOf(GlyphID("a"), GlyphID("b")))
        assertEquals(2, byAb.size)
    }

    @Test
    fun `a sequence can be complete and a prefix of a longer one at once`() {
        val table = SequenceTable.parse("a,b,,\na,b,c,,\n")
        val observed = listOf(GlyphID("a"), GlyphID("b"))
        assertTrue(table.isComplete(observed))
        assertEquals(2, table.candidatesForPrefix(observed).size)
    }

    @Test
    fun `continuations are the distinct next glyphs`() {
        val table = SequenceTable.parse("a,b,,\na,c,,\na,b,d,,\n")
        val next = table.continuationsAfter(listOf(GlyphID("a")))
        assertEquals(setOf(GlyphID("b"), GlyphID("c")), next)
    }

    @Test
    fun `prediction resolves when one sequence remains`() {
        val table = SequenceTable.parse("a,b,c,,\na,c,d,,\n")
        val predictor = SequencePredictor(table)
        val resolved = predictor.predict(listOf(GlyphID("a"), GlyphID("b")))
        assertTrue(resolved.isResolved)
        assertEquals(
            listOf(GlyphID("c")),
            resolved.remaining
        )
    }

    @Test
    fun `prediction stays open while several sequences remain`() {
        val table = SequenceTable.parse("a,b,c,,\na,b,d,,\n")
        val predictor = SequencePredictor(table)
        val open = predictor.predict(listOf(GlyphID("a")))
        assertFalse(open.isResolved)
        assertEquals(2, open.candidates.size)
        assertTrue(open.remaining.isEmpty())
    }

    @Test
    fun `prediction reports a contradiction`() {
        val table = SequenceTable.parse("a,b,,\n")
        val predictor = SequencePredictor(table)
        val contradicted = predictor.predict(listOf(GlyphID("z")))
        assertTrue(contradicted.isContradicted)
        assertFalse(contradicted.isResolved)
    }
}
