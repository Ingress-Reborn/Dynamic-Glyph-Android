package org.szres.glyphlens.recognition.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameTextTest {
    @Test
    fun `language metadata preserves recognition order and confidence floors`() {
        assertEquals(listOf("en-US"), GameLanguage.english.recognitionLanguages)
        assertEquals(listOf("zh-Hans", "en-US"), GameLanguage.simplifiedChinese.recognitionLanguages)
        assertEquals(listOf("zh-Hant", "en-US"), GameLanguage.traditionalChinese.recognitionLanguages)
        assertEquals(listOf("ja-JP", "en-US"), GameLanguage.japanese.recognitionLanguages)
        assertEquals(listOf("ko-KR", "en-US"), GameLanguage.korean.recognitionLanguages)
        assertEquals(listOf("English", "简体中文", "繁體中文", "日本語", "한국어"), GameLanguage.entries.map { it.nativeName })
        assertEquals(setOf(GameLanguage.english, GameLanguage.simplifiedChinese, GameLanguage.japanese),
            GameLanguage.entries.filter { it.isRecorded }.toSet())
        assertFalse(GameLanguage.english.needsAccurateRecognition)
        assertTrue(GameLanguage.korean.needsAccurateRecognition)
        assertEquals(GameLanguage.Confidence(0.35), GameLanguage.english.confidence)
        assertEquals(GameLanguage.Confidence(0.3), GameLanguage.japanese.confidence)
    }

}
