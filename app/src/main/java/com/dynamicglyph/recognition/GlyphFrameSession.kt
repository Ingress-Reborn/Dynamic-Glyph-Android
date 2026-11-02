package com.dynamicglyph.recognition

/**
 * A live, provisional frame session. Caches geometry, not game phase: none of
 * its observations may be appended to a sequence without an external
 * presentation boundary. Timestamps must be monotonic seconds.
 *
 * Ported from Swift GlyphFrameSession.swift.
 */
class GlyphFrameSession(table: GlyphTable) {

    private val recognizer = GlyphFrameRecognizer(table)

    var calibration: NodeArrayDetection? = null
        private set

    /**
     * When the fit last matched all 11 rings on an empty array. The fit is kept
     * up to eight seconds after it, so it alone does not show that the array is
     * still on screen.
     */
    var validatedAt: Double? = null
        private set

    private var previousTime: Double? = null
    private var lastFitAttempt = Double.NEGATIVE_INFINITY

    /** How long the last [observe] spent hunting for the array, if it hunted. */
    var lastGridHuntNanos: Long? = null
        private set

    fun reset() {
        calibration = null
        previousTime = null
        validatedAt = null
        lastFitAttempt = Double.NEGATIVE_INFINITY
    }

    fun observe(image: LuminanceImage, time: Double): GlyphFrameRecognizer.Observation? {
        lastGridHuntNanos = null
        if (!time.isFinite()) {
            reset()
            return null
        }

        val previous = previousTime
        if (previous != null && (time < previous || time - previous > 1.0)) reset()
        previousTime = time

        val fit = calibration
        if (fit != null &&
            (fit.imageWidth != image.width || fit.imageHeight != image.height)
        ) {
            reset()
            previousTime = time
        }

        // A cached fit is not evidence that the array is still on screen.
        // Periodically reacquire it on a clean empty array, and expire it when
        // no such validation has been possible for eight seconds.
        val validated = validatedAt
        if (validated != null && time - validated > CALIBRATION_LIFETIME) {
            calibration = null
        }

        var observation: GlyphFrameRecognizer.Observation? =
            calibration?.let { recognizer.observe(image, it) }

        val needsHunt = calibration == null ||
            (observation?.state == GlyphFrameRecognizer.State.NO_STROKE &&
                time - lastFitAttempt >= FIT_RETRY_INTERVAL)

        if (needsHunt) {
            lastFitAttempt = time
            val huntStarted = System.nanoTime()
            val detected = NodeArrayDetector().detect(image)
            lastGridHuntNanos = System.nanoTime() - huntStarted

            if (detected != null && detected.matchedNodes.size == NodeIndex.COUNT) {
                val candidate = recognizer.observe(image, detected)
                // Only an empty array validates a fit. A stroke drawn over the
                // rings makes the fit meaningless.
                if (candidate.state == GlyphFrameRecognizer.State.NO_STROKE) {
                    calibration = detected
                    validatedAt = time
                    observation = candidate
                }
            }
        }

        return observation
    }

    companion object {
        /** How long a validated fit stays usable without revalidation. */
        const val CALIBRATION_LIFETIME = 8.0

        /** How often to retry acquiring a fit while the array is empty. */
        const val FIT_RETRY_INTERVAL = 0.5
    }
}
