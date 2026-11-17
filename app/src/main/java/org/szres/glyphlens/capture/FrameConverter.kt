package org.szres.glyphlens.capture

import org.szres.glyphlens.recognition.RgbaFrame
import java.nio.ByteBuffer

internal object FrameConverter {
    /**
     * Copies one capture into owned RGBA bytes. A [reuse] array of the right size receives the
     * pixels instead of a new allocation, so the caller must be finished with its previous frame.
     */
    fun read(
        width: Int,
        height: Int,
        timestampNanos: Long,
        pixelStride: Int,
        rowStride: Int,
        buffer: ByteBuffer,
        reuse: ByteArray? = null,
    ): RgbaFrame {
        require(width > 0 && height > 0)
        require(pixelStride >= 4 && rowStride >= width * pixelStride)
        val requiredCapacity = (height - 1) * rowStride + (width - 1) * pixelStride + 4
        require(requiredCapacity <= buffer.capacity())

        val source = buffer.duplicate()
        val byteCount = width * height * 4
        val pixels = reuse?.takeIf { it.size == byteCount } ?: ByteArray(byteCount)
        if (pixelStride == 4) {
            val rowBytes = width * 4
            if (rowStride == rowBytes) {
                source.position(0)
                source.get(pixels, 0, byteCount)
            } else {
                for (y in 0 until height) {
                    source.position(y * rowStride)
                    source.get(pixels, y * rowBytes, rowBytes)
                }
            }
        } else {
            var destination = 0
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val offset = y * rowStride + x * pixelStride
                    pixels[destination] = source.get(offset)
                    pixels[destination + 1] = source.get(offset + 1)
                    pixels[destination + 2] = source.get(offset + 2)
                    pixels[destination + 3] = source.get(offset + 3)
                    destination += 4
                }
            }
        }
        return RgbaFrame(width, height, timestampNanos, pixels)
    }

    /**
     * Copies only every [rowStep]th row from [firstRow], packed one after another as RGBA.
     * This is what a luminance gate needs to decide whether the whole frame is worth copying.
     */
    fun readRows(
        width: Int,
        height: Int,
        pixelStride: Int,
        rowStride: Int,
        buffer: ByteBuffer,
        firstRow: Int,
        rowStep: Int,
        reuse: ByteArray? = null,
    ): ByteArray {
        require(width > 0 && height > 0 && firstRow >= 0 && rowStep > 0)
        require(pixelStride >= 4 && rowStride >= width * pixelStride)
        val requiredCapacity = (height - 1) * rowStride + (width - 1) * pixelStride + 4
        require(requiredCapacity <= buffer.capacity())

        val source = buffer.duplicate()
        val rowBytes = width * 4
        val rowCount = if (height <= firstRow) 0 else (height - firstRow + rowStep - 1) / rowStep
        val rows = reuse?.takeIf { it.size == rowCount * rowBytes } ?: ByteArray(rowCount * rowBytes)
        var destination = 0
        var y = firstRow
        while (y < height) {
            if (pixelStride == 4) {
                source.position(y * rowStride)
                source.get(rows, destination, rowBytes)
                destination += rowBytes
            } else {
                for (x in 0 until width) {
                    val offset = y * rowStride + x * pixelStride
                    rows[destination] = source.get(offset)
                    rows[destination + 1] = source.get(offset + 1)
                    rows[destination + 2] = source.get(offset + 2)
                    rows[destination + 3] = source.get(offset + 3)
                    destination += 4
                }
            }
            y += rowStep
        }
        return rows
    }
}
