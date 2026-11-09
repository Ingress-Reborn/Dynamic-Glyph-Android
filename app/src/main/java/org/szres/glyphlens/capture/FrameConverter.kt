package org.szres.glyphlens.capture

import org.szres.glyphlens.recognition.RgbaFrame
import java.nio.ByteBuffer

internal object FrameConverter {
    fun read(
        width: Int,
        height: Int,
        timestampNanos: Long,
        pixelStride: Int,
        rowStride: Int,
        buffer: ByteBuffer,
    ): RgbaFrame {
        require(width > 0 && height > 0)
        require(pixelStride >= 4 && rowStride >= width * pixelStride)
        val requiredCapacity = (height - 1) * rowStride + (width - 1) * pixelStride + 4
        require(requiredCapacity <= buffer.capacity())

        val source = buffer.duplicate()
        val pixels = ByteArray(width * height * 4)
        if (pixelStride == 4) {
            val rowBytes = width * 4
            for (y in 0 until height) {
                source.position(y * rowStride)
                source.get(pixels, y * rowBytes, rowBytes)
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
}
