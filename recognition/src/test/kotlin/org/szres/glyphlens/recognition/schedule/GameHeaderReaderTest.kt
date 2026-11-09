package org.szres.glyphlens.recognition.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.szres.glyphlens.recognition.image.ColorImage
import org.szres.glyphlens.recognition.image.FrameTopBand
import org.szres.glyphlens.recognition.schedule.GameTextSchedule.Request
import org.szres.glyphlens.recognition.text.CaptureSessionID
import org.szres.glyphlens.recognition.text.GameLanguage
import org.szres.glyphlens.recognition.text.GameLanguageMemory
import org.szres.glyphlens.recognition.text.GamePhase
import org.szres.glyphlens.recognition.text.HeaderReading
import java.util.stream.IntStream

class GameHeaderReaderTest {
    private val session = CaptureSessionID(1UL)

    @Test
    fun automaticLanguageConfirmsOnlyTheAcceptedLocalHeader() {
        val languages = GameLanguageMemory(GameLanguage.english, isAutomatic = true)
        val reader = GameHeaderReader(GameHeaderReader.Configuration(languages = languages))
        for ((time, language) in listOf(
            0.0 to GameLanguage.english,
            1.0 to GameLanguage.simplifiedChinese,
            2.0 to GameLanguage.traditionalChinese,
            3.0 to GameLanguage.japanese,
        )) {
            val request = reader.submit(time, session)!!
            assertEquals(language, reader.submittedLanguage(request))
            reader.finishHeader(request, listOf("グリフを受信中" to 0.9))
        }
        assertEquals(GameLanguage.japanese, languages.current)
        assertTrue(languages.isConfirmed)
        val next = reader.submit(4.0, session)!!
        assertEquals(GameLanguage.japanese, reader.submittedLanguage(next))
    }

    @Test
    fun retiredHeaderCannotConfirmAutomaticLanguage() {
        val languages = GameLanguageMemory(GameLanguage.japanese, isAutomatic = true)
        val reader = GameHeaderReader(GameHeaderReader.Configuration(languages = languages))
        val request = reader.submit(0.0, session)!!
        reader.invalidate()
        reader.finishHeader(request, listOf("グリフを受信中" to 0.9))
        assertFalse(languages.isConfirmed)
        assertNull(reader.latestReading(session))
        assertEquals(1, reader.snapshotMetrics().staleCompletions)
    }

    private fun band(
        width: Int = 64,
        height: Int = 200,
        changedRow: Int? = null,
        patterned: Boolean = false,
        frameHeight: Int = height,
    ): FrameTopBand {
        val pixels = ByteArray(width * height * 3)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val offset = (y * width + x) * 3
                if (patterned) {
                    pixels[offset] = ((y * 17 + x * 3) % 251).toByte()
                    pixels[offset + 1] = ((y * 29 + x * 5 + 1) % 251).toByte()
                    pixels[offset + 2] = ((y * 43 + x * 7 + 2) % 251).toByte()
                } else {
                    pixels[offset] = 20
                    pixels[offset + 1] = 20
                    pixels[offset + 2] = 20
                }
            }
        }
        if (changedRow != null) pixels[(changedRow * width + width / 2) * 3 + 1] = 21
        return FrameTopBand(ColorImage(width, height, pixels), frameHeight)
    }

    private fun signature(band: FrameTopBand): HeaderSignature = HeaderSignature.from(band)!!

    private fun unknown(request: Request) = HeaderReading(GamePhase.unknown, "", 0.0, request.sourceTime)

    @Test
    fun `same outside header skips keeps original timestamp and refreshes at one second`() {
        val frame = band()
        val signature = signature(frame)
        val reader = GameHeaderReader()
        val first = reader.submit(
            0.0, session, headerInterval = 0.5, inHack = false, headerSignature = signature,
        )!!
        reader.finishHeader(first, emptyList())
        assertNull(reader.submit(
            0.5, session, headerInterval = 0.5, inHack = false, headerSignature = signature,
        ))
        assertEquals(1, reader.snapshotMetrics().headerSkippedUnchanged)
        assertEquals(1, reader.snapshotMetrics().submitted)
        assertEquals(0.0, reader.reading(0.3, session)?.sourceTime)
        assertNull(reader.reading(0.5, session))
        assertNull(reader.submit(
            0.999, session, headerInterval = 0.5, inHack = false, headerSignature = signature,
        ))
        val refresh = reader.submit(
            1.0, session, headerInterval = 0.5, inHack = false, headerSignature = signature,
        )!!
        reader.finishHeader(refresh, emptyList())
        assertEquals(2, reader.snapshotMetrics().headerOutsideSubmitted)
        assertEquals(1.0, reader.reading(1.0, session)?.sourceTime)
    }

    @Test
    fun `every changed crop byte triggers next eligible look and a skip consumes no cadence`() {
        val original = signature(band())
        val below = signature(band(changedRow = 30))
        val changed = signature(band(changedRow = 15))
        val reader = GameHeaderReader()
        val first = reader.submit(0.0, session, headerInterval = 0.5, inHack = false, headerSignature = original)!!
        reader.finish(first, header = unknown(first))
        assertNull(reader.submit(0.5, session, headerInterval = 0.5, inHack = false, headerSignature = below))
        assertEquals(1, reader.snapshotMetrics().headerSkippedUnchanged)
        val next = reader.submit(0.55, session, headerInterval = 0.5, inHack = false, headerSignature = changed)!!
        reader.finish(next, header = unknown(next))
        assertEquals(2, reader.snapshotMetrics().submitted)
        assertEquals(0.55, reader.reading(0.55, session)?.sourceTime)
    }

    @Test
    fun `signature uses fractional integral crop and frame dimensions`() {
        val original = signature(band(width = 67, height = 213, patterned = true))
        assertEquals(12 until 23, original.rows)
        assertEquals(67 * 11 * 3, original.pixels.size)
        for (row in listOf(11, 12, 22, 23)) {
            val changed = signature(band(width = 67, height = 213, changedRow = row, patterned = true))
            assertEquals("row $row", row == 12 || row == 22, changed != original)
        }
        val resized = signature(band(width = 68, height = 213, patterned = true))
        assertTrue(resized != original)
        val sameCropDifferentFrameHeight = signature(
            band(width = 67, height = 213, patterned = true, frameHeight = 214),
        )
        assertTrue(sameCropDifferentFrameHeight.pixels.contentEquals(original.pixels))
        assertTrue(sameCropDifferentFrameHeight != original)
    }

    @Test
    fun `top band supplies the reader crop and signature bytes are immutable`() {
        val frame = band(patterned = true)
        val signature = signature(frame)
        val expected = signature.pixels
        val exposed = signature.pixels
        exposed[0] = (exposed[0].toInt() xor 0xff).toByte()
        assertTrue(expected.contentEquals(signature.pixels))

        val reader = GameHeaderReader()
        val first = reader.submit(0.0, session, headerInterval = 0.5, inHack = false, topBand = frame)!!
        reader.finish(first, header = unknown(first))
        assertNull(reader.submit(0.5, session, headerInterval = 0.5, inHack = false, topBand = frame))
        assertEquals(1, reader.snapshotMetrics().headerSkippedUnchanged)
    }

    @Test
    fun `hack cues bypass unchanged pixels without accelerating cadence`() {
        val signature = signature(band())
        val reader = GameHeaderReader()
        val first = reader.submit(0.0, session, headerInterval = 0.5, inHack = false, headerSignature = signature)!!
        reader.finish(first, header = unknown(first))
        val fresh = reader.submit(
            0.5, session, headerInterval = 0.5, inHack = false,
            requiresFreshHeader = true, headerSignature = signature,
        )!!
        reader.finish(fresh, header = unknown(fresh))
        assertEquals(2, reader.snapshotMetrics().headerOutsideSubmitted)
        assertEquals(0, reader.snapshotMetrics().headerSkippedUnchanged)
        assertEquals(0.5, reader.reading(0.5, session)?.sourceTime)
        assertNull(reader.submit(
            0.6, session, headerInterval = 0.5, inHack = false,
            requiresFreshHeader = true, headerSignature = signature,
        ))
        val ordinary = reader.submit(1.0, session, headerInterval = 0.5, inHack = false, headerSignature = signature)!!
        reader.finish(ordinary, header = unknown(ordinary))
        assertEquals(3, reader.snapshotMetrics().submitted)
    }

    @Test
    fun `freshness includes zero and maximum age and rejects wrong session or clocks`() {
        val reader = GameHeaderReader()
        val request = reader.submit(0.0, session)!!
        reader.finish(request, header = unknown(request))
        assertEquals(GamePhase.unknown, reader.reading(0.0, session)?.phase)
        assertEquals(0.0, reader.reading(0.35, session)?.sourceTime)
        assertNull(reader.reading(Math.nextUp(0.35), session))
        assertNull(reader.reading(-Double.MIN_VALUE, session))
        assertNull(reader.reading(Double.NaN, session))
        assertNull(reader.reading(Double.POSITIVE_INFINITY, session))
        assertNull(reader.reading(0.0, CaptureSessionID(2UL)))
        assertNotNull(reader.latestReading(session))
        assertNull(reader.latestReading(CaptureSessionID(2UL)))
    }

    @Test
    fun `forged header source time is stale and rejection preserves cadence`() {
        val reader = GameHeaderReader()
        val request = reader.submit(1.0, session)!!
        reader.finish(request, header = unknown(request).copy(sourceTime = Math.nextUp(1.0)))
        assertEquals(1, reader.snapshotMetrics().staleCompletions)
        assertEquals(0, reader.snapshotMetrics().completed)
        assertFalse(reader.isReading)
        assertNull(reader.reading(1.0, session))
        assertNull(reader.submit(1.0625, session))
        val fresh = reader.submit(1.125, session)!!
        reader.finishHeader(fresh, listOf("Incoming glyph sequence" to 0.9))
        assertEquals(GamePhase.presenting, reader.reading(1.125, session)?.phase)
        assertEquals(1.125, reader.reading(1.125, session)?.sourceTime)
    }

    @Test
    fun `session invalidation rejects old completion without opening another lane`() {
        val signature = signature(band())
        val reader = GameHeaderReader()
        val old = reader.submit(0.0, session, headerInterval = 0.5, inHack = false, headerSignature = signature)!!
        reader.invalidate()
        assertTrue(reader.isReading)
        assertNull(reader.submit(0.5, session, headerInterval = 0.5, inHack = false, headerSignature = signature))
        assertEquals(1, reader.snapshotMetrics().droppedBusy)
        reader.finish(old, header = unknown(old))
        assertEquals(1, reader.snapshotMetrics().staleCompletions)
        assertFalse(reader.isReading)
        assertNull(reader.reading(0.0, session))
        val fresh = reader.submit(0.6, session, headerInterval = 0.5, inHack = false, headerSignature = signature)!!
        reader.finish(fresh, header = unknown(fresh))
        assertEquals(2, reader.snapshotMetrics().submitted)
        assertEquals(0.6, reader.reading(0.6, session)?.sourceTime)
    }

    @Test
    fun `phase invalidation clears baseline and old phase completion but not lane`() {
        for (oldInFlight in listOf(false, true)) {
            val signature = signature(band(patterned = true))
            val reader = GameHeaderReader()
            val old = reader.submit(
                100.0, session, headerInterval = 0.5, inHack = false, headerSignature = signature,
            )!!
            if (!oldInFlight) reader.finish(old, header = unknown(old))
            reader.invalidatePhase()
            assertNull(reader.reading(100.0, session))
            if (oldInFlight) {
                assertTrue(reader.isReading)
                assertNull(reader.submit(
                    100.5, session, headerInterval = 0.5, inHack = false, headerSignature = signature,
                ))
                reader.finish(old, header = unknown(old))
                assertEquals(1, reader.snapshotMetrics().staleCompletions)
            }
            val fresh = reader.submit(
                100.5, session, headerInterval = 0.5, inHack = false, headerSignature = signature,
            )!!
            reader.finishHeader(fresh, listOf("Incoming glyph sequence" to 1.0))
            assertEquals(2, reader.snapshotMetrics().submitted)
            assertEquals(0, reader.snapshotMetrics().headerSkippedUnchanged)
            assertEquals(GamePhase.presenting, reader.reading(100.5, session)?.phase)
            assertNull(reader.submit(
                101.0, session, headerInterval = 0.5, inHack = false, headerSignature = signature,
            ))
            assertEquals(1, reader.snapshotMetrics().headerSkippedUnchanged)
            assertNull(reader.reading(101.0, session))
        }
    }

    @Test
    fun `submitted language drives vocabulary and source time is never repaired`() {
        val languages = GameLanguageMemory(GameLanguage.simplifiedChinese, isAutomatic = true)
        val reader = GameHeaderReader(GameHeaderReader.Configuration(languages = languages))
        val chinese = reader.submit(0.0, session)!!
        assertEquals(GameLanguage.simplifiedChinese, reader.submittedLanguage(chinese))
        languages.found(GameLanguage.japanese)
        assertEquals(GameLanguage.simplifiedChinese, reader.submittedLanguage(chinese))
        reader.finishHeader(chinese, listOf("正在接收符文序列…" to 0.5))
        assertNull(reader.submittedLanguage(chinese))
        assertEquals(GamePhase.presenting, reader.reading(0.0, session)?.phase)
        assertEquals(0.0, reader.reading(0.0, session)?.sourceTime)

        val englishFallback = reader.submit(0.1, session)!!
        assertEquals(GameLanguage.simplifiedChinese, reader.submittedLanguage(englishFallback))
        reader.finishHeader(englishFallback, listOf("GO!" to 0.5))
        assertEquals(GamePhase.input, reader.reading(0.1, session)?.phase)
        assertEquals(0.1, reader.reading(0.1, session)?.sourceTime)
    }

    @Test
    fun `every game language reaches its submitted header request`() {
        for (language in GameLanguage.entries) {
            val reader = GameHeaderReader(
                GameHeaderReader.Configuration(
                    languages = GameLanguageMemory(language, isAutomatic = false),
                ),
            )
            val request = reader.submit(0.0, session)!!
            assertEquals(language.rawValue, language, reader.submittedLanguage(request))
            reader.finishHeader(request, emptyList())
        }
    }

    @Test
    fun `chosen header language keeps English fallback without switching or confirming`() {
        val languages = GameLanguageMemory(GameLanguage.korean, isAutomatic = false)
        val reader = GameHeaderReader(GameHeaderReader.Configuration(languages = languages))
        val request = reader.submit(0.0, session)!!

        languages.found(GameLanguage.japanese)
        assertEquals(GameLanguage.korean, reader.submittedLanguage(request))
        reader.finishHeader(request, listOf("GO!" to 0.5))

        assertEquals(GamePhase.input, reader.reading(0.0, session)?.phase)
        assertEquals(GameLanguage.korean, languages.current)
        assertFalse(languages.isConfirmed)
    }

    @Test
    fun `failed header does not suppress retry on identical pixels`() {
        val signature = signature(band())
        val reader = GameHeaderReader()
        val failed = reader.submit(0.0, session, headerInterval = 0.5, inHack = false, headerSignature = signature)!!
        reader.finish(failed, error = "OCR failed")
        val retry = reader.submit(0.5, session, headerInterval = 0.5, inHack = false, headerSignature = signature)!!
        reader.finish(retry, header = unknown(retry))
        assertEquals(1, reader.snapshotMetrics().failed)
        assertEquals(0, reader.snapshotMetrics().headerSkippedUnchanged)
        assertEquals(2, reader.snapshotMetrics().submitted)
        assertEquals("OCR failed", reader.snapshotMetrics().lastError)
    }

    @Test
    fun `concurrent looks neither open another lane nor lose unchanged counts`() {
        val signature = signature(band())
        val busyReader = GameHeaderReader()
        val first = busyReader.submit(
            0.0, session, headerInterval = 0.5, inHack = false, headerSignature = signature,
        )!!
        IntStream.range(0, 20).parallel().forEach {
            busyReader.submit(0.5, session, headerInterval = 0.5, inHack = false, headerSignature = signature)
        }
        assertEquals(1, busyReader.snapshotMetrics().submitted)
        assertEquals(20, busyReader.snapshotMetrics().droppedBusy)
        busyReader.finish(first, header = unknown(first))

        val skipReader = GameHeaderReader()
        val initial = skipReader.submit(
            0.0, session, headerInterval = 0.5, inHack = false, headerSignature = signature,
        )!!
        skipReader.finish(initial, header = unknown(initial))
        IntStream.range(0, 20).parallel().forEach {
            skipReader.submit(0.5, session, headerInterval = 0.5, inHack = false, headerSignature = signature)
        }
        assertEquals(1, skipReader.snapshotMetrics().submitted)
        assertEquals(20, skipReader.snapshotMetrics().headerSkippedUnchanged)
        assertFalse(skipReader.isReading)
    }
}
