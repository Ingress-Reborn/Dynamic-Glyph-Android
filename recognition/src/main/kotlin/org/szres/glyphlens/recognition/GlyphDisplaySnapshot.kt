package org.szres.glyphlens.recognition

/** Identifies one capture session; evidence from another session is not interchangeable. */
data class CaptureSessionID(val rawValue: ULong) {
    override fun toString(): String = rawValue.toString()
}

enum class TrackingState {
    INACTIVE,
    COMMAND,
    PRESENTING,
    INPUT,
    COMPLETED,
    INTERRUPTED,
}

enum class PhaseSource {
    RETAINED,
    INPUT_TIMER,
    INDICATOR_TRANSITION,
    NONE,
}

sealed class AcceptanceMode {
    data object TrackingSequence : AcceptanceMode()

    data class ProvisionalOnly(val reason: String) : AcceptanceMode()

    val isTracking: Boolean
        get() = this == TrackingSequence
}

enum class CaptureLiveness {
    STARTING,
    LIVE,
    INTERRUPTED,
    STOPPED,
}

enum class StatusTone {
    WARNING,
    TENTATIVE,
    IDLE,
    COLLECTING,
    DRAWING,
    DONE,
}

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
    UNKNOWN_PHASE;

    val isTrustworthy: Boolean
        get() = when (this) {
            STOPPED, STALE, OBSTRUCTED, CONFLICTED, UNKNOWN_PHASE -> false
            INFERRED, PROVISIONAL, WAITING, COLLECTING, YOUR_TURN, COMPLETE -> true
        }

    val tone: StatusTone
        get() = when (this) {
            STOPPED, STALE, OBSTRUCTED, CONFLICTED -> StatusTone.WARNING
            INFERRED, PROVISIONAL -> StatusTone.TENTATIVE
            WAITING, UNKNOWN_PHASE -> StatusTone.IDLE
            COLLECTING -> StatusTone.COLLECTING
            YOUR_TURN -> StatusTone.DRAWING
            COMPLETE -> StatusTone.DONE
        }
}

/** The single immutable value consumed by every display surface. */
class GlyphDisplaySnapshot(
    val captureSessionID: CaptureSessionID,
    val hackID: Int,
    val revision: Int,
    val liveness: CaptureLiveness,
    val state: TrackingState,
    val phaseSource: PhaseSource,
    val acceptance: AcceptanceMode,
    val sourceFrameTime: Double,
    val evidenceArrivalUptime: Double,
    val expectedSlotCount: Int?,
    val slots: List<SlotObservation>,
    val currentSlotIndex: Int?,
    val inputSlotIndex: Int?,
    val inputSlotObservedTime: Double? = null,
    val indicatorsObstructed: Boolean = false,
    val slotCountConflict: Boolean,
    val isFrozen: Boolean,
    val provisionalGlyph: GlyphID?,
    val provisionalExtensionCount: Int,
    val outlook: SequenceOutlook,
    val calibrationSummary: String,
    val hasMapControl: Boolean = false,
) {
    fun age(atUptime: Double): Double =
        if (evidenceArrivalUptime > 0.0) maxOf(0.0, atUptime - evidenceArrivalUptime) else 0.0

    fun isStale(atUptime: Double, threshold: Double): Boolean =
        evidenceArrivalUptime > 0.0 && age(atUptime) > threshold

    fun displayStatus(atUptime: Double, stalenessThreshold: Double = 1.5): DisplayStatus {
        if (liveness == CaptureLiveness.STOPPED) return DisplayStatus.STOPPED
        if (isStale(atUptime, stalenessThreshold)) return DisplayStatus.STALE
        if (slots.any { it.status == SlotObservation.Status.INFERRED }) return DisplayStatus.INFERRED
        if (indicatorsObstructed) return DisplayStatus.OBSTRUCTED
        if (acceptance is AcceptanceMode.ProvisionalOnly) return DisplayStatus.PROVISIONAL
        if (slotCountConflict) return DisplayStatus.CONFLICTED
        return when (state) {
            TrackingState.INACTIVE, TrackingState.COMMAND -> DisplayStatus.WAITING
            TrackingState.PRESENTING -> DisplayStatus.COLLECTING
            TrackingState.INPUT -> DisplayStatus.YOUR_TURN
            TrackingState.COMPLETED -> DisplayStatus.COMPLETE
            TrackingState.INTERRUPTED -> DisplayStatus.UNKNOWN_PHASE
        }
    }

    fun statusLine(atUptime: Double, stalenessThreshold: Double = 1.5): String {
        return when (displayStatus(atUptime, stalenessThreshold)) {
            DisplayStatus.STOPPED -> "Not running"
            DisplayStatus.STALE -> "Paused"
            DisplayStatus.OBSTRUCTED -> "Slot row unreadable · keep it visible"
            DisplayStatus.INFERRED -> "Best guess · positions unverified"
            DisplayStatus.PROVISIONAL -> {
                val mode = acceptance
                if (mode is AcceptanceMode.ProvisionalOnly) "Getting ready · ${mode.reason}"
                else "Getting ready"
            }
            DisplayStatus.CONFLICTED -> "Slot count changed · positions unreliable"
            DisplayStatus.WAITING -> if (state == TrackingState.COMMAND) {
                "Get ready · the sequence is coming"
            } else {
                "Waiting for a Glyph hack"
            }
            DisplayStatus.COLLECTING -> if (phaseSource == PhaseSource.RETAINED) {
                "Watching the sequence · waiting for indicators"
            } else {
                "Watching the sequence"
            }
            DisplayStatus.YOUR_TURN -> "Your turn · draw the sequence"
            DisplayStatus.COMPLETE -> "Hack complete"
            DisplayStatus.UNKNOWN_PHASE -> "Lost sight of the game"
        }
    }

    private fun valueComponents(): List<Any?> = listOf(
        captureSessionID, hackID, revision, liveness, state, phaseSource, acceptance,
        sourceFrameTime, evidenceArrivalUptime, expectedSlotCount, slots, currentSlotIndex,
        inputSlotIndex, inputSlotObservedTime, indicatorsObstructed, slotCountConflict, isFrozen,
        provisionalGlyph, provisionalExtensionCount, outlook, calibrationSummary, hasMapControl,
    )

    override fun equals(other: Any?): Boolean =
        other is GlyphDisplaySnapshot && valueComponents() == other.valueComponents()

    override fun hashCode(): Int = valueComponents().hashCode()

    override fun toString(): String = "GlyphDisplaySnapshot(hackID=$hackID, revision=$revision)"

    companion object {
        @JvmStatic
        fun waiting(
            sessionID: CaptureSessionID,
            liveness: CaptureLiveness,
            acceptance: AcceptanceMode,
        ): GlyphDisplaySnapshot = GlyphDisplaySnapshot(
            captureSessionID = sessionID,
            hackID = 0,
            revision = 0,
            liveness = liveness,
            state = TrackingState.INACTIVE,
            phaseSource = PhaseSource.NONE,
            acceptance = acceptance,
            sourceFrameTime = 0.0,
            evidenceArrivalUptime = 0.0,
            expectedSlotCount = null,
            slots = emptyList(),
            currentSlotIndex = null,
            inputSlotIndex = null,
            slotCountConflict = false,
            isFrozen = false,
            provisionalGlyph = null,
            provisionalExtensionCount = 0,
            outlook = SequenceOutlook.unavailable,
            calibrationSummary = "waiting for an empty array",
        )
    }
}
