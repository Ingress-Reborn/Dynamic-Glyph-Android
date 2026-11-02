package com.dynamicglyph.recognition

/**
 * All game UI layout ratios, measured from a 603×1311 English portrait capture.
 *
 * Ported from Swift GameUILayout.swift. These are fractions of frame
 * width/height, so they are resolution-independent.
 *
 * NOTE: On Android devices without a Dynamic Island, the status bar height
 * differs, so headerTextTop may need adjustment. This is a known portability
 * limitation documented in the original code.
 */
data class GameUILayout(
    val headerTextTop: Double,
    val headerTextBottom: Double,
    val slotStripTop: Double,
    val slotStripBottom: Double,
    val inputTimerBarTop: Double,
    val inputTimerBarBottom: Double,
    val inputTimerBarMinimumWidth: Double,
    val slotMarkerMinimumWidth: Double,
    val slotMarkerMaximumWidth: Double,
    val slotMarkerMinimumColumnHits: Int,
    val slotPitchTolerance: Double,
    val slotCenterPitch: Double,
    val slotCenterTolerance: Double,
    val inputTimerTrackLeft: Double,
    val inputTimerTrackRight: Double,
    val inputTimerEndpointTolerance: Double,
    val bannerMinimumRowCoverage: Double,
    val bannerMinimumHeight: Double,
    val coveredMarkerMinimumHeight: Double
) {
    /** The bottom of the top band that needs colour decoding. */
    val topBandBottom: Double get() = inputTimerBarBottom

    /** Banner shadow edge tolerance in pixels. */
    fun bannerEdgeTolerance(frameHeight: Int): Int =
        kotlin.math.max(1, (0.002 * frameHeight).toInt())

    /** Rows allowed for the banner's shadowed lower edge, about 2 px in 1218 px. */
    fun bannerEdgeTolerance(frameHeight: Int): Int =
        maxOf(1, kotlin.math.ceil(0.002 * frameHeight).toInt())

    /**
     * Whether the detected array sits below every region read here. A fit that
     * overlaps the header means the frame is not the layout these fractions
     * describe, and the phase must be reported unknown.
     */
    fun isConsistentWith(fit: NodeArrayDetection): Boolean =
        fit.imageHeight > 0 && fit.origin.y > topBandBottom * fit.imageHeight

    companion object {
        /**
         * Measured from a 603×1311 English portrait frame.
         * Adjust headerTextTop for devices without Dynamic Island.
         */
        val ENGLISH_PORTRAIT = GameUILayout(
            headerTextTop = 0.060,
            headerTextBottom = 0.104,
            slotStripTop = 0.104,
            slotStripBottom = 0.176,
            inputTimerBarTop = 0.218,
            inputTimerBarBottom = 0.246,
            inputTimerBarMinimumWidth = 0.03,
            slotMarkerMinimumWidth = 0.085,
            slotMarkerMaximumWidth = 0.190,
            slotMarkerMinimumColumnHits = 3,
            slotPitchTolerance = 0.18,
            slotCenterPitch = 0.16,
            slotCenterTolerance = 0.035,
            inputTimerTrackLeft = 0.11,
            inputTimerTrackRight = 0.89,
            inputTimerEndpointTolerance = 0.035,
            bannerMinimumRowCoverage = 0.75,
            bannerMinimumHeight = 0.004,
            coveredMarkerMinimumHeight = 0.4
        )
    }
}
