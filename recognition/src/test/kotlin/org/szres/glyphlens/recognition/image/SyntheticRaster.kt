package org.szres.glyphlens.recognition.image

import org.szres.glyphlens.recognition.GlyphPoint
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Independent software renderer: pixels come from distance to hand-authored line
 * segments, never from recognizer sample positions or normalized template edges.
 */
internal class SyntheticRaster(
    val scale: Double = 1.0,
    val offset: GlyphPoint = GlyphPoint(0.0, 0.0),
) {
    val width: Int = (320 * scale).toInt()
    val height: Int = (520 * scale).toInt()
    val pixels: ByteArray = ByteArray(width * height) { 8 }

    val nodes: List<GlyphPoint>
        get() = listOf(
            160.0 to 130.0, 40.0 to 202.0, 280.0 to 202.0,
            100.0 to 238.0, 220.0 to 238.0, 160.0 to 274.0,
            100.0 to 310.0, 220.0 to 310.0, 40.0 to 346.0,
            280.0 to 346.0, 160.0 to 418.0,
        ).map { point(it.first, it.second) }

    init {
        for (node in nodes) drawRing(node)
    }

    fun point(x: Double, y: Double): GlyphPoint =
        GlyphPoint((x + offset.x) * scale, (y + offset.y) * scale)

    fun copy(): SyntheticRaster {
        val result = SyntheticRaster(scale, offset)
        pixels.copyInto(result.pixels)
        return result
    }

    fun image(): LuminanceImage = LuminanceImage(width, height, pixels.copyOf())

    fun line(from: GlyphPoint, to: GlyphPoint, value: Int = 210) {
        val radius = max(1.0, 2.0 * scale)
        val dx = to.x - from.x
        val dy = to.y - from.y
        val lengthSquared = dx * dx + dy * dy
        val minY = max(0, (min(from.y, to.y) - radius).toInt())
        val maxY = min(height - 1, (max(from.y, to.y) + radius).toInt())
        val minX = max(0, (min(from.x, to.x) - radius).toInt())
        val maxX = min(width - 1, (max(from.x, to.x) + radius).toInt())
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val px = x - from.x
                val py = y - from.y
                val t = min(1.0, max(0.0, (px * dx + py * dy) / lengthSquared))
                val distanceX = x - (from.x + t * dx)
                val distanceY = y - (from.y + t * dy)
                if (distanceX * distanceX + distanceY * distanceY <= radius * radius) {
                    val index = y * width + x
                    pixels[index] = max(value, pixels[index].toInt() and 0xff).toByte()
                }
            }
        }
    }

    private fun drawRing(center: GlyphPoint) {
        val minY = max(0, (center.y - 9 * scale).toInt())
        val maxY = min(height - 1, (center.y + 9 * scale).toInt())
        val minX = max(0, (center.x - 9 * scale).toInt())
        val maxX = min(width - 1, (center.x + 9 * scale).toInt())
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val dx = x - center.x
                val dy = y - center.y
                val radius = sqrt(dx * dx + dy * dy) / scale
                if (radius >= 5.5 && radius <= 7.5) pixels[y * width + x] = 230.toByte()
            }
        }
    }
}
