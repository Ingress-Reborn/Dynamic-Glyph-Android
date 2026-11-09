package org.szres.glyphlens.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class FrameConverterTest {
    @Test
    fun readSkipsRowPaddingAndPreservesRgba() {
        val source = ByteArray(20) { -1 }
        for (index in 0 until 8) source[index] = (index + 1).toByte()
        for (index in 0 until 8) source[12 + index] = (index + 9).toByte()

        val frame = FrameConverter.read(
            width = 2,
            height = 2,
            timestampNanos = 123456789,
            pixelStride = 4,
            rowStride = 12,
            buffer = ByteBuffer.wrap(source),
        )

        assertEquals(2, frame.width)
        assertEquals(2, frame.height)
        assertEquals(123456789, frame.timestampNanos)
        assertArrayEquals(ByteArray(16) { (it + 1).toByte() }, frame.pixels)
    }

    @Test
    fun readSkipsBytesBetweenPixels() {
        val source = byteArrayOf(
            1, 2, 3, 4, 99, 99,
            5, 6, 7, 8, 99, 99,
        )

        val frame = FrameConverter.read(
            width = 2,
            height = 1,
            timestampNanos = 42,
            pixelStride = 6,
            rowStride = 12,
            buffer = ByteBuffer.wrap(source),
        )

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), frame.pixels)
    }

    @Test
    fun readAcceptsDirectReadOnlyBufferWithoutChangingItsCursor() {
        for (rowStride in listOf(8, 12)) {
            val direct = ByteBuffer.allocateDirect(rowStride + 8)
            for (index in 0 until 8) {
                direct.put(index, (index + 1).toByte())
                direct.put(rowStride + index, (index + 9).toByte())
            }
            val source = direct.asReadOnlyBuffer().apply { position(3) }
            val originalLimit = source.limit()

            val frame = FrameConverter.read(2, 2, 42, 4, rowStride, source)

            assertArrayEquals(ByteArray(16) { (it + 1).toByte() }, frame.pixels)
            assertEquals(3, source.position())
            assertEquals(originalLimit, source.limit())
        }
    }

}
