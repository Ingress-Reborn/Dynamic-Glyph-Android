package org.szres.glyphlens.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
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

    @Test
    fun readFillsAMatchingArrayAndReplacesOneOfAnotherSize() {
        val source = ByteBuffer.wrap(ByteArray(16) { (it + 1).toByte() })
        val previous = ByteArray(16) { -1 }

        val reused = FrameConverter.read(2, 2, 42, 4, 8, source, reuse = previous)
        assertSame(previous, reused.pixels)
        assertArrayEquals(ByteArray(16) { (it + 1).toByte() }, reused.pixels)

        // A resized capture must not inherit a stale buffer.
        val resized = FrameConverter.read(1, 2, 43, 4, 8, source, reuse = previous)
        assertNotSame(previous, resized.pixels)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 9, 10, 11, 12), resized.pixels)
    }

    @Test
    fun readRowsPacksEverySteppedRowAcrossStrides() {
        // 2×5 pixels; byte value encodes row and position so any misplaced row is visible.
        for ((pixelStride, rowStride) in listOf(4 to 8, 4 to 12, 6 to 16)) {
            val source = ByteArray(rowStride * 5) { -1 }
            for (y in 0 until 5) for (x in 0 until 2) for (channel in 0 until 4) {
                source[y * rowStride + x * pixelStride + channel] = (y * 16 + x * 4 + channel).toByte()
            }
            fun row(y: Int) = ByteArray(8) { (y * 16 + it).toByte() }

            val rows = FrameConverter.readRows(2, 5, pixelStride, rowStride, ByteBuffer.wrap(source), firstRow = 1, rowStep = 2)
            assertArrayEquals(row(1) + row(3), rows)

            val reused = FrameConverter.readRows(2, 5, pixelStride, rowStride, ByteBuffer.wrap(source), 0, 2, reuse = ByteArray(24))
            assertArrayEquals(row(0) + row(2) + row(4), reused)
            assertEquals(0, FrameConverter.readRows(2, 5, pixelStride, rowStride, ByteBuffer.wrap(source), 5, 2).size)
        }
    }
}
