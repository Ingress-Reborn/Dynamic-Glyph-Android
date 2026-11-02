package com.dynamicglyph.recognition

/**
 * Observation of a single slot in the sequence.
 * Ported from Swift SlotObservation.swift.
 */
class SlotObservation(val index: Int) {

    enum class Status {
        PENDING,
        OBSERVING,
        CONFIRMED,
        /** Retained from a blank-separated shape with no readable indicator. */
        INFERRED,
        MISSING,
        UNCERTAIN
    }

    var status: Status = Status.PENDING
    var glyph: GlyphID? = null
    var edges: Set<GlyphPath.Edge> = emptySet()

    val isSettled: Boolean
        get() = status == Status.CONFIRMED || status == Status.INFERRED ||
            status == Status.MISSING || status == Status.UNCERTAIN

    override fun toString(): String = "Slot($index, $status, $glyph)"
}

/**
 * Tracking events emitted by PresentationTracker.
 *
 * The original also carries a human-readable `reason` on the invalidation and
 * rejection events, for the evidence log.
 */
data class TrackingEvent(
    val kind: Kind,
    val time: Double,
    val hackID: Int,
    val slotIndex: Int? = null,
    val glyph: GlyphID? = null,
    val reason: String? = null
) {
    enum class Kind {
        PHASE_CHANGED,
        HACK_STARTED,
        SLOT_ADVANCED,
        SLOT_COMMITTED,
        SLOT_INFERRED,
        SLOT_MISSING,
        SLOT_UNCERTAIN,
        EVIDENCE_INVALIDATED,
        SLOT_COUNT_CONFLICT,
        SEQUENCE_FROZEN,
        HACK_COMPLETED,
        STALE_EVIDENCE_REJECTED
    }
}

/**
 * Where the tracker believes the game is. Distinct from [GamePhase], which is a
 * single frame's reading: this survives unreadable frames and adds the
 * `interrupted` case that no header ever spells out.
 */
enum class TrackingState {
    INACTIVE,
    COMMAND,
    PRESENTING,
    INPUT,
    COMPLETED,
    INTERRUPTED
}
