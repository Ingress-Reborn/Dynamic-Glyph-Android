package org.szres.glyphlens.recognition.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeaderAndCommandTextTest {
    @Test
    fun onlyUnambiguousLocalPhasePhrasesIdentifyLanguage() {
        val samples = listOf(
            GameLanguage.english to "Incoming glyph sequence",
            GameLanguage.simplifiedChinese to "正在接收符文序列",
            GameLanguage.traditionalChinese to "正在接收 GLYPH 序列",
            GameLanguage.japanese to "グリフを受信中",
            GameLanguage.korean to "글리프 시퀀스",
        )
        for ((language, text) in samples) {
            val matcher = HeaderPhraseMatcher(language)
            assertEquals(language, matcher.read(listOf(text to 0.9)).at(2.0).language)
            assertNull(matcher.read(listOf("GO!" to 0.9)).language)
            assertNull(matcher.read(listOf(text to 0.2)).language)
            if (language != GameLanguage.english) {
                assertNull(matcher.read(listOf("Incoming glyph sequence" to 0.9)).language)
            }
        }
        for (language in listOf(GameLanguage.simplifiedChinese, GameLanguage.traditionalChinese)) {
            assertNull(HeaderPhraseMatcher(language).read(listOf("符文入侵完成" to 0.9)).language)
        }
        assertNull(HeaderPhraseMatcher().read(listOf("Incoming glyph sequence" to 0.9, "GO!" to 0.9)).language)
    }

    @Test
    fun `English headers recognize exact phrases and reject guesses`() {
        val matcher = HeaderPhraseMatcher()
        assertEquals(GamePhase.command, matcher.read(listOf("Command channel open…" to 0.9)).phase)
        assertEquals(GamePhase.command, matcher.read(listOf("COMMAND  CHANNEL   OPEN..." to 0.9)).phase)
        assertEquals(GamePhase.presenting, matcher.read(listOf("Incoming glyph sequence" to 0.9)).phase)
        assertEquals(GamePhase.input, matcher.read(listOf("GO!" to 0.9)).phase)
        assertEquals(GamePhase.completed, matcher.read(listOf("Glyph hack complete" to 0.9)).phase)
        for (text in listOf("DRONE FOOTAGE", "Garden Exhibit", "+448 AP", "RECALL", "16:84", "", "go to the portal", "Incoming glyph")) {
            assertEquals(text, GamePhase.unknown, matcher.read(listOf(text to 0.9)).phase)
        }
    }

    @Test
    fun `low confidence and contradictory headers stay unknown`() {
        val matcher = HeaderPhraseMatcher()
        assertEquals(GamePhase.unknown, matcher.read(listOf("Incoming glyph sequence" to 0.2)).phase)
        val conflict = matcher.read(listOf("Incoming glyph sequence" to 0.9, "GO!" to 0.95))
        assertEquals(GamePhase.unknown, conflict.phase)
        assertEquals("conflicting phrases", conflict.matchedText)
    }

    @Test
    fun `CJK headers match within lines while short input requires the whole line`() {
        val samples = listOf(
            Triple(GameLanguage.simplifiedChinese, "命令频道正在打开…", GamePhase.command),
            Triple(GameLanguage.simplifiedChinese, "正在接收符文序列…", GamePhase.presenting),
            Triple(GameLanguage.simplifiedChinese, "开始！", GamePhase.input),
            Triple(GameLanguage.simplifiedChinese, "符文入侵完成", GamePhase.completed),
            Triple(GameLanguage.traditionalChinese, "開啟指令頻道…", GamePhase.command),
            Triple(GameLanguage.traditionalChinese, "正在接收 GLYPH 序列…", GamePhase.presenting),
            Triple(GameLanguage.japanese, "コマンドチャンネルを開いています...", GamePhase.command),
            Triple(GameLanguage.japanese, "グリフを受信中…", GamePhase.presenting),
            Triple(GameLanguage.japanese, "Go!", GamePhase.input),
            Triple(GameLanguage.japanese, "ハック完了。ボーナスを計算中…", GamePhase.completed),
            Triple(GameLanguage.korean, "명령 채널 열림…", GamePhase.command),
            Triple(GameLanguage.korean, "해킹 완료. \ubcf4\ub108\uc2a4 \uacc4\uc0b0 \uc911...", GamePhase.completed),
        )
        for ((language, header, phase) in samples) {
            assertEquals(header, phase, HeaderPhraseMatcher(language).read(listOf(header to 1.0)).phase)
            if (phase == GamePhase.input) {
                assertEquals(GamePhase.unknown,
                    HeaderPhraseMatcher(language).read(listOf("开始 Glyph Hack" to 1.0)).phase)
            }
        }
    }

}
