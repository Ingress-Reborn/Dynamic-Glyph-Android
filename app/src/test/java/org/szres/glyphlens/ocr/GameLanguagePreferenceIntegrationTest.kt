package org.szres.glyphlens.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.szres.glyphlens.recognition.text.GameLanguage
import org.szres.glyphlens.recognition.text.GameLanguageMemory
import org.szres.glyphlens.settings.AppPreferences

class GameLanguagePreferenceIntegrationTest {
    @Test
    fun automaticPreferenceStartsFromRememberedLanguageAndPersistsConfirmedChanges() {
        val changes = mutableListOf<GameLanguage>()
        val memory = AppPreferences.makeGameLanguageMemory(
            chosen = null,
            remembered = GameLanguage.traditionalChinese,
            preferredLanguages = listOf("ja-JP"),
            onChange = changes::add,
        )

        assertTrue(memory.isAutomatic)
        assertEquals(GameLanguage.traditionalChinese, memory.current)
        assertFalse(memory.isConfirmed)
        memory.found(GameLanguage.japanese)
        memory.found(GameLanguage.japanese)
        assertEquals(GameLanguageMemory.State(GameLanguage.japanese, true), memory.state)
        assertEquals(listOf(GameLanguage.japanese), changes)
    }

    @Test
    fun automaticPreferenceUsesSystemLanguageThenEnglish() {
        val traditional = AppPreferences.makeGameLanguageMemory(
            chosen = null,
            remembered = null,
            preferredLanguages = listOf("fr-FR", "zh-Hant-TW"),
        )
        val fallback = AppPreferences.makeGameLanguageMemory(
            chosen = null,
            remembered = null,
            preferredLanguages = listOf("fr-FR"),
        )

        assertEquals(GameLanguage.traditionalChinese, traditional.current)
        assertEquals(GameLanguage.english, fallback.current)
    }

    @Test
    fun chosenPreferenceNeverSwitchesOrPublishesAutomaticDetection() {
        val changes = mutableListOf<GameLanguage>()
        val memory = AppPreferences.makeGameLanguageMemory(
            chosen = GameLanguage.korean,
            remembered = GameLanguage.japanese,
            preferredLanguages = listOf("zh-Hans"),
            onChange = changes::add,
        )

        assertFalse(memory.isAutomatic)
        memory.found(GameLanguage.japanese)
        assertEquals(GameLanguage.korean, memory.current)
        assertFalse(memory.isConfirmed)
        assertTrue(changes.isEmpty())
    }

}
