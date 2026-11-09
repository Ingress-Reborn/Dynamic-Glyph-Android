package org.szres.glyphlens.recognition

/** One presented position, including holes and unresolved evidence. */
data class SlotObservation(
    val index: Int,
    var status: Status = Status.PENDING,
    var glyph: GlyphID? = null,
    var alternatives: List<GlyphID> = emptyList(),
    var firstSampleTime: Double? = null,
    var lastSampleTime: Double? = null,
    var sampleCount: Int = 0,
    var boundaryReason: String? = null,
) {
    enum class Status {
        PENDING,
        OBSERVING,
        CONFIRMED,
        INFERRED,
        MISSING,
        UNCERTAIN,
    }

    /** Closed one way or another; no further evidence will be accepted. */
    val isSettled: Boolean
        get() = status == Status.CONFIRMED || status == Status.INFERRED ||
            status == Status.MISSING || status == Status.UNCERTAIN
}

/** Reviewable tracker transition associated with a frame and optional slot. */
data class TrackingEvent(
    val kind: Kind,
    val time: Double,
    val hackID: Int,
    val slotIndex: Int? = null,
    val glyph: GlyphID? = null,
    val reason: String,
    val sampleCount: Int = 0,
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
        STALE_EVIDENCE_REJECTED,
    }
}
