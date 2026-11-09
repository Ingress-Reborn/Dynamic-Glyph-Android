package org.szres.glyphlens.recognition.image

import org.szres.glyphlens.recognition.GlyphTable

/**
 * A live, provisional frame session. Geometry is cached, game phase is not.
 * Timestamps are monotonic seconds; gaps and clock reversals invalidate continuity.
 */
class GlyphFrameSession(table: GlyphTable) {
    var calibration: NodeArrayDetection? = null
        private set

    private val recognizer = GlyphFrameRecognizer(table)
    private val detector = NodeArrayDetector()
    private var previousTime: Double? = null

    /** Last time all 11 rings matched on an empty array. */
    var validatedAt: Double? = null
        private set

    private var lastFitAttempt = Double.NEGATIVE_INFINITY

    /** Time spent hunting for the array in the last observe, or null when no hunt ran. */
    var lastGridHuntNanoseconds: Long? = null
        private set

    fun reset() {
        calibration = null
        previousTime = null
        validatedAt = null
        lastFitAttempt = Double.NEGATIVE_INFINITY
    }

    fun observe(frame: ImageFrame): GlyphFrameObservation? = observe(frame.luminance, frame.timeSeconds)

    /** Checks lifetime only. It does not claim new ring evidence or extend the empty-array deadline. */
    fun retainCalibration(image: LuminanceImage, at: Double): NodeArrayDetection? {
        if (!updateLifetime(image, at)) return null
        return calibration
    }

    fun observe(image: LuminanceImage, at: Double): GlyphFrameObservation? {
        if (!updateLifetime(image, at)) return null
        val fit = calibration
        var observation = fit?.let { recognizer.observe(image, it) }
        if (calibration == null ||
            (observation?.state == GlyphFrameObservation.State.NO_STROKE && at - lastFitAttempt >= 0.5)
        ) {
            lastFitAttempt = at
            var detected = fit?.let { detector.revalidate(image, it) }
            if (detected == null) {
                val huntStarted = System.nanoTime()
                detected = detector.detect(image)
                lastGridHuntNanoseconds = System.nanoTime() - huntStarted
            }
            if (detected != null && detected.matchedNodes.size == 11) {
                val candidate = if (detected === fit) observation else recognizer.observe(image, detected)
                if (candidate?.state == GlyphFrameObservation.State.NO_STROKE) {
                    calibration = detected
                    validatedAt = at
                    observation = candidate
                }
            }
        }
        return observation
    }

    private fun updateLifetime(image: LuminanceImage, at: Double): Boolean {
        lastGridHuntNanoseconds = null
        if (!at.isFinite()) {
            reset()
            return false
        }
        previousTime?.let { previous ->
            if (at < previous || at - previous > 1.0) reset()
        }
        previousTime = at
        calibration?.let { fit ->
            if (fit.imageWidth != image.width || fit.imageHeight != image.height) {
                reset()
                previousTime = at
            }
        }
        validatedAt?.let { validated ->
            if (at - validated > 8.0) calibration = null
        }
        return true
    }
}
