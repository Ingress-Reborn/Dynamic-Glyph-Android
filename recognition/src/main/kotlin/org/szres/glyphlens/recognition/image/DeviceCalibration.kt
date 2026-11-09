package org.szres.glyphlens.recognition.image

import org.szres.glyphlens.recognition.GlyphPoint
import org.szres.glyphlens.recognition.NodeIndex
import kotlin.math.min

/** Saved geometry is a search hint, never evidence of the current game phase. */
@ConsistentCopyVisibility
data class DeviceCalibration private constructor(
    val origin: GlyphPoint,
    val size: GlyphPoint,
    val nodeRadius: Double,
) {
    fun forImage(width: Int, height: Int): NodeArrayDetection = NodeArrayDetection(
        imageWidth = width,
        imageHeight = height,
        origin = GlyphPoint(origin.x * width, origin.y * height),
        size = GlyphPoint(size.x * width, size.y * height),
        nodeRadius = nodeRadius * min(width, height),
        matchedNodes = NodeIndex.all.toSet(),
        rootMeanSquareError = 0.0,
    )

    fun encode(): String = listOf(origin.x, origin.y, size.x, size.y, nodeRadius).joinToString(",")

    companion object {
        fun fromDetection(fit: NodeArrayDetection): DeviceCalibration = DeviceCalibration(
            origin = GlyphPoint(fit.origin.x / fit.imageWidth, fit.origin.y / fit.imageHeight),
            size = GlyphPoint(fit.size.x / fit.imageWidth, fit.size.y / fit.imageHeight),
            nodeRadius = fit.nodeRadius / min(fit.imageWidth, fit.imageHeight),
        )

        fun decode(value: String?): DeviceCalibration? {
            val fields = value?.split(',') ?: return null
            if (fields.size != 5) return null
            val numbers = fields.map { it.toDoubleOrNull()?.takeIf(Double::isFinite) ?: return null }
            val (x, y, width, height, radius) = numbers
            if (x < 0.0 || y < 0.0 || width <= 0.0 || height <= 0.0 ||
                x + width >= 1.0 || y + height >= 1.0 || radius <= 0.0 || radius >= 0.1
            ) return null
            return DeviceCalibration(GlyphPoint(x, y), GlyphPoint(width, height), radius)
        }
    }
}
