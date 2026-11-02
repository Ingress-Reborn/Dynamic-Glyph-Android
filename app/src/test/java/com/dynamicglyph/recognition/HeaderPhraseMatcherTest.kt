package com.dynamicglyph.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Header phrase matching.
 *
 * The phrases are the game's own English header text, taken from the original's
 * `GameText.english`. Two rules here are easy to get wrong and both are tested:
 * "GO!" needs a whole-header match, and two phases matching one line is a
 * contradiction rather than a tie to break.
 */
class HeaderPhraseMatcherTest {

    private val matcher = HeaderPhraseMatcher()

    private fun read(vararg lines: Pair<String, Double>) = matcher.read(lines.toList())

    @Test
    fun `recognises the presentation header`() {
        val reading = read("INCOMING GLYPH SEQUENCE" to 0.9)
        assertEquals(GamePhase.PRESENTING, reading.phase)
        assertTrue(reading.isKnown)
    }

    @Test
    fun `recognises the command channel header`() {
        assertEquals(
            GamePhase.COMMAND,
            read("Command channel open…" to 0.8).phase
        )
    }

    @Test
    fun `recognises the completion header`() {
        assertEquals(
            GamePhase.COMPLETED,
            read("Glyph hack complete" to 0.8).phase
        )
    }

    @Test
    fun `recognises GO as the input header`() {
        assertEquals(GamePhase.INPUT, read("GO!" to 0.9).phase)
    }

    @Test
    fun `GO requires the whole header to match`() {
        // Two characters is too short for a substring match: map text such as
        // "GO TO PORTAL" must not be read as the input phase.
        assertEquals(GamePhase.UNKNOWN, read("GO TO PORTAL" to 0.9).phase)
    }

    @Test
    fun `ignores lines below the confidence floor`() {
        assertEquals(GamePhase.UNKNOWN, read("INCOMING GLYPH SEQUENCE" to 0.2).phase)
    }

    @Test
    fun `two matching phases is a contradiction, not a tie`() {
        val reading = read("INCOMING GLYPH SEQUENCE GLYPH HACK COMPLETE" to 0.9)
        assertEquals(GamePhase.UNKNOWN, reading.phase)
        assertEquals("conflicting phrases", reading.matchedText)
    }

    @Test
    fun `unrelated text reads as unknown`() {
        val reading = read("Resonator level 8" to 0.9)
        assertEquals(GamePhase.UNKNOWN, reading.phase)
        assertEquals("", reading.matchedText)
        assertEquals(0.0, reading.confidence, 0.0)
    }

    @Test
    fun `normalisation drops case, punctuation and repeated spaces`() {
        assertEquals(
            listOf("command", "channel", "open"),
            HeaderPhraseMatcher.normalize("COMMAND  CHANNEL open…")
        )
        assertEquals(
            listOf("go"),
            HeaderPhraseMatcher.normalize("GO!")
        )
    }

    @Test
    fun `the best line wins when several agree`() {
        val reading = read(
            "incoming glyph sequence" to 0.5,
            "INCOMING GLYPH SEQUENCE" to 0.95
        )
        assertEquals(GamePhase.PRESENTING, reading.phase)
        assertEquals(0.95, reading.confidence, 1e-9)
    }
}
