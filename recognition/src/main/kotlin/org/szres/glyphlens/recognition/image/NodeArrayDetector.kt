package org.szres.glyphlens.recognition.image

import org.szres.glyphlens.recognition.GlyphBoundingBox
import org.szres.glyphlens.recognition.GlyphPoint
import org.szres.glyphlens.recognition.NodeArray
import org.szres.glyphlens.recognition.NodeIndex
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** An axis-aligned fit to the eleven node rings, in source-image pixels. */
data class NodeArrayDetection(
    val imageWidth: Int,
    val imageHeight: Int,
    val origin: GlyphPoint,
    val size: GlyphPoint,
    val nodeRadius: Double,
    val matchedNodes: Set<NodeIndex>,
    val rootMeanSquareError: Double,
) {
    val positions: List<GlyphPoint>
        get() = NodeArray.canonical.fitted(GlyphBoundingBox(origin, size))
}

/** Finds bright annular components, then requires agreement with the entire lattice. */
class NodeArrayDetector {
    fun detect(image: LuminanceImage): NodeArrayDetection? {
        val rings = components(image)
        if (rings.size < 8 || rings.size > 128) return null
        val canonical = NodeArray.canonical.positions
        var best: NodeArrayDetection? = null

        for (top in rings) {
            for (bottom in rings) {
                val height = bottom.center.y - top.center.y
                if (height < min(image.width, image.height) * 0.35 ||
                    abs(top.center.x - bottom.center.x) >= height * 0.015
                ) continue
                val centerX = (top.center.x + bottom.center.x) / 2.0
                for (side in rings) {
                    val relativeY = (side.center.y - top.center.y) / height
                    if (min(abs(relativeY - 0.25), abs(relativeY - 0.75)) >= 0.015) continue
                    val width = 2.0 * abs(side.center.x - centerX)
                    if (width / height < 0.65 || width / height > 1.05) continue
                    var origin = GlyphPoint(centerX - width / 2.0, top.center.y)
                    var size = GlyphPoint(width, height)
                    val tolerance = max(2.0, height * 0.012)
                    var matches = match(rings, origin, size, tolerance)
                    if (matches.size < 8) continue

                    // Least squares removes centroid noise from the three anchors.
                    for (axis in 0..1) {
                        val xs = matches.map { component(canonical[it.node], axis) }
                        val ys = matches.map { component(rings[it.ring].center, axis) }
                        val mx = xs.average()
                        val my = ys.average()
                        val variance = xs.sumOf { (it - mx) * (it - mx) }
                        val covariance = xs.indices.sumOf { index ->
                            (xs[index] - mx) * (ys[index] - my)
                        }
                        val newSize = covariance / variance
                        val newOrigin = my - newSize * mx
                        size = withComponent(size, axis, newSize)
                        origin = withComponent(origin, axis, newOrigin)
                    }
                    matches = match(rings, origin, size, tolerance)
                    if (matches.size < 8 || origin.x < 0.0 || origin.y < 0.0 ||
                        origin.x + size.x >= image.width || origin.y + size.y >= image.height
                    ) continue
                    val error = sqrt(matches.sumOf { match ->
                        val target = origin + canonical[match.node] * size
                        val delta = rings[match.ring].center - target
                        delta.x * delta.x + delta.y * delta.y
                    } / matches.size)
                    val radii = matches.map { rings[it.ring].radius }.sorted()
                    val fit = NodeArrayDetection(
                        imageWidth = image.width,
                        imageHeight = image.height,
                        origin = origin,
                        size = size,
                        nodeRadius = radii[radii.size / 2],
                        matchedNodes = matches.mapTo(linkedSetOf()) { NodeIndex.all[it.node] },
                        rootMeanSquareError = error,
                    )
                    val current = best
                    if (current == null || fit.matchedNodes.size > current.matchedNodes.size ||
                        (fit.matchedNodes.size == current.matchedNodes.size &&
                            error < current.rootMeanSquareError)
                    ) best = fit
                }
            }
        }
        return best
    }

    private data class Ring(val center: GlyphPoint, val radius: Double)
    private data class Match(val node: Int, val ring: Int)

    private fun match(
        rings: List<Ring>,
        origin: GlyphPoint,
        size: GlyphPoint,
        tolerance: Double,
    ): List<Match> {
        val used = mutableSetOf<Int>()
        val matches = mutableListOf<Match>()
        for ((node, position) in NodeArray.canonical.positions.withIndex()) {
            val target = origin + position * size
            var nearest: Int? = null
            var distance = tolerance * tolerance
            for ((index, ring) in rings.withIndex()) {
                if (index in used) continue
                val delta = ring.center - target
                val squared = delta.x * delta.x + delta.y * delta.y
                if (squared < distance) {
                    distance = squared
                    nearest = index
                }
            }
            nearest?.let {
                used.add(it)
                matches.add(Match(node, it))
            }
        }
        return matches
    }

    private fun components(image: LuminanceImage): List<Ring> {
        val width = image.width
        val height = image.height
        val scale = min(width, height).toDouble()
        val visited = BooleanArray(image.pixels.size)
        val stack = ArrayDeque<Int>()
        val rings = mutableListOf<Ring>()
        for (start in image.pixels.indices) {
            if ((image.pixels[start].toInt() and 0xff) < 160 || visited[start]) continue
            stack.addLast(start)
            visited[start] = true
            var count = 0
            var sx = 0.0
            var sy = 0.0
            var minX = width
            var minY = height
            var maxX = 0
            var maxY = 0
            while (stack.isNotEmpty()) {
                val index = stack.removeLast()
                val x = index % width
                val y = index / width
                count++
                sx += x
                sy += y
                minX = min(minX, x)
                maxX = max(maxX, x)
                minY = min(minY, y)
                maxY = max(maxY, y)
                for (ny in max(0, y - 1)..min(height - 1, y + 1)) {
                    for (nx in max(0, x - 1)..min(width - 1, x + 1)) {
                        val next = ny * width + nx
                        if (!visited[next] && (image.pixels[next].toInt() and 0xff) >= 160) {
                            visited[next] = true
                            stack.addLast(next)
                        }
                    }
                }
            }
            val boxWidth = (maxX - minX + 1).toDouble()
            val boxHeight = (maxY - minY + 1).toDouble()
            val diameter = (boxWidth + boxHeight) / 2.0
            val fill = count / (boxWidth * boxHeight)
            if (diameter < max(5.0, scale * 0.015) || diameter > scale * 0.07 ||
                boxWidth / boxHeight < 0.75 || boxWidth / boxHeight > 1.33 ||
                fill < 0.15 || fill > 0.65
            ) continue
            val center = GlyphPoint(sx / count, sy / count)
            if (image.sample(center) >= 160.0) continue // A ring has a hole.
            rings.add(Ring(center, diameter / 2.0))
        }
        return rings
    }

    private fun component(point: GlyphPoint, axis: Int): Double = if (axis == 0) point.x else point.y

    private fun withComponent(point: GlyphPoint, axis: Int, value: Double): GlyphPoint =
        if (axis == 0) GlyphPoint(value, point.y) else GlyphPoint(point.x, value)
}
