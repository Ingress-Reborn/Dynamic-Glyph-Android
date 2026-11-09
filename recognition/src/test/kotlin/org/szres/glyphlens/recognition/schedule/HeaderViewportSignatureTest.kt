package org.szres.glyphlens.recognition.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.szres.glyphlens.recognition.RgbaFrame
import org.szres.glyphlens.recognition.image.ColorImage
import org.szres.glyphlens.recognition.image.GameUILayout
import org.szres.glyphlens.recognition.image.ImageFrame

class HeaderViewportSignatureTest {
    @Test
    fun rgbaViewCopiesOnlyTheHeaderWithoutPackingTheFrame() {
        val layout = GameUILayout(headerTextTop = 0.2, headerTextBottom = 0.4, contentLeft = 0.25, contentRight = 0.75)
        val rgb = ByteArray(20 * 40 * 3) { (it % 251).toByte() }
        val rgba = ByteArray(20 * 40 * 4) { index ->
            if (index % 4 == 3) 255.toByte() else rgb[index / 4 * 3 + index % 4]
        }
        val reference = HeaderSignature.from(ColorImage(20, 40, rgb), layout = layout)!!
        val frame = ImageFrame.fromRgba(RgbaFrame(20, 40, 100, rgba), fullColor = true)
        val signature = HeaderSignature.from(frame.topBand, layout)!!
        assertEquals(reference, signature)
        assertEquals(10 * 8 * 3, signature.pixels.size)
        assertFalse(frame.luminance.hasPackedPixels)
        assertFalse(frame.topBand.image.hasPackedPixels)

        val outside = rgba.copyOf().also { it[(10 * 20 + 2) * 4] = 0 }
        val outsideFrame = ImageFrame.fromRgba(RgbaFrame(20, 40, 100, outside), fullColor = true)
        assertEquals(signature, HeaderSignature.from(outsideFrame.topBand, layout))
        assertFalse(outsideFrame.topBand.image.hasPackedPixels)

        val inside = rgba.copyOf().also { it[(10 * 20 + 10) * 4] = 0 }
        val insideFrame = ImageFrame.fromRgba(RgbaFrame(20, 40, 100, inside), fullColor = true)
        assertNotEquals(signature, HeaderSignature.from(insideFrame.topBand, layout))
        assertFalse(insideFrame.topBand.image.hasPackedPixels)
    }

    @Test
    fun headerSignatureUsesLocatedRowsAndColumnsAndIgnoresOutsideContent() {
        val layout = GameUILayout(headerTextTop = 0.2, headerTextBottom = 0.4, contentLeft = 0.25, contentRight = 0.75)
        val pixels = ByteArray(20 * 40 * 3) { (it % 251).toByte() }
        val original = HeaderSignature.from(ColorImage(20, 40, pixels), layout = layout)!!
        assertEquals(5 until 15, original.columns)
        assertEquals(8 until 16, original.rows)
        assertEquals(10 * 8 * 3, original.pixels.size)
        val changedOutside = pixels.copyOf().also { it[(10 * 20 + 2) * 3] = 0 }
        assertEquals(original, HeaderSignature.from(ColorImage(20, 40, changedOutside), layout = layout))
        val changedInside = pixels.copyOf().also { it[(10 * 20 + 10) * 3] = 0 }
        assertNotEquals(original, HeaderSignature.from(ColorImage(20, 40, changedInside), layout = layout))
    }

    @Test
    fun equalPixelsAtDifferentViewportPositionsAreDifferentEvidence() {
        val image = ColorImage(100, 200, ByteArray(100 * 200 * 3))
        val left = GameUILayout(contentLeft = 0.1, contentRight = 0.6)
        val right = left.copy(contentLeft = 0.4, contentRight = 0.9)
        assertNotEquals(HeaderSignature.from(image, layout = left), HeaderSignature.from(image, layout = right))
    }
}
