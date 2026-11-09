package org.szres.glyphlens.recognition.image

import kotlin.math.ceil

/** Where the game's phase evidence sits, as fractions of the delivered frame. */
data class GameUILayout(
    var headerTextTop: Double = 0.060,
    var headerTextBottom: Double = 0.104,
    var slotStripTop: Double = 0.104,
    var slotStripBottom: Double = 0.176,
    var inputTimerBarTop: Double = 0.218,
    var inputTimerBarBottom: Double = 0.246,
    var inputTimerBarMinimumWidth: Double = 0.03,
    var slotMarkerMinimumWidth: Double = 0.085,
    var slotMarkerMaximumWidth: Double = 0.190,
    var slotMarkerMinimumColumnHits: Int = 3,
    var slotPitchTolerance: Double = 0.18,
    var slotCenterPitch: Double = 0.16,
    var slotCenterTolerance: Double = 0.035,
    var inputTimerTrackLeft: Double = 0.11,
    var inputTimerTrackRight: Double = 0.89,
    var inputTimerEndpointTolerance: Double = 0.035,
    var bannerMinimumRowCoverage: Double = 0.75,
    var bannerMinimumHeight: Double = 0.004,
    var coveredMarkerMinimumHeight: Double = 0.4,
    var contentLeft: Double = 0.0,
    var contentRight: Double = 1.0,
) {
    /** Rows allowed for the banner's shadowed lower edge. */
    fun bannerEdgeTolerance(frameHeight: Int): Int = maxOf(1, ceil(0.002 * frameHeight).toInt())

    /** The whole band a caller must decode in colour to use this layout. */
    val topBandBottom: Double
        get() = inputTimerBarBottom

    /** Whether the detected array sits below every region read here. */
    fun isConsistent(fit: NodeArrayDetection): Boolean =
        fit.imageHeight > 0 && fit.origin.y > topBandBottom * fit.imageHeight
}
