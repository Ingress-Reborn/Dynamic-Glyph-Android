package org.szres.glyphlens.recognition.session

import org.szres.glyphlens.recognition.AcceptanceMode
import org.szres.glyphlens.recognition.CaptureLiveness
import org.szres.glyphlens.recognition.GlyphDisplaySnapshot
import org.szres.glyphlens.recognition.GlyphID
import org.szres.glyphlens.recognition.SequenceOutlook
import org.szres.glyphlens.recognition.SequencePredictor
import org.szres.glyphlens.recognition.image.GlyphFrameObservation
import org.szres.glyphlens.recognition.image.NodeArrayDetection
import org.szres.glyphlens.recognition.outlook
import java.util.Locale

/** Packages the same tracker state and confirmed-prefix prediction for every display surface. */
class PresentationSnapshotFactory(
    private val predictor: SequencePredictor? = null,
) {
    private var cachedOutlookKey: Pair<List<GlyphID>, Int?>? = null
    private var cachedOutlook: SequenceOutlook = SequenceOutlook.unavailable

    fun snapshot(
        tracker: PresentationTracker,
        observation: GlyphFrameObservation?,
        arrivalUptime: Double,
        sourceTime: Double,
        liveness: CaptureLiveness = CaptureLiveness.LIVE,
        acceptance: AcceptanceMode = AcceptanceMode.TrackingSequence,
        calibration: NodeArrayDetection? = observation?.calibration,
        hasMapControl: Boolean = false,
    ): GlyphDisplaySnapshot {
        val prefix = tracker.confirmedPrefix
        val expected = tracker.expectedSlotCount
        val key = prefix to expected
        val predictor = predictor
        if (predictor == null) {
            cachedOutlook = SequenceOutlook.unavailable
        } else if (key != cachedOutlookKey) {
            cachedOutlook = predictor.outlook(observed = prefix, expectedLength = expected)
            cachedOutlookKey = key
        }

        val provisional = if (observation?.state == GlyphFrameObservation.State.UNIQUE_VISIBLE_SHAPE) {
            observation.exactMatches.firstOrNull()
        } else {
            null
        }
        val calibrationSummary = calibration?.let {
            String.format(
                Locale.ROOT,
                "%d rings · RMS %.2f px",
                it.matchedNodes.size,
                it.rootMeanSquareError,
            )
        } ?: "waiting for 11 clear rings"

        return GlyphDisplaySnapshot(
            captureSessionID = tracker.sessionID,
            hackID = tracker.hackID,
            revision = tracker.revision,
            liveness = liveness,
            state = tracker.state,
            phaseSource = tracker.phaseSource,
            acceptance = acceptance,
            sourceFrameTime = sourceTime,
            evidenceArrivalUptime = arrivalUptime,
            expectedSlotCount = expected,
            slots = tracker.slots,
            currentSlotIndex = tracker.currentSlotIndex,
            inputSlotIndex = tracker.inputSlotIndex,
            inputSlotObservedTime = tracker.lastIndicatorTime,
            indicatorsObstructed = tracker.indicatorsObstructed,
            slotCountConflict = tracker.slotCountConflict,
            isFrozen = tracker.isFrozen,
            provisionalGlyph = provisional,
            provisionalExtensionCount = observation?.extensions?.size ?: 0,
            outlook = cachedOutlook,
            calibrationSummary = calibrationSummary,
            hasMapControl = hasMapControl,
        )
    }
}
