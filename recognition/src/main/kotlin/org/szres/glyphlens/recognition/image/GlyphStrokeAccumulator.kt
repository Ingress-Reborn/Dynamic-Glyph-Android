package org.szres.glyphlens.recognition.image

import org.szres.glyphlens.recognition.GlyphID
import org.szres.glyphlens.recognition.GlyphPath

/** Evidence that ONE Glyph presentation ended; exact matching alone is never a boundary. */
enum class GlyphCompletion {
    UNCONFIRMED,
    PRESENTATION_BOUNDARY,
}

data class GlyphFrameSummary(
    val state: GlyphFrameObservation.State,
    val edges: Set<GlyphPath.Edge>,
    val exactMatches: List<GlyphID>,
    val extensions: List<GlyphID>,
)

data class GlyphStrokeResult(
    val observation: GlyphFrameObservation,
    val frames: List<GlyphFrameSummary>,
    val completion: GlyphCompletion,
    /** Two non-nested exact shapes may be a missed boundary or a fading trail. */
    val possibleBoundaryCrossing: Boolean,
) {
    val frameCount: Int
        get() = frames.size

    /** Sequence-ready only under the caller's explicit boundary assertion. */
    val acceptedGlyph: GlyphID?
        get() = if (completion == GlyphCompletion.PRESENTATION_BOUNDARY &&
            !possibleBoundaryCrossing && observation.hasUniqueVisibleShape
        ) observation.exactMatches.firstOrNull() else null
}

/** Accumulates ONE presentation with fixed calibration and vocabulary. */
class GlyphStrokeAccumulator(
    val calibration: NodeArrayDetection,
    private val recognizer: GlyphFrameRecognizer,
    val maximumFrames: Int = 300,
) {
    sealed class AccumulationError(message: String) : Exception(message) {
        data object ImageSizeChanged : AccumulationError("Image size changed")
        data object FrameLimitExceeded : AccumulationError("Frame limit exceeded")
    }

    private var evidence: List<GlyphEdgeEvidence> = emptyList()
    private val inkPixels = linkedSetOf<Int>()
    private var frames: List<GlyphFrameSummary> = emptyList()
    private var possibleBoundaryCrossing = false

    init {
        require(maximumFrames > 0) { "Maximum frames must be positive" }
    }

    /** A rejected frame invalidates the accumulated drawing before throwing. */
    fun append(image: LuminanceImage): GlyphFrameObservation {
        if (image.width != calibration.imageWidth || image.height != calibration.imageHeight) {
            reset()
            throw AccumulationError.ImageSizeChanged
        }
        if (frames.size >= maximumFrames) {
            reset()
            throw AccumulationError.FrameLimitExceeded
        }
        val observation = recognizer.observe(image, calibration)
        val edges = observation.edges
        if (observation.hasUniqueVisibleShape && frames.any { frame ->
                frame.state == GlyphFrameObservation.State.UNIQUE_VISIBLE_SHAPE &&
                    !edges.containsAll(frame.edges) && !frame.edges.containsAll(edges)
            }
        ) possibleBoundaryCrossing = true

        evidence = if (evidence.isEmpty()) {
            observation.evidence
        } else {
            evidence.zip(observation.evidence).map { (old, new) ->
                GlyphEdgeEvidence(
                    old.edge,
                    old.samples.zip(new.samples).map { (a, b) -> maxOf(a, b) },
                )
            }
        }
        inkPixels.addAll(observation.inkPixels)
        frames = frames + GlyphFrameSummary(
            observation.state,
            edges,
            observation.exactMatches,
            observation.extensions,
        )
        return observation
    }

    /** Consumes the drawing. Default output remains provisional even after an exact union. */
    fun finish(completion: GlyphCompletion = GlyphCompletion.UNCONFIRMED): GlyphStrokeResult? {
        try {
            if (frames.isEmpty()) return null
            return GlyphStrokeResult(
                observation = recognizer.classify(evidence, calibration, inkPixels.toList()),
                frames = frames,
                completion = completion,
                possibleBoundaryCrossing = possibleBoundaryCrossing,
            )
        } finally {
            reset()
        }
    }

    fun reset() {
        evidence = emptyList()
        inkPixels.clear()
        frames = emptyList()
        possibleBoundaryCrossing = false
    }
}
