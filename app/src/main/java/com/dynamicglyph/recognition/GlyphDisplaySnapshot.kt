package com.dynamicglyph.recognition

/**
 * The immutable display snapshot read by all rendering surfaces.
 *
 * Ported from Swift GlyphDisplaySnapshot.swift.
 * Contains the confirmed prefix, slot states, provisional glyph,
 * prediction outlook, and display status.
 */
data class GlyphDisplaySnapshot(
    val sessionID: CaptureSessionID,
    val hackID: Int,
    val revision: Int,
    val slots: List<SlotDisplay>,
    val expectedSlotCount: Int?,
    val provisionalGlyph: GlyphID?,
    val prediction: SequencePredictor.Prediction?,
    val status: DisplayStatus,
    val statusLine: String,
    val captureLiveness: CaptureLiveness,
    val uptime: Double
) {
    /** Per-slot display info. */
    data class SlotDisplay(
        val index: Int,
        val status: SlotObservation.Status,
        val glyph: GlyphID?,
        val edges: Set<GlyphPath.Edge>?
    )

    /** Display status by priority. */
    enum class DisplayStatus {
        STOPPED,
        STALE,
        OBSTRUCTED,
        INFERRED,
        PROVISIONAL,
        CONFLICTED,
        WAITING,
        COLLECTING,
        YOUR_TURN,
        COMPLETE,
        UNKNOWN_PHASE
    }

    enum class CaptureLiveness {
        STARTING, LIVE, INTERRUPTED, STOPPED
    }

    enum class StatusTone {
        WARNING, TENTATIVE, IDLE, COLLECTING, DRAWING, DONE
    }

    val statusTone: StatusTone
        get() = when (status) {
            DisplayStatus.STOPPED -> StatusTone.IDLE
            DisplayStatus.STALE -> StatusTone.WARNING
            DisplayStatus.OBSTRUCTED -> StatusTone.WARNING
            DisplayStatus.INFERRED -> StatusTone.TENTATIVE
            DisplayStatus.PROVISIONAL -> StatusTone.TENTATIVE
            DisplayStatus.CONFLICTED -> StatusTone.WARNING
            DisplayStatus.WAITING -> StatusTone.IDLE
            DisplayStatus.COLLECTING -> StatusTone.COLLECTING
            DisplayStatus.YOUR_TURN -> StatusTone.DRAWING
            DisplayStatus.COMPLETE -> StatusTone.DONE
            DisplayStatus.UNKNOWN_PHASE -> StatusTone.WARNING
        }

    companion object {
        const val STALENESS_THRESHOLD = 1.5
    }
}
