package org.szres.glyphlens.recognition.image

import org.szres.glyphlens.recognition.GlyphBoundingBox
import org.szres.glyphlens.recognition.GlyphPoint
import org.szres.glyphlens.recognition.NodeArray
import org.szres.glyphlens.recognition.NodeIndex
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
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
    /** Re-fits the eleven nearby rings so a small movement cannot keep old sample coordinates alive. */
    fun revalidate(image: LuminanceImage, calibration: NodeArrayDetection): NodeArrayDetection? {
        val tolerance = max(2.0, calibration.size.y * 0.012)
        val radius = calibration.nodeRadius + tolerance + 1.0
        val rings = calibration.positions.map { position ->
            val nearby = components(
                image,
                left = max(0, floor(position.x - radius).toInt()),
                top = max(0, floor(position.y - radius).toInt()),
                right = min(image.width, ceil(position.x + radius).toInt() + 1),
                bottom = min(image.height, ceil(position.y + radius).toInt() + 1),
            )
            nearby.filter { ring ->
                val delta = ring.center - position
                delta.x * delta.x + delta.y * delta.y < tolerance * tolerance
            }.minByOrNull { ring ->
                val delta = ring.center - position
                delta.x * delta.x + delta.y * delta.y
            } ?: return null
        }
        val matches = rings.indices.map { Match(it, it) }
        val geometry = fittedGeometry(rings, matches)
        val result = detection(image, rings, matches, geometry.origin, geometry.size)
        return if (result == calibration) calibration else result
    }

    fun detect(image: LuminanceImage): NodeArrayDetection? {
        val rings = components(image)
        if (rings.size < 8 || rings.size > 128) return null
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

                    val geometry = fittedGeometry(rings, matches)
                    origin = geometry.origin
                    size = geometry.size
                    matches = match(rings, origin, size, tolerance)
                    if (matches.size < 8 || origin.x < 0.0 || origin.y < 0.0 ||
                        origin.x + size.x >= image.width || origin.y + size.y >= image.height
                    ) continue
                    val fit = detection(image, rings, matches, origin, size)
                    val current = best
                    if (current == null || fit.matchedNodes.size > current.matchedNodes.size ||
                        (fit.matchedNodes.size == current.matchedNodes.size &&
                            fit.rootMeanSquareError < current.rootMeanSquareError)
                    ) best = fit
                }
            }
        }
        return best
    }

    private data class Ring(val center: GlyphPoint, val radius: Double)
    private data class Match(val node: Int, val ring: Int)
    private data class Geometry(val origin: GlyphPoint, val size: GlyphPoint)

    private fun fittedGeometry(rings: List<Ring>, matches: List<Match>): Geometry {
        val canonical = NodeArray.canonical.positions
        var origin = GlyphPoint(0.0, 0.0)
        var size = GlyphPoint(0.0, 0.0)
        // Least squares removes centroid noise from the individual rings.
        for (axis in 0..1) {
            val xs = matches.map { component(canonical[it.node], axis) }
            val ys = matches.map { component(rings[it.ring].center, axis) }
            val mx = xs.average()
            val my = ys.average()
            val variance = xs.sumOf { (it - mx) * (it - mx) }
            val covariance = xs.indices.sumOf { index -> (xs[index] - mx) * (ys[index] - my) }
            val newSize = covariance / variance
            size = withComponent(size, axis, newSize)
            origin = withComponent(origin, axis, my - newSize * mx)
        }
        return Geometry(origin, size)
    }

    private fun detection(
        image: LuminanceImage,
        rings: List<Ring>,
        matches: List<Match>,
        origin: GlyphPoint,
        size: GlyphPoint,
    ): NodeArrayDetection {
        val canonical = NodeArray.canonical.positions
        val error = sqrt(matches.sumOf { match ->
            val target = origin + canonical[match.node] * size
            val delta = rings[match.ring].center - target
            delta.x * delta.x + delta.y * delta.y
        } / matches.size)
        val radii = matches.map { rings[it.ring].radius }.sorted()
        return NodeArrayDetection(
            imageWidth = image.width,
            imageHeight = image.height,
            origin = origin,
            size = size,
            nodeRadius = radii[radii.size / 2],
            matchedNodes = matches.mapTo(linkedSetOf()) { NodeIndex.all[it.node] },
            rootMeanSquareError = error,
        )
    }

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

    private fun components(
        image: LuminanceImage,
        left: Int = 0,
        top: Int = 0,
        right: Int = image.width,
        bottom: Int = image.height,
    ): List<Ring> {
        val width = image.width
        val height = image.height
        val scale = min(width, height).toDouble()
        val regionWidth = right - left
        val regionHeight = bottom - top
        val isLocal = regionWidth != width || regionHeight != height
        val minimumDiameter = max(5.0, scale * 0.015)
        // A ring is at least this many rows tall: its diameter averages a box whose width is at
        // most 1.33 times its height. Seeding every such row therefore meets every ring, and the
        // shorter components it can miss are rejected by the size test below anyway.
        val seedStep = max(1, (minimumDiameter / 1.165).toInt())
        val cells = regionWidth * regionHeight
        if (visited.size < cells) visited = BooleanArray(cells) else visited.fill(false, 0, cells)
        val visited = visited
        var stack = stack
        var firsts = IntArray(16)
        val rings = ArrayList<Ring>()
        var seedRow = seedStep - 1
        while (seedRow < regionHeight) {
            val rowStart = seedRow * regionWidth
            val imageRow = (seedRow + top) * width + left
            for (column in 0 until regionWidth) {
                val start = rowStart + column
                if (visited[start]) continue
                visited[start] = true
                if (image[imageRow + column] < 160) continue
                var depth = 1
                stack[0] = start
                var count = 0
                var sx = 0.0
                var sy = 0.0
                var minX = width
                var minY = height
                var maxX = 0
                var maxY = 0
                var first = start
                // Each popped seed claims its whole unvisited bright run, then seeds the runs that
                // touch it in the rows above and below; the pixels of one 8-connected component are
                // each counted once, exactly as a pixel-by-pixel fill would count them.
                while (depth > 0) {
                    val index = stack[--depth]
                    val row = index / regionWidth
                    val rowBase = row * regionWidth
                    val imageBase = (row + top) * width + left
                    var runLeft = index - rowBase
                    var runRight = runLeft
                    while (runLeft > 0) {
                        val next = rowBase + runLeft - 1
                        if (visited[next]) break
                        visited[next] = true
                        if (image[imageBase + runLeft - 1] < 160) break
                        runLeft--
                    }
                    while (runRight < regionWidth - 1) {
                        val next = rowBase + runRight + 1
                        if (visited[next]) break
                        visited[next] = true
                        if (image[imageBase + runRight + 1] < 160) break
                        runRight++
                    }
                    val length = runRight - runLeft + 1
                    if (rowBase + runLeft < first) first = rowBase + runLeft
                    count += length
                    sx += (left.toLong() * length + (runLeft + runRight).toLong() * length / 2).toDouble()
                    sy += ((row + top).toLong() * length).toDouble()
                    minX = min(minX, runLeft + left)
                    maxX = max(maxX, runRight + left)
                    minY = min(minY, row + top)
                    maxY = max(maxY, row + top)
                    val from = max(0, runLeft - 1)
                    val to = min(regionWidth - 1, runRight + 1)
                    var neighbour = row - 1
                    while (neighbour <= row + 1) {
                        if (neighbour in 0 until regionHeight) {
                            val base = neighbour * regionWidth
                            val pixels = (neighbour + top) * width + left
                            var inRun = false
                            for (x in from..to) {
                                val next = base + x
                                if (visited[next]) {
                                    inRun = false
                                } else if (image[pixels + x] < 160) {
                                    visited[next] = true
                                    inRun = false
                                } else if (!inRun) {
                                    // The rest of this run stays unvisited for its seed to claim.
                                    visited[next] = true
                                    if (depth == stack.size) stack = stack.copyOf(depth * 2)
                                    stack[depth++] = next
                                    inRun = true
                                }
                            }
                        }
                        neighbour += 2
                    }
                }
                // A clipped fragment can look annular; local verification requires the complete ring.
                if (isLocal && (minX == left || minY == top || maxX == right - 1 || maxY == bottom - 1)) continue
                val boxWidth = (maxX - minX + 1).toDouble()
                val boxHeight = (maxY - minY + 1).toDouble()
                val diameter = (boxWidth + boxHeight) / 2.0
                val fill = count / (boxWidth * boxHeight)
                if (diameter < minimumDiameter || diameter > scale * 0.07 ||
                    boxWidth / boxHeight < 0.75 || boxWidth / boxHeight > 1.33 ||
                    fill < 0.15 || fill > 0.65
                ) continue
                val center = GlyphPoint(sx / count, sy / count)
                if (image.sample(center) >= 160.0) continue // A ring has a hole.
                if (rings.size == firsts.size) firsts = firsts.copyOf(firsts.size * 2)
                firsts[rings.size] = first
                rings.add(Ring(center, diameter / 2.0))
            }
            seedRow += seedStep
        }
        this.stack = stack
        // Report rings in raster order of their first pixel, as a scan of every row would.
        return rings.indices.sortedBy { firsts[it] }.map { rings[it] }
    }

    private var visited = BooleanArray(0)
    private var stack = IntArray(256)

    private fun component(point: GlyphPoint, axis: Int): Double = if (axis == 0) point.x else point.y

    private fun withComponent(point: GlyphPoint, axis: Int, value: Double): GlyphPoint =
        if (axis == 0) GlyphPoint(value, point.y) else GlyphPoint(point.x, value)
}
