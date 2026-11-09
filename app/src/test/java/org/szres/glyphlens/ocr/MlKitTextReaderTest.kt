package org.szres.glyphlens.ocr

import org.junit.Assert.assertEquals
import org.junit.Test
import org.szres.glyphlens.recognition.text.GameLanguage

class MlKitTextReaderTest {
    @Test
    fun scaledCropMapsBackToFullFrameCoordinates() {
        val box = normalizeOcrBounds(
            left = 10,
            top = 20,
            right = 30,
            bottom = 60,
            cropLeft = 20,
            cropTop = 40,
            scaleX = 2.0,
            scaleY = 2.0,
            fullFrameWidth = 200,
            fullFrameHeight = 400,
        )

        assertEquals(0.125, box.x, 0.0)
        assertEquals(0.125, box.y, 0.0)
        assertEquals(0.05, box.width, 0.0)
        assertEquals(0.05, box.height, 0.0)
    }

    @Test
    fun unscaledCropDoesNotFlipVerticalOrigin() {
        val box = normalizeOcrBounds(
            left = 4,
            top = 8,
            right = 14,
            bottom = 20,
            cropLeft = 10,
            cropTop = 30,
            scaleX = 1.0,
            scaleY = 1.0,
            fullFrameWidth = 100,
            fullFrameHeight = 200,
        )

        assertEquals(0.14, box.x, 0.0)
        assertEquals(0.19, box.y, 0.0)
        assertEquals(0.10, box.width, 0.0)
        assertEquals(0.06, box.height, 0.0)
    }

    @Test
    fun everyGameLanguageSelectsItsProductionMlKitModelFamily() {
        val expected = mapOf(
            GameLanguage.english to MlKitScript.latin,
            GameLanguage.simplifiedChinese to MlKitScript.chinese,
            GameLanguage.traditionalChinese to MlKitScript.chinese,
            GameLanguage.japanese to MlKitScript.japanese,
            GameLanguage.korean to MlKitScript.korean,
        )

        assertEquals(GameLanguage.entries.toSet(), expected.keys)
        for ((language, script) in expected) {
            assertEquals(language.rawValue, script, language.mlKitScript())
        }
    }

}
