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
    val strokePixelCount: Int,
    val unexplainedInkPixelCount: Int,
    /** Ordered by edge difference then name; these are not confidence scores. */
    val candidates: List<GlyphCandidate>,
    /** Templates that strictly contain the detected edges. */
    val extensions: List<GlyphID>,
    /** Retained for spatial accounting when a fading trail is accumulated. */
    internal val inkPixels: List<Int>,
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
        if (calibration.imageWidth != image.width || calibration.imageHeight != image.height) {
            return unavailable(GlyphFrameObservation.State.CALIBRATION_MISMATCH)
        }
        val positions = calibration.positions
        val halfWidth = max(1.0, calibration.size.x * 0.006)
        val evidence = GlyphGeometry.elementaryEdges.map { edge ->
            val from = positions[edge.lower.rawValue]
            val to = positions[edge.upper.rawValue]
            val delta = to - from
            val length = sqrt(delta.x * delta.x + delta.y * delta.y)
            val direction = delta / length
            val normal = GlyphPoint(-direction.y, direction.x)
            val trim = min(length * 0.3, calibration.nodeRadius * 1.9)
            val samples = (0 until 24).map { index ->
                val distance = trim + (length - 2.0 * trim) * (index + 0.5) / 24.0
                val point = from + scaled(direction, distance)
                val center = listOf(-1.0, 0.0, 1.0).maxOf { offset ->
                    image.sample(point + scaled(normal, offset * halfWidth))
                }
                val offset = halfWidth * 4.0 + calibration.nodeRadius * 0.2
                val background = (
                    image.sample(point + scaled(normal, offset)) +
                        image.sample(point - scaled(normal, offset))
                    ) / 2.0
                if (center < 60.0) 0.0
                else min(1.0, max(0.0, (center - background) / 128.0))
            }
            GlyphEdgeEvidence(edge, samples)
        }

        // Exclude node rings/glow and bounding-box corners outside the outer hexagon.
        val inkPixels = mutableListOf<Int>()
        val maskRadiusSquared = calibration.nodeRadius * calibration.nodeRadius * 3.61
        val hull = convexHull(positions)
        val glowMargin = max(2.0, calibration.size.x * 0.015)
        val minX = max(0, calibration.origin.x.toInt())
        val maxX = min(image.width - 1, (calibration.origin.x + calibration.size.x).toInt())
        val minY = max(0, calibration.origin.y.toInt())
        val maxY = min(image.height - 1, (calibration.origin.y + calibration.size.y).toInt())
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                if (image[x, y] < 80) continue
                val pointX = x.toDouble()
                val pointY = y.toDouble()
                if (!isWithin(glowMargin, hull, pointX, pointY)) continue
                val masked = positions.any { position ->
                    val dx = pointX - position.x
                    val dy = pointY - position.y
                    dx * dx + dy * dy <= maskRadiusSquared
                }
                if (!masked) inkPixels.add(y * image.width + x)
            }
        }
        return classify(evidence, calibration, inkPixels)
    }

    internal fun classify(
        evidence: List<GlyphEdgeEvidence>,
        calibration: NodeArrayDetection,
        inkPixels: List<Int>,
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

        val positions = calibration.positions
        val segments = edges.map { positions[it.lower.rawValue] to positions[it.upper.rawValue] }
        val inkRadius = max(2.0, calibration.size.x * 0.015)
        val unexplained = inkPixels.count { index ->
            val pointX = (index % calibration.imageWidth).toDouble()
            val pointY = (index / calibration.imageWidth).toDouble()
            segments.none { (a, b) ->
                val dx = b.x - a.x
                val dy = b.y - a.y
                val offsetX = pointX - a.x
                val offsetY = pointY - a.y
                val lengthSquared = dx * dx + dy * dy
                val t = min(1.0, max(0.0, (offsetX * dx + offsetY * dy) / lengthSquared))
                val distanceX = pointX - (a.x + dx * t)
                val distanceY = pointY - (a.y + dy * t)
                distanceX * distanceX + distanceY * distanceY <= inkRadius * inkRadius
            }
        }
        val excessiveInk = unexplained > max(24, (inkPixels.size * 0.35).toInt())
        val state = when {
            exactCount > 0 && excessiveInk -> GlyphFrameObservation.State.UNEXPLAINED_INK
            exactCount == 1 -> GlyphFrameObservation.State.UNIQUE_VISIBLE_SHAPE
            exactCount > 1 -> GlyphFrameObservation.State.AMBIGUOUS_SHAPE
            edges.isEmpty() && inkPixels.size < max(6, (calibration.size.x * 0.025).toInt()) ->
                GlyphFrameObservation.State.NO_STROKE
            else -> GlyphFrameObservation.State.UNRECOGNIZED_STROKE
        }
        return GlyphFrameObservation(
            state = state,
            calibration = calibration,
            evidence = evidence,
            strokePixelCount = inkPixels.size,
            unexplainedInkPixelCount = unexplained,
            candidates = candidates,
            extensions = extensions,
            inkPixels = inkPixels,
        )
    }

    private fun unavailable(state: GlyphFrameObservation.State): GlyphFrameObservation =
        GlyphFrameObservation(state, null, emptyList(), 0, 0, emptyList(), emptyList(), emptyList())

    companion object {
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
