package org.szres.glyphlens.recognition.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameLanguageMemoryTest {
    @Test
    fun automaticHeaderProbesOneLanguagePerRetryIntervalUntilConfirmed() {
        val changes = mutableListOf<GameLanguage>()
        val memory = GameLanguageMemory(GameLanguage.english, isAutomatic = true) { changes += it }
        assertEquals(GameLanguage.english, memory.headerLanguage(0.0))
        assertEquals(GameLanguage.english, memory.headerLanguage(0.5))
        assertEquals(GameLanguage.simplifiedChinese, memory.headerLanguage(1.0))
        assertEquals(GameLanguage.traditionalChinese, memory.headerLanguage(2.0))
        assertEquals(GameLanguage.japanese, memory.headerLanguage(3.0))
        assertEquals(GameLanguage.english, memory.current)
        assertFalse(memory.isConfirmed)
        assertTrue(changes.isEmpty())
        memory.found(GameLanguage.japanese)
        assertEquals(GameLanguageMemory.State(GameLanguage.japanese, true), memory.state)
        assertEquals(GameLanguage.japanese, memory.headerLanguage(4.0))
        assertEquals(listOf(GameLanguage.japanese), changes)
    }

    @Test
    fun chosenLanguageNeverRotatesOrChanges() {
        val memory = GameLanguageMemory(GameLanguage.korean, isAutomatic = false)
        assertEquals(GameLanguage.korean, memory.headerLanguage(0.0))
        assertEquals(GameLanguage.korean, memory.headerLanguage(10.0))
        memory.found(GameLanguage.english)
        assertEquals(GameLanguage.korean, memory.current)
        assertFalse(memory.isConfirmed)
    }

    @Test
    fun `automatic reading starts from remembered then preferred game language`() {
        assertEquals(GameLanguage.korean,
            GameLanguageMemory.startingGuess(GameLanguage.korean, listOf("ja-JP")))
        assertEquals(GameLanguage.traditionalChinese,
            GameLanguageMemory.startingGuess(null, listOf("zh-Hant-TW", "en")))
        assertEquals(GameLanguage.simplifiedChinese,
            GameLanguageMemory.startingGuess(null, listOf("zh-Hans-CN")))
        assertEquals(GameLanguage.japanese,
            GameLanguageMemory.startingGuess(null, listOf("fr-FR", "ja-JP")))
        assertEquals(GameLanguage.english,
            GameLanguageMemory.startingGuess(null, listOf("fr-FR")))
    }
}
