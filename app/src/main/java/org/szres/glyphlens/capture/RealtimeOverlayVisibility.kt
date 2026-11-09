package org.szres.glyphlens.capture

import org.szres.glyphlens.recognition.CaptureLiveness
import org.szres.glyphlens.recognition.GlyphDisplaySnapshot
import org.szres.glyphlens.recognition.PredictionState
import org.szres.glyphlens.recognition.SlotObservation
import org.szres.glyphlens.recognition.TrackingState

internal class RealtimeOverlayVisibility {
    @Volatile
    var wantsWindow = false
        private set

    private var lastReadyHackID = 0

    fun reset() {
        wantsWindow = false
        lastReadyHackID = 0
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
        if (snapshot.liveness != CaptureLiveness.LIVE ||
            !snapshot.acceptance.isTracking || snapshot.isStale(uptime, threshold = 1.5)
        ) return false
        if (hidesAfterDrawing && snapshot.hasMapControl) {
            val changed = wantsWindow
            wantsWindow = false
            if (snapshot.hackID > 0) lastReadyHackID = snapshot.hackID
            return changed
        }
        if (snapshot.hackID == lastReadyHackID || !isReady(snapshot)) return false
        return revealOnce(snapshot.hackID)
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
