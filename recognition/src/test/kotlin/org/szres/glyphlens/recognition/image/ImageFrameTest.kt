package org.szres.glyphlens.recognition.image

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.szres.glyphlens.recognition.RgbaFrame

class ImageFrameTest {
    @Test
    fun rgbaEntryReadsSamplesWithoutPackingAndPreservesSourceHeightAndTimestamp() {
        val pixels = ByteArray(2 * 10 * 4)
        for (pixel in 0 until 20) {
            pixels[pixel * 4] = 200.toByte()
            pixels[pixel * 4 + 1] = 100.toByte()
            pixels[pixel * 4 + 2] = 50.toByte()
            pixels[pixel * 4 + 3] = 255.toByte()
        }
        val frame = ImageFrame.fromRgba(RgbaFrame(2, 10, 1_234_567_890, pixels))
        assertFalse(frame.luminance.hasPackedPixels)
        assertFalse(frame.topBand.image.hasPackedPixels)
        assertEquals(3, frame.topBand.image.height) // ceil(0.246 * 10)
        assertEquals(10, frame.topBand.frameHeight)
        assertEquals(1_234_567_890, frame.timestampNanos)
        assertEquals(1.23456789, frame.timeSeconds, 1e-12)
        assertEquals(RgbPixel(200, 100, 50), frame.topBand.image[1, 2])
        assertEquals(RgbPixel(200, 100, 50), frame.topBand.image[5])
        assertEquals(124, frame.luminance[1, 2])
        assertEquals(124, frame.luminance[5])
        assertEquals(LumaGate(1).read(frame.luminance), LumaGate(1).read(RgbaFrame(2, 10, 0, pixels)))
        assertFalse(frame.luminance.hasPackedPixels)
        assertFalse(frame.topBand.image.hasPackedPixels)
    }

    @Test
    fun fullColorViewPreservesAllRowsAndOnlyPacksWhenRequested() {
        val pixels = ByteArray(2 * 10 * 4)
        for (pixel in 0 until 20) {
            pixels[pixel * 4] = 200.toByte()
            pixels[pixel * 4 + 1] = 100.toByte()
            pixels[pixel * 4 + 2] = 50.toByte()
            pixels[pixel * 4 + 3] = 255.toByte()
        }
        pixels[19 * 4] = 255.toByte()
        pixels[19 * 4 + 1] = 0
        pixels[19 * 4 + 2] = 0
        val rgba = RgbaFrame(2, 10, 1_234_567_890, pixels)
        val cropped = ImageFrame.fromRgba(rgba)
        val full = ImageFrame.fromRgba(rgba, fullColor = true)

        assertEquals(10, full.topBand.image.height)
        assertEquals(10, full.topBand.frameHeight)
        assertEquals(rgba.timestampNanos, full.timestampNanos)
        assertEquals(RgbPixel(255, 0, 0), full.topBand.image[1, 9])
        assertEquals(RgbPixel(255, 0, 0), full.topBand.image[19])
        assertEquals(76, full.luminance[1, 9])
        assertEquals(76, full.luminance[19])
        assertFalse(full.luminance.hasPackedPixels)
        assertFalse(full.topBand.image.hasPackedPixels)
        assertEquals(cropped.topBand.rows(0.104, 0.176), full.topBand.rows(0.104, 0.176))
        val reader = SlotStripReader()
        assertEquals(reader.read(cropped), reader.read(full))
        assertEquals(reader.hasTimerBar(cropped), reader.hasTimerBar(full))
        assertFalse(cropped.luminance.hasPackedPixels)
        assertFalse(cropped.topBand.image.hasPackedPixels)
        assertFalse(full.luminance.hasPackedPixels)
        assertFalse(full.topBand.image.hasPackedPixels)

        assertEquals(60, full.topBand.image.pixels.size)
        assertArrayEquals(cropped.luminance.pixels, full.luminance.pixels)
        assertArrayEquals(
            cropped.topBand.image.pixels,
            full.topBand.image.pixels.copyOf(cropped.topBand.image.pixels.size),
        )
        assertTrue(cropped.luminance.hasPackedPixels)
        assertTrue(cropped.topBand.image.hasPackedPixels)
        assertTrue(full.luminance.hasPackedPixels)
        assertTrue(full.topBand.image.hasPackedPixels)
    }

    @Test
    fun rgbaEntryRejectsTruncatedOrInvalidBuffers() {
        assertThrows(IllegalArgumentException::class.java) {
            ImageFrame.fromRgba(RgbaFrame(0, 1, 0, ByteArray(0)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImageFrame.fromRgba(RgbaFrame(2, 2, 0, ByteArray(15)))
        }
    }

    @Test
    fun opaqueCaptureAlphaDoesNotChangeColorOrGateLuma() {
        val opaque = RgbaFrame(1, 1, 9, byteArrayOf(10, 20, 30, 255.toByte()))
        val alsoOpaque = RgbaFrame(1, 1, 9, byteArrayOf(10, 20, 30, 0))
        assertEquals(
            ImageFrame.fromRgba(opaque).luminance[0, 0],
            ImageFrame.fromRgba(alsoOpaque).luminance[0, 0],
        )
        assertEquals(
            ImageFrame.fromRgba(opaque).topBand.image[0],
            ImageFrame.fromRgba(alsoOpaque).topBand.image[0],
        )
    }

}
