package org.szres.glyphlens.recognition.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class NodeArrayDetectorTest {
    @Test
    fun ringsRemainSeparateWhenEveryBackgroundPixelIsJustBelowThreshold() {
        val detector = NodeArrayDetector()
        val canvas = SyntheticRaster()
        val expected = requireNotNull(detector.detect(canvas.image()))
        assertEquals(11, expected.matchedNodes.size)

        for (index in canvas.pixels.indices) {
            if ((canvas.pixels[index].toInt() and 0xff) == 8) canvas.pixels[index] = 159.toByte()
        }

        assertEquals(expected, detector.detect(canvas.image()))
        assertSame(expected, detector.revalidate(canvas.image(), expected))
    }

    @Test
    fun thresholdIncludesBrightRingsButRejectsTheirFilledBackground() {
        val detector = NodeArrayDetector()
        val canvas = SyntheticRaster()
        val expected = requireNotNull(detector.detect(canvas.image()))
        for (index in canvas.pixels.indices) {
            canvas.pixels[index] = if ((canvas.pixels[index].toInt() and 0xff) == 8) 159.toByte() else 160.toByte()
        }

        assertEquals(expected, detector.detect(canvas.image()))
        assertSame(expected, detector.revalidate(canvas.image(), expected))

        canvas.pixels.fill(160.toByte())
        assertNull(detector.detect(canvas.image()))
        assertNull(detector.revalidate(canvas.image(), expected))
    }
}
