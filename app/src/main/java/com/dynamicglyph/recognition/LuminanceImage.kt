package com.dynamicglyph.recognition

/**
 * 8-bit greyscale image, flat pixel array, top-left origin.
 * Ported from Swift LuminanceImage.swift.
 */
class LuminanceImage(
    val width: Int,
    val height: Int,
    val pixels: ByteArray
) {
    init {
        require(pixels.size == width * height) {
            "Pixel count mismatch: expected ${width * height}, got ${pixels.size}"
        }
    }

    /** Nearest-neighbour sample; out-of-bounds returns 0. */
    fun sample(point: Vec2): Int {
        val x = kotlin.math.round(point.x).toInt()
        val y = kotlin.math.round(point.y).toInt()
        if (x < 0 || x >= width || y < 0 || y >= height) return 0
        return pixels[y * width + x].toInt() and 0xFF
    }

    /** Direct pixel access. */
    fun pixel(x: Int, y: Int): Int {
        if (x < 0 || x >= width || y < 0 || y >= height) return 0
        return pixels[y * width + x].toInt() and 0xFF
    }

    /** Set a pixel. */
    fun setPixel(x: Int, y: Int, value: Int) {
        if (x < 0 || x >= width || y < 0 || y >= height) return
        pixels[y * width + x] = value.toByte()
    }
}
