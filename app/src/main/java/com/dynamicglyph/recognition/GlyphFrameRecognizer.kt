package com.dynamicglyph.recognition

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Per-frame Glyph shape recognition from a luminance image + calibration.
 *
 * Ported from Swift GlyphFrameRecognizer.swift. The caller owns game phase and
 * Glyph boundaries; player-input strokes must never become observations of the
 * presentation.
 *
 * Algorithm, in the original's order:
 * 1. For each elementary edge, sample 24 spatial bins of centre-vs-background
 *    contrast, trimming the ends so the node rings and their glow do not count
 *    as stroke.
 * 2. An edge is present when at least 80% of its bins carry contrast — 20 of 24.
 *    Four missing bins, including a contiguous gap, are tolerated on purpose:
 *    the test is evidence of ink, not proof that the line is continuous.
 * 3. Match the present-edge set against templates by symmetric difference,
 *    which is direction-independent.
 * 4. Classify, including the "unexplained ink" check that catches stray bright
 *    objects inside the lattice that no edge accounts for.
 */
class GlyphFrameRecognizer(
    table: GlyphTable,
    val maxEdgeDistance: Int = 2
) {

    init {
        require(maxEdgeDistance >= 0) { "maxEdgeDistance must be >= 0" }
    }

    data class EdgeEvidence(
        val edge: GlyphPath.Edge,
        val samples: DoubleArray,
        val coverage: Double,
        val isPresent: Boolean
    )

    enum class State {
        CALIBRATION_UNAVAILABLE,
        CALIBRATION_MISMATCH,
        NO_STROKE,
        UNRECOGNIZED_STROKE,
        UNEXPLAINED_INK,
        AMBIGUOUS_SHAPE,
        UNIQUE_VISIBLE_SHAPE
    }

    data class Observation(
        val state: State,
        val calibration: NodeArrayDetection?,
        val evidence: List<EdgeEvidence>,
        val strokePixelCount: Int,
        val unexplainedInkPixelCount: Int,
        val candidates: List<GlyphCandidate>,
        val extensions: List<GlyphID>,
        val inkPixels: List<Int>,
        val edges: Set<GlyphPath.Edge>
    ) {
        /** Templates matching the visible edges exactly. */
        val exactMatches: List<GlyphID>
            get() = candidates.filter { it.distance == 0 }.map { it.id }

        /**
         * A visible shape may still be the beginning of a longer Glyph. Only a
         * presentation boundary can establish that the drawing is complete.
         */
        val hasUniqueVisibleShape: Boolean
            get() = state == State.UNIQUE_VISIBLE_SHAPE
    }

    /** The exact normalised template used for pixel recognition. */
    private val templates: List<Pair<GlyphID, Set<GlyphPath.Edge>>> =
        table.glyphs.mapNotNull { id ->
            val path = table.pathFor(id) ?: return@mapNotNull null
            id to GlyphGeometry.normalizedEdges(path.undirectedEdges)
        }

    /** The normalised edge set a Glyph is expected to produce. */
    fun expectedEdgesFor(glyph: GlyphID): Set<GlyphPath.Edge>? =
        templates.firstOrNull { it.first == glyph }?.second

    /**
     * Observe a frame with a pre-acquired calibration.
     *
     * The fit is required: a drawn-over array usually cannot be calibrated.
     * Invalidate it after rotation, resizing or a layout change.
     */
    fun observe(image: LuminanceImage, calibration: NodeArrayDetection): Observation {
        if (calibration.imageWidth != image.width || calibration.imageHeight != image.height) {
            return unavailable(State.CALIBRATION_MISMATCH)
        }

        val positions = calibration.positions
        val halfWidth = max(1.0, calibration.size.x * 0.006)

        val evidence = GlyphGeometry.elementaryEdges.map { edge ->
            sampleEdge(image, positions, edge, calibration.nodeRadius, halfWidth)
        }

        // Distinguish an empty array from ink that establishes no complete
        // lattice edge. Exclude the bright node rings and their glow, and the
        // corners of the bounding box outside the hexagon of outer nodes: no
        // stroke can be drawn there, but a floating button parked at the screen
        // edge (AssistiveTouch) reaches into them.
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
                if (image.pixel(x, y) < 80) continue
                val point = Vec2(x.toDouble(), y.toDouble())
                if (!isWithin(glowMargin, hull, point)) continue
                val inRing = positions.any { p ->
                    val d = point - p
                    d.lengthSquared() <= maskRadiusSquared
                }
                if (!inRing) inkPixels.add(y * image.width + x)
            }
        }

        return classify(evidence, calibration, inkPixels)
    }

    /**
     * Bootstrap/diagnostic operation. Strokes often obscure enough rings to make
     * this fail on a valid Glyph screen; failure is NOT a screen-exit signal.
     * Live recognition should use [observe] with a fit acquired on an empty
     * array.
     */
    fun calibrateAndObserve(image: LuminanceImage): Observation {
        val fit = NodeArrayDetector().detect(image) ?: return unavailable(State.CALIBRATION_UNAVAILABLE)
        return observe(image, fit)
    }

    private fun unavailable(state: State) = Observation(
        state = state,
        calibration = null,
        evidence = emptyList(),
        strokePixelCount = 0,
        unexplainedInkPixelCount = 0,
        candidates = emptyList(),
        extensions = emptyList(),
        inkPixels = emptyList(),
        edges = emptySet()
    )

    /**
     * Sample contrast along one elementary edge in 24 bins.
     */
    private fun sampleEdge(
        image: LuminanceImage,
        positions: List<Vec2>,
        edge: GlyphPath.Edge,
        nodeRadius: Double,
        halfWidth: Double
    ): EdgeEvidence {
        val from = positions[edge.lower.rawValue]
        val to = positions[edge.upper.rawValue]
        val delta = to - from
        val length = delta.length()

        if (length < 1e-9) {
            return EdgeEvidence(edge, DoubleArray(BIN_COUNT), 0.0, false)
        }

        val direction = delta * (1.0 / length)
        val normal = Vec2(-direction.y, direction.x)
        val trim = min(length * 0.3, nodeRadius * 1.9)
        val offset = halfWidth * 4 + nodeRadius * 0.2

        val samples = DoubleArray(BIN_COUNT)
        for (index in 0 until BIN_COUNT) {
            val distance = trim + (length - 2 * trim) * (index + 0.5) / BIN_COUNT
            val point = from + direction * distance

            val center = maxOf(
                image.sample(point + normal * halfWidth).toDouble(),
                image.sample(point).toDouble(),
                image.sample(point - normal * halfWidth).toDouble()
            )
            val background = (
                image.sample(point + normal * offset) +
                    image.sample(point - normal * offset)
                ) / 2.0

            samples[index] = if (center < 60) {
                0.0
            } else {
                min(1.0, max(0.0, (center - background) / 128.0))
            }
        }

        val passing = samples.count { it >= 0.18 }
        val coverage = passing.toDouble() / BIN_COUNT
        return EdgeEvidence(edge, samples, coverage, coverage >= 0.8)
    }

    private fun classify(
        evidence: List<EdgeEvidence>,
        calibration: NodeArrayDetection,
        inkPixels: List<Int>
    ): Observation {
        val edges = evidence.filter { it.isPresent }.map { it.edge }.toSet()

        // No nearest-neighbour guess on empty evidence: an empty array is not
        // weak evidence for a Glyph, it is the absence of one.
        val candidates: List<GlyphCandidate> = if (edges.isEmpty()) {
            emptyList()
        } else {
            templates.map { (id, templateEdges) ->
                GlyphCandidate(id, edges.symmetricDifference(templateEdges).size)
            }.filter { it.distance <= maxEdgeDistance }.sorted()
        }

        val exactCount = candidates.count { it.distance == 0 }

        // A visible shape may be the start of a longer one.
        val extensions: List<GlyphID> = if (edges.isEmpty()) {
            emptyList()
        } else {
            templates.filter { (_, templateEdges) ->
                edges.size < templateEdges.size && templateEdges.containsAll(edges)
            }.map { it.first }
        }

        // Measure actual spatial support, not length * guessed stroke width:
        // overlaps, glow and different raster widths make area estimates weak.
        val positions = calibration.positions
        val segments = edges.map { edge ->
            positions[edge.lower.rawValue] to positions[edge.upper.rawValue]
        }
        val inkRadius = max(2.0, calibration.size.x * 0.015)
        val inkRadiusSquared = inkRadius * inkRadius

        val unexplained = inkPixels.count { index ->
            val point = Vec2(
                (index % calibration.imageWidth).toDouble(),
                (index / calibration.imageWidth).toDouble()
            )
            segments.none { (a, b) ->
                val segmentDelta = b - a
                val segmentLengthSquared = segmentDelta.lengthSquared()
                val t = if (segmentLengthSquared < 1e-12) {
                    0.0
                } else {
                    min(1.0, max(0.0, (point - a).dot(segmentDelta) / segmentLengthSquared))
                }
                val distance = point - (a + segmentDelta * t)
                distance.lengthSquared() <= inkRadiusSquared
            }
        }

        val excessiveInk = unexplained > max(24, (inkPixels.size * 0.35).toInt())

        val state = when {
            exactCount > 0 && excessiveInk -> State.UNEXPLAINED_INK
            exactCount == 1 -> State.UNIQUE_VISIBLE_SHAPE
            exactCount > 1 -> State.AMBIGUOUS_SHAPE
            edges.isEmpty() && inkPixels.size < max(6, (calibration.size.x * 0.025).toInt()) ->
                State.NO_STROKE
            else -> State.UNRECOGNIZED_STROKE
        }

        return Observation(
            state = state,
            calibration = calibration,
            evidence = evidence,
            strokePixelCount = inkPixels.size,
            unexplainedInkPixelCount = unexplained,
            candidates = candidates,
            extensions = extensions,
            inkPixels = inkPixels,
            edges = edges
        )
    }

    companion object {
        /** Spatial bins per edge. */
        const val BIN_COUNT = 24

        /**
         * Andrew's monotone chain. Ordered so the interior lies on the
         * non-negative side of every edge's cross product.
         */
        fun convexHull(points: List<Vec2>): List<Vec2> {
            val sorted = points.sortedWith(compareBy({ it.x }, { it.y }))
            if (sorted.size <= 2) return sorted

            fun cross(o: Vec2, a: Vec2, b: Vec2): Double =
                (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

            val lower = mutableListOf<Vec2>()
            for (point in sorted) {
                while (lower.size >= 2 &&
                    cross(lower[lower.size - 2], lower[lower.size - 1], point) <= 0
                ) {
                    lower.removeAt(lower.lastIndex)
                }
                lower.add(point)
            }

            val upper = mutableListOf<Vec2>()
            for (point in sorted.asReversed()) {
                while (upper.size >= 2 &&
                    cross(upper[upper.size - 2], upper[upper.size - 1], point) <= 0
                ) {
                    upper.removeAt(upper.lastIndex)
                }
                upper.add(point)
            }

            return lower.dropLast(1) + upper.dropLast(1)
        }

        /**
         * Whether a point is inside the hull, or within `margin` of its
         * boundary. A degenerate hull accepts everything.
         */
        fun isWithin(margin: Double, hull: List<Vec2>, point: Vec2): Boolean {
            if (hull.size <= 2) return true
            var inside = true
            var nearest = Double.POSITIVE_INFINITY
            for (index in hull.indices) {
                val a = hull[index]
                val b = hull[(index + 1) % hull.size]
                val edge = b - a
                val offset = point - a
                if (edge.x * offset.y - edge.y * offset.x < 0) inside = false

                val edgeLengthSquared = edge.lengthSquared()
                val t = if (edgeLengthSquared < 1e-12) {
                    0.0
                } else {
                    min(1.0, max(0.0, offset.dot(edge) / edgeLengthSquared))
                }
                val distance = point - (a + edge * t)
                nearest = min(nearest, distance.lengthSquared())
            }
            return inside || nearest <= margin * margin
        }
    }
}
