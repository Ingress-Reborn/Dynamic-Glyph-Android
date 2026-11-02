package com.dynamicglyph.recognition

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * An axis-aligned fit to the eleven node rings, in source-image pixels.
 */
data class NodeArrayDetection(
    val imageWidth: Int,
    val imageHeight: Int,
    val origin: Vec2,
    val size: Vec2,
    val nodeRadius: Double,
    val matchedNodes: Set<NodeIndex>,
    val rootMeanSquareError: Double
) {
    /** Pixel-space positions of all 11 nodes. */
    val positions: List<Vec2>
        get() = NodeArray.CANONICAL.fittedTo(origin, size)
}

/**
 * Finds bright annular components, then requires agreement with the entire
 * lattice. UI circles and letters alone must not count as an array.
 *
 * Ported from Swift NodeArrayDetector.swift.
 *
 * The initial fit needs visible apex/base rings and at least eight rings in
 * total. Calibrate on the empty presentation screen and reuse that fit while
 * strokes obscure rings. Rotation and perspective are not supported.
 */
class NodeArrayDetector {

    private data class Ring(val center: Vec2, val radius: Double)

    /**
     * Detect the node array in a greyscale image. Returns null if no fit is
     * found — which is not a screen-exit signal.
     */
    fun detect(image: LuminanceImage): NodeArrayDetection? {
        val rings = components(image)
        if (rings.size < 8 || rings.size > 128) return null

        val canonical = NodeArray.CANONICAL.positions
        val shortSide = min(image.width, image.height).toDouble()
        var best: NodeArrayDetection? = null

        for (top in rings) {
            for (bottom in rings) {
                val height = bottom.center.y - top.center.y
                if (height < shortSide * 0.35) continue
                if (abs(top.center.x - bottom.center.x) >= height * 0.015) continue

                val centerX = (top.center.x + bottom.center.x) / 2.0

                for (side in rings) {
                    val relativeY = (side.center.y - top.center.y) / height
                    if (min(abs(relativeY - 0.25), abs(relativeY - 0.75)) >= 0.015) continue

                    val width = 2 * abs(side.center.x - centerX)
                    val aspect = width / height
                    if (aspect < 0.65 || aspect > 1.05) continue

                    var origin = Vec2(centerX - width / 2.0, top.center.y)
                    var size = Vec2(width, height)
                    val tolerance = max(2.0, height * 0.012)

                    var matches = match(rings, origin, size, tolerance)
                    if (matches.size < 8) continue

                    // Least squares removes centroid noise from the three anchors.
                    for (axis in 0..1) {
                        val xs = matches.map { canonical[it.node][axis] }
                        val ys = matches.map { rings[it.ring].center[axis] }
                        val mx = xs.sum() / xs.size
                        val my = ys.sum() / ys.size
                        val variance = xs.sumOf { (it - mx) * (it - mx) }
                        var covariance = 0.0
                        for (i in xs.indices) covariance += (xs[i] - mx) * (ys[i] - my)

                        if (variance < 1e-12) continue
                        val solvedSize = covariance / variance
                        val solvedOrigin = my - solvedSize * mx
                        if (axis == 0) {
                            size = Vec2(solvedSize, size.y)
                            origin = Vec2(solvedOrigin, origin.y)
                        } else {
                            size = Vec2(size.x, solvedSize)
                            origin = Vec2(origin.x, solvedOrigin)
                        }
                    }

                    matches = match(rings, origin, size, tolerance)
                    if (matches.size < 8) continue
                    if (origin.x < 0 || origin.y < 0) continue
                    if (origin.x + size.x >= image.width || origin.y + size.y >= image.height) continue

                    var sumSquared = 0.0
                    for (m in matches) {
                        val delta = rings[m.ring].center - (origin + canonical[m.node] * size)
                        sumSquared += delta.lengthSquared()
                    }
                    val error = sqrt(sumSquared / matches.size)

                    val radii = matches.map { rings[it.ring].radius }.sorted()
                    val fit = NodeArrayDetection(
                        imageWidth = image.width,
                        imageHeight = image.height,
                        origin = origin,
                        size = size,
                        nodeRadius = radii[radii.size / 2],
                        matchedNodes = matches.map { NodeIndex(it.node) }.toSet(),
                        rootMeanSquareError = error
                    )

                    val incumbent = best
                    if (incumbent == null ||
                        fit.matchedNodes.size > incumbent.matchedNodes.size ||
                        (fit.matchedNodes.size == incumbent.matchedNodes.size &&
                            error < incumbent.rootMeanSquareError)
                    ) {
                        best = fit
                    }
                }
            }
        }
        return best
    }

    private data class Match(val node: Int, val ring: Int)

    /**
     * Greedy nearest-ring assignment, nodes in canonical order, each ring used
     * once. The initial distance is the tolerance, so nothing outside it can
     * match.
     */
    private fun match(rings: List<Ring>, origin: Vec2, size: Vec2, tolerance: Double): List<Match> {
        val used = mutableSetOf<Int>()
        val matches = mutableListOf<Match>()
        for ((node, p) in NodeArray.CANONICAL.positions.withIndex()) {
            val target = origin + p * size
            var nearest: Int? = null
            var bestSquared = tolerance * tolerance
            for ((index, ring) in rings.withIndex()) {
                if (index in used) continue
                val squared = (ring.center - target).lengthSquared()
                if (squared < bestSquared) {
                    bestSquared = squared
                    nearest = index
                }
            }
            if (nearest != null) {
                used.add(nearest)
                matches.add(Match(node, nearest))
            }
        }
        return matches
    }

    /**
     * Threshold at 160 and flood fill 8-connected, keeping components that look
     * like a hollow ring of about the right size.
     */
    private fun components(image: LuminanceImage): List<Ring> {
        val w = image.width
        val h = image.height
        val scale = min(w, h).toDouble()
        val visited = BooleanArray(w * h)
        val rings = mutableListOf<Ring>()
        val stack = ArrayDeque<Int>()

        for (start in 0 until w * h) {
            if (visited[start]) continue
            if ((image.pixels[start].toInt() and 0xFF) < THRESHOLD) {
                visited[start] = true
                continue
            }

            stack.clear()
            stack.addLast(start)
            visited[start] = true

            var count = 0
            var sumX = 0.0
            var sumY = 0.0
            var minX = w
            var minY = h
            var maxX = 0
            var maxY = 0

            while (stack.isNotEmpty()) {
                val index = stack.removeLast()
                val x = index % w
                val y = index / w
                count++
                sumX += x
                sumY += y
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y

                for (ny in max(0, y - 1)..min(h - 1, y + 1)) {
                    for (nx in max(0, x - 1)..min(w - 1, x + 1)) {
                        val next = ny * w + nx
                        if (!visited[next] && (image.pixels[next].toInt() and 0xFF) >= THRESHOLD) {
                            visited[next] = true
                            stack.addLast(next)
                        }
                    }
                }
            }

            if (count == 0) continue
            val bw = (maxX - minX + 1).toDouble()
            val bh = (maxY - minY + 1).toDouble()
            val diameter = (bw + bh) / 2.0
            val fill = count.toDouble() / (bw * bh)

            if (diameter < max(5.0, scale * 0.015)) continue
            if (diameter > scale * 0.07) continue
            if (bw / bh < 0.75 || bw / bh > 1.33) continue
            if (fill < 0.15 || fill > 0.65) continue

            val center = Vec2(sumX / count, sumY / count)
            // A ring has a hole. This is what separates node rings from other
            // bright UI.
            if (image.sample(center) >= THRESHOLD) continue

            rings.add(Ring(center, diameter / 2.0))
        }
        return rings
    }

    companion object {
        private const val THRESHOLD = 160
    }
}
