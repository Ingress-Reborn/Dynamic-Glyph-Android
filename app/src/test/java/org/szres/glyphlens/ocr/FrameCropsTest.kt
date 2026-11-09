package org.szres.glyphlens.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameCropsTest {
    @Test
    fun rgbaBytesBecomeTheExpectedAndroidPixel() {
        assertEquals(0x80204010.toInt(), rgbaPixelToArgb(0x20, 0x40, 0x10, 0x80.toByte()))
        assertEquals(0xFFFFFFFF.toInt(), rgbaPixelToArgb(-1, -1, -1, -1))
        assertEquals(0x00000000, rgbaPixelToArgb(0, 0, 0, 0))
    }
}
