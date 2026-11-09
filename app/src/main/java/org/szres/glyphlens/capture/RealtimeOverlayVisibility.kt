package org.szres.glyphlens.capture

import org.szres.glyphlens.recognition.CaptureLiveness
import org.szres.glyphlens.recognition.GlyphDisplaySnapshot
import org.szres.glyphlens.recognition.PhaseSource
import org.szres.glyphlens.recognition.PredictionState
import org.szres.glyphlens.recognition.SlotObservation
import org.szres.glyphlens.recognition.TrackingState

internal class RealtimeOverlayVisibility {
    @Volatile
    var wantsWindow = false
        private set

    private var lastReadyHackID = 0
    private var lastInputHackID = 0
    private var lastCompletedHackID = 0
    private var lastClearedInputHackID = 0

    fun reset() {
        wantsWindow = false
        lastReadyHackID = 0
        lastInputHackID = 0
        lastCompletedHackID = 0
        lastClearedInputHackID = 0
    }

    fun show() {
        wantsWindow = true
    }

    fun hide(snapshot: GlyphDisplaySnapshot?) {
        wantsWindow = false
        if (snapshot != null && isReady(snapshot)) lastReadyHackID = snapshot.hackID
    }

    fun observe(
        snapshot: GlyphDisplaySnapshot,
        uptime: Double = snapshot.evidenceArrivalUptime,
        hidesAfterDrawing: Boolean = false,
    ): Boolean {
        if (snapshot.hackID <= 0 || snapshot.liveness != CaptureLiveness.LIVE ||
            !snapshot.acceptance.isTracking || snapshot.isStale(uptime, threshold = 1.5)
        ) return false
        if (snapshot.state == TrackingState.INPUT &&
            (snapshot.phaseSource == PhaseSource.HEADER_TEXT || snapshot.phaseSource == PhaseSource.INPUT_TIMER)
        ) lastInputHackID = snapshot.hackID
        if (hidesAfterDrawing && shouldHideAfterDrawing(snapshot)) {
            val changed = wantsWindow
            wantsWindow = false
            return changed
        }
        if (snapshot.hackID == lastReadyHackID || !isReady(snapshot)) return false
        return revealOnce(snapshot.hackID)
    }

    private fun shouldHideAfterDrawing(snapshot: GlyphDisplaySnapshot): Boolean {
        if (snapshot.hackID == lastInputHackID && snapshot.hackID != lastCompletedHackID &&
            snapshot.state == TrackingState.COMPLETED && snapshot.phaseSource == PhaseSource.HEADER_TEXT
        ) {
            lastCompletedHackID = snapshot.hackID
            return true
        }
        // A missed completion title must not leave the previous result covering the next presentation.
        val startsNextRound = snapshot.state == TrackingState.COMMAND && snapshot.phaseSource == PhaseSource.HEADER_TEXT ||
            snapshot.state == TrackingState.PRESENTING && snapshot.hackID > lastInputHackID
        if (lastInputHackID > 0 && lastInputHackID != lastClearedInputHackID && startsNextRound) {
            lastClearedInputHackID = lastInputHackID
            return true
        }
        return false
    }

    private fun revealOnce(hackID: Int): Boolean {
        if (hackID == lastReadyHackID) return false
        lastReadyHackID = hackID
        if (wantsWindow) return false
        wantsWindow = true
        return true
    }

    private fun isReady(snapshot: GlyphDisplaySnapshot): Boolean {
        if (snapshot.hackID <= 0 || snapshot.liveness != CaptureLiveness.LIVE ||
            !snapshot.acceptance.isTracking || snapshot.slotCountConflict || snapshot.indicatorsObstructed ||
            (snapshot.state != TrackingState.PRESENTING && snapshot.state != TrackingState.INPUT)
        ) return false
        val count = snapshot.expectedSlotCount ?: return false
        if (snapshot.slots.any {
                it.status == SlotObservation.Status.INFERRED || it.status == SlotObservation.Status.MISSING ||
                    it.status == SlotObservation.Status.UNCERTAIN
            }
        ) return false
        if (snapshot.slots.size == count && snapshot.slots.all {
                it.status == SlotObservation.Status.CONFIRMED && it.glyph != null
            }
        ) return true
        val outlook = snapshot.outlook
        if (outlook.state != PredictionState.RESOLVED || outlook.observed.isEmpty() ||
            outlook.observed.size + outlook.predictedSuffix.size != count
        ) return false
        return outlook.observed.indices.all { index ->
            val slot = snapshot.slots.getOrNull(index)
            slot?.status == SlotObservation.Status.CONFIRMED && slot.glyph == outlook.observed[index]
        }
    }
}
