package org.szres.glyphlens.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.szres.glyphlens.recognition.image.ColorImage
import org.szres.glyphlens.recognition.image.GameUILayout
import org.szres.glyphlens.recognition.schedule.HeaderSignature
import org.szres.glyphlens.recognition.text.GameLanguage
import org.szres.glyphlens.recognition.text.OcrTextLine

class HeaderTextCacheTest {
    private val language = GameLanguage.english
    private val lines = listOf(OcrTextLine("INCOMING GLYPH SEQUENCE", 0.99, null))

    @Test
    fun identicalNewFrameReusesTextButAnyChangedHeaderByteRunsOcr() {
        val cache = HeaderTextCache()
        val pixels = ByteArray(100 * 200 * 3)
        var calls = 0
        val recognize = { calls++; lines }
        val first = HeaderSignature.from(ColorImage(100, 200, pixels))
        assertSame(lines, cache.read(first, language, recognize))

        pixels[(199 * 100 + 99) * 3] = 1
        val changedOutsideHeader = HeaderSignature.from(ColorImage(100, 200, pixels.copyOf()))
        assertSame(lines, cache.read(changedOutsideHeader, language, recognize))
        assertEquals(1, calls)

        pixels[(15 * 100 + 50) * 3] = 1
        cache.read(HeaderSignature.from(ColorImage(100, 200, pixels)), language, recognize)
        assertEquals(2, calls)
    }

    @Test
    fun languageCropAndSourceDimensionsArePartOfTheInput() {
        val cache = HeaderTextCache()
        val image = ColorImage(100, 200, ByteArray(100 * 200 * 3))
        var calls = 0
        val recognize = { calls++; lines }
        cache.read(HeaderSignature.from(image), language, recognize)
        cache.read(HeaderSignature.from(image), GameLanguage.japanese, recognize)
        cache.read(
            HeaderSignature.from(image, layout = GameUILayout(contentLeft = 0.1, contentRight = 0.9)),
            GameLanguage.japanese,
            recognize,
        )
        cache.read(
            HeaderSignature.from(image, layout = GameUILayout(headerTextTop = 0.065, headerTextBottom = 0.109)),
            GameLanguage.japanese,
            recognize,
        )
        cache.read(
            HeaderSignature.from(ColorImage(100, 201, ByteArray(100 * 201 * 3))),
            GameLanguage.japanese,
            recognize,
        )
        assertEquals(5, calls)
    }

    @Test
    fun onlyTheLastExactInputIsRetainedAndMissingSignaturesAreNeverReused() {
        val cache = HeaderTextCache()
        val first = HeaderSignature.from(ColorImage(100, 200, ByteArray(100 * 200 * 3)))
        val second = HeaderSignature.from(ColorImage(100, 200, ByteArray(100 * 200 * 3) { 1 }))
        var calls = 0
        val recognize = { calls++; lines }
        for (signature in listOf(first, second, first, null, null, first)) {
            cache.read(signature, language, recognize)
        }
        assertEquals(6, calls)
    }
}
