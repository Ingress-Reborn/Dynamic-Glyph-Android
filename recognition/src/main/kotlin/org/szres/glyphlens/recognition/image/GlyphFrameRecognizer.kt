package org.szres.glyphlens.recognition.image

import org.szres.glyphlens.recognition.GlyphCandidate
import org.szres.glyphlens.recognition.GlyphGeometry
import org.szres.glyphlens.recognition.GlyphID
import org.szres.glyphlens.recognition.GlyphPath
import org.szres.glyphlens.recognition.GlyphPoint
import org.szres.glyphlens.recognition.GlyphTable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Spatial contrast samples along an elementary edge, excluding node rings. */
data class GlyphEdgeEvidence(
    val edge: GlyphPath.Edge,
    val samples: List<Double>,
) {
    val coverage: Double
        get() = samples.count { it >= 0.18 } / samples.size.toDouble()

    val isPresent: Boolean
        get() = coverage >= 0.8
}

data class GlyphFrameObservation(
    val state: State,
    val calibration: NodeArrayDetection?,
    val evidence: List<GlyphEdgeEvidence>,
    /** Area estimates from the fixed spatial ink samples, rather than a full-frame pixel census. */
    val strokePixelCount: Int,
    val unexplainedInkPixelCount: Int,
    /** Ordered by edge difference then name; these are not confidence scores. */
    val candidates: List<GlyphCandidate>,
    /** Templates that strictly contain the detected edges. */
    val extensions: List<GlyphID>,
    /** Lit sample indices in the fixed calibration plan, also used to accumulate fading trails. */
    internal val inkSamples: List<Int>,
) {
    enum class State {
        CALIBRATION_UNAVAILABLE,
        CALIBRATION_MISMATCH,
        NO_STROKE,
        UNRECOGNIZED_STROKE,
        UNEXPLAINED_INK,
        AMBIGUOUS_SHAPE,
        UNIQUE_VISIBLE_SHAPE,
    }

    val edges: Set<GlyphPath.Edge>
        get() = evidence.filter { it.isPresent }.mapTo(linkedSetOf()) { it.edge }

    val exactMatches: List<GlyphID>
        get() = candidates.filter { it.distance == 0 }.map { it.id }

    /** Only a presentation boundary can establish that this drawing is complete. */
    val hasUniqueVisibleShape: Boolean
        get() = state == State.UNIQUE_VISIBLE_SHAPE
}

/** Analyzes an upright frame; the caller owns game-phase and Glyph boundaries. */
class GlyphFrameRecognizer(
    table: GlyphTable,
    val maxEdgeDistance: Int = 2,
) {
    private data class Template(val id: GlyphID, val edges: Set<GlyphPath.Edge>)

    private val templates: List<Template>
    private var samplingPlan: SamplingPlan? = null

    /** Actual image reads in the most recent observation; constructing a plan reads no pixels. */
    var lastSampleCount: Int = 0
        private set

    internal var samplingPlanBuildCount: Int = 0
        private set

    init {
        require(maxEdgeDistance >= 0) { "Maximum edge distance cannot be negative" }
        templates = table.glyphs.mapNotNull { id ->
            table.path(forID = id)?.let { path ->
                Template(id, GlyphGeometry.normalizedEdges(path.undirectedEdges))
            }
        }
    }

    /** The exact normalized template used for pixel recognition. */
    fun expectedEdges(forGlyph: GlyphID): Set<GlyphPath.Edge>? =
        templates.firstOrNull { it.id == forGlyph }?.edges

    /** Bootstrap/diagnostic operation; live recognition should reuse an empty-array fit. */
    fun calibrateAndObserve(image: LuminanceImage): GlyphFrameObservation {
        val fit = NodeArrayDetector().detect(image)
            ?: return unavailable(GlyphFrameObservation.State.CALIBRATION_UNAVAILABLE)
        return observe(image, fit)
    }

    /** Observe with a fixed calibration acquired on an empty array. */
    fun observe(image: LuminanceImage, calibration: NodeArrayDetection): GlyphFrameObservation {
        lastSampleCount = 0
        if (calibration.imageWidth != image.width || calibration.imageHeight != image.height) {
            return unavailable(GlyphFrameObservation.State.CALIBRATION_MISMATCH)
        }
        val plan = planFor(calibration)
        val evidence = GlyphGeometry.elementaryEdges.mapIndexed { edgeIndex, edge ->
            val samples = (0 until SAMPLES_PER_EDGE).map { sample ->
                val offset = (edgeIndex * SAMPLES_PER_EDGE + sample) * PIXELS_PER_SAMPLE
                val center = max(
                    read(image, plan.edgePixels[offset]),
                    max(read(image, plan.edgePixels[offset + 1]), read(image, plan.edgePixels[offset + 2])),
                )
                val background = (read(image, plan.edgePixels[offset + 3]) +
                    read(image, plan.edgePixels[offset + 4])) / 2.0
                if (center < 60.0) 0.0
                else min(1.0, max(0.0, (center - background) / 128.0))
            }
            GlyphEdgeEvidence(edge, samples)
        }
        val inkSamples = plan.inkPixels.indices.filter { read(image, plan.inkPixels[it]) >= 80 }
        return classify(evidence, calibration, inkSamples)
    }

    internal fun classify(
        evidence: List<GlyphEdgeEvidence>,
        calibration: NodeArrayDetection,
        inkSamples: List<Int>,
    ): GlyphFrameObservation {
        val edges = evidence.filter { it.isPresent }.mapTo(linkedSetOf()) { it.edge }
        val candidates = if (edges.isEmpty()) emptyList() else templates.map { template ->
            GlyphCandidate(
                id = template.id,
                distance = (edges - template.edges).size + (template.edges - edges).size,
            )
        }.filter { it.distance <= maxEdgeDistance }.sorted()
        val exactCount = candidates.count { it.distance == 0 }
        val extensions = if (edges.isEmpty()) emptyList() else templates.filter { template ->
            edges.size < template.edges.size && template.edges.containsAll(edges)
        }.map { it.id }

        val plan = planFor(calibration)
        var edgeMask = 0L
        evidence.forEachIndexed { index, sample -> if (sample.isPresent) edgeMask = edgeMask or (1L shl index) }
        val unexplainedSamples = inkSamples.count { plan.inkEdges[it] and edgeMask == 0L }
        val inkArea = inkSamples.size * plan.inkSampleArea
        val unexplainedArea = unexplainedSamples * plan.inkSampleArea
        // One bright probe may be a single noise pixel, not an entire illuminated sample cell.
        val excessiveInk = unexplainedSamples >= 2 && unexplainedArea > max(24, (inkArea * 0.35).toInt())
        val state = when {
            exactCount > 0 && excessiveInk -> GlyphFrameObservation.State.UNEXPLAINED_INK
            exactCount == 1 -> GlyphFrameObservation.State.UNIQUE_VISIBLE_SHAPE
            exactCount > 1 -> GlyphFrameObservation.State.AMBIGUOUS_SHAPE
            edges.isEmpty() && inkArea < max(6, (calibration.size.x * 0.025).toInt()) ->
                GlyphFrameObservation.State.NO_STROKE
            else -> GlyphFrameObservation.State.UNRECOGNIZED_STROKE
        }
        return GlyphFrameObservation(
            state = state,
            calibration = calibration,
            evidence = evidence,
            strokePixelCount = inkArea,
            unexplainedInkPixelCount = unexplainedArea,
            candidates = candidates,
            extensions = extensions,
            inkSamples = inkSamples,
        )
    }

    private fun planFor(calibration: NodeArrayDetection): SamplingPlan {
        val current = samplingPlan
        if (current != null && current.calibration == calibration) return current
        return SamplingPlan(calibration).also {
            samplingPlan = it
            samplingPlanBuildCount++
        }
    }

    private fun read(image: LuminanceImage, index: Int): Int {
        if (index < 0) return 0
        lastSampleCount++
        return image[index]
    }

    private class SamplingPlan(val calibration: NodeArrayDetection) {
        val edgePixels = IntArray(GlyphGeometry.elementaryEdges.size * SAMPLES_PER_EDGE * PIXELS_PER_SAMPLE)
        val inkPixels: IntArray
        val inkEdges: LongArray
        val inkSampleArea: Int

        init {
            val positions = calibration.positions
            val halfWidth = max(1.0, calibration.size.x * 0.006)
            val edges = GlyphGeometry.elementaryEdges
            for ((edgeIndex, edge) in edges.withIndex()) {
                val from = positions[edge.lower.rawValue]
                val to = positions[edge.upper.rawValue]
                val delta = to - from
                val length = sqrt(delta.x * delta.x + delta.y * delta.y)
                val direction = delta / length
                val normal = GlyphPoint(-direction.y, direction.x)
                val trim = min(length * 0.3, calibration.nodeRadius * 1.9)
                val backgroundOffset = halfWidth * 4.0 + calibration.nodeRadius * 0.2
                for (sample in 0 until SAMPLES_PER_EDGE) {
                    val distance = trim + (length - 2.0 * trim) * (sample + 0.5) / SAMPLES_PER_EDGE
                    val point = from + scaled(direction, distance)
                    val offset = (edgeIndex * SAMPLES_PER_EDGE + sample) * PIXELS_PER_SAMPLE
                    edgePixels[offset] = pixelIndex(point - scaled(normal, halfWidth))
                    edgePixels[offset + 1] = pixelIndex(point)
                    edgePixels[offset + 2] = pixelIndex(point + scaled(normal, halfWidth))
                    edgePixels[offset + 3] = pixelIndex(point + scaled(normal, backgroundOffset))
                    edgePixels[offset + 4] = pixelIndex(point - scaled(normal, backgroundOffset))
                }
            }

            // Keep the guard mesh at the stroke-width scale, independent of display resolution.
            val step = max(2, (halfWidth * 2).toInt())
            inkSampleArea = step * step
            val points = mutableListOf<Int>()
            val masks = mutableListOf<Long>()
            val maskRadiusSquared = calibration.nodeRadius * calibration.nodeRadius * 3.61
            val hull = convexHull(positions)
            val inkRadius = max(2.0, calibration.size.x * 0.015)
            val minX = max(0, calibration.origin.x.toInt())
            val maxX = min(calibration.imageWidth - 1, (calibration.origin.x + calibration.size.x).toInt())
            val minY = max(0, calibration.origin.y.toInt())
            val maxY = min(calibration.imageHeight - 1, (calibration.origin.y + calibration.size.y).toInt())
            for (y in minY + step / 2..maxY step step) {
                for (x in minX + step / 2..maxX step step) {
                    val pointX = x.toDouble()
                    val pointY = y.toDouble()
                    if (!isWithin(inkRadius, hull, pointX, pointY)) continue
                    if (positions.any { position ->
                            val dx = pointX - position.x
                            val dy = pointY - position.y
                            dx * dx + dy * dy <= maskRadiusSquared
                        }
                    ) continue
                    var coveredBy = 0L
                    for ((edgeIndex, edge) in edges.withIndex()) {
                        val a = positions[edge.lower.rawValue]
                        val b = positions[edge.upper.rawValue]
                        val dx = b.x - a.x
                        val dy = b.y - a.y
                        val offsetX = pointX - a.x
                        val offsetY = pointY - a.y
                        val lengthSquared = dx * dx + dy * dy
                        val t = min(1.0, max(0.0, (offsetX * dx + offsetY * dy) / lengthSquared))
                        val distanceX = pointX - (a.x + dx * t)
                        val distanceY = pointY - (a.y + dy * t)
                        if (distanceX * distanceX + distanceY * distanceY <= inkRadius * inkRadius) {
                            coveredBy = coveredBy or (1L shl edgeIndex)
                        }
                    }
                    points.add(y * calibration.imageWidth + x)
                    masks.add(coveredBy)
                }
            }
            inkPixels = points.toIntArray()
            inkEdges = masks.toLongArray()
        }

        private fun pixelIndex(point: GlyphPoint): Int {
            val x = LuminanceImage.roundedAwayFromZero(point.x).toInt()
            val y = LuminanceImage.roundedAwayFromZero(point.y).toInt()
            if (x < 0 || y < 0 || x >= calibration.imageWidth || y >= calibration.imageHeight) return -1
            return y * calibration.imageWidth + x
        }
    }

    private fun unavailable(state: GlyphFrameObservation.State): GlyphFrameObservation =
        GlyphFrameObservation(state, null, emptyList(), 0, 0, emptyList(), emptyList(), emptyList())

    companion object {
        private const val SAMPLES_PER_EDGE = 24
        private const val PIXELS_PER_SAMPLE = 5
        /** Monotone chain, with the interior on the non-negative side of every edge. */
        internal fun convexHull(points: List<GlyphPoint>): List<GlyphPoint> {
            val sorted = points.sortedWith { a, b ->
                if (a.x != b.x) a.x.compareTo(b.x) else a.y.compareTo(b.y)
            }
            if (sorted.size <= 2) return sorted

            fun cross(o: GlyphPoint, a: GlyphPoint, b: GlyphPoint): Double =
                (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

            val lower = mutableListOf<GlyphPoint>()
            val upper = mutableListOf<GlyphPoint>()
            for (point in sorted) {
                while (lower.size >= 2 &&
                    cross(lower[lower.size - 2], lower[lower.size - 1], point) <= 0.0
                ) lower.removeAt(lower.lastIndex)
                lower.add(point)
            }
            for (point in sorted.asReversed()) {
                while (upper.size >= 2 &&
                    cross(upper[upper.size - 2], upper[upper.size - 1], point) <= 0.0
                ) upper.removeAt(upper.lastIndex)
                upper.add(point)
            }
            return lower.dropLast(1) + upper.dropLast(1)
        }

        /** Inside the convex polygon, or no further than [margin] outside it. */
        internal fun isWithin(margin: Double, hull: List<GlyphPoint>, pointX: Double, pointY: Double): Boolean {
            if (hull.size <= 2) return true
            var inside = true
            var nearest = Double.POSITIVE_INFINITY
            for (index in hull.indices) {
                val a = hull[index]
                val b = hull[(index + 1) % hull.size]
                val edgeX = b.x - a.x
                val edgeY = b.y - a.y
                val offsetX = pointX - a.x
                val offsetY = pointY - a.y
                if (edgeX * offsetY - edgeY * offsetX < 0.0) inside = false
                val lengthSquared = edgeX * edgeX + edgeY * edgeY
                val t = min(1.0, max(0.0, (offsetX * edgeX + offsetY * edgeY) / lengthSquared))
                val distanceX = pointX - (a.x + edgeX * t)
                val distanceY = pointY - (a.y + edgeY * t)
                nearest = min(nearest, distanceX * distanceX + distanceY * distanceY)
            }
            return inside || nearest <= margin * margin
        }

    }
}

private fun scaled(point: GlyphPoint, scale: Double): GlyphPoint =
    GlyphPoint(point.x * scale, point.y * scale)
