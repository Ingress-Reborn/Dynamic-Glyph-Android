package com.dynamicglyph.recognition

import kotlin.math.abs
import kotlin.math.max

/**
 * Turns timestamped frame evidence into a presented sequence.
 *
 * Ported from Swift PresentationTracker.swift. Pure: no images, no clock, no
 * framework. Everything it knows arrives as [FrameEvidence], which is what makes
 * every rule testable by handing it a list of values.
 *
 * A Glyph is only confirmed in the sequence when an *independently detected*
 * boundary says the slot ended. An exact shape match or a blank frame cannot
 * confirm a slot. When the header establishes presentation but the indicators
 * are covered, blank-separated shapes may be retained as inferred entries,
 * excluded from prediction and guidance.
 *
 * ### Fidelity
 *
 * This is a faithful port of the original's phase and slot rules. Two parts are
 * reduced, and are called out where they occur:
 *
 * - `coveredSince` overlay detection is simplified: a rejection of
 *   [SlotStripReader.Rejection.COVERED_BY_OVERLAY] starts the obstruction clock
 *   without the original's additional "array is in view" gate.
 * - The fallback (indicator-less) path infers slots from blank intervals, but
 *   does not reproduce the original's `nextInferredIndex` bookkeeping across
 *   several consecutive inferred slots.
 *
 * Everything else — phase priority, the indicator prelude, slot-count voting and
 * conflict, skipped-slot marking, release-last-slot, obstruction grace, the
 * nested-hypothesis resolution and the interrupted/reset paths — follows the
 * Swift source.
 */
class PresentationTracker(
    val sessionID: CaptureSessionID,
    val policy: Policy = Policy()
) {

    data class Policy(
        /** Two supporting samples this far apart make a shape stable. */
        val minimumStableInterval: Double = 0.045,
        val minimumStableSamples: Int = 2,
        /** A larger jump between source timestamps means frames were lost. */
        val maximumFrameGap: Double = 1.0,
        /** How long a phase survives an unreadable header. */
        val unknownPhaseGrace: Double = 0.75,
        /** Display-only: evidence older than this is shown as stale. */
        val stalenessThreshold: Double = 1.5,
        /** Agreeing strip readings needed before a slot count is trusted. */
        val slotCountVotesRequired: Int = 2,
        /** How long the indicators may be unreadable mid-hack. */
        val indicatorObstructionGrace: Double = 0.35,
        /** Minimum clear-array interval that separates provisional shapes. */
        val minimumBlankInterval: Double = 0.045,
        /** Whether a stable empty strip then slot-0 may establish presentation. */
        val entersPresentationFromIndicators: Boolean = true,
        /** Whether the last slot may close on its released indicator. */
        val closesLastSlotOnRelease: Boolean = true,
        /** How long the strip must have been out of sight for a new hack. */
        val minimumStripAbsenceForNewHack: Double = 1.5
    )

    private class Hypothesis(
        val glyph: GlyphID,
        val edges: Set<GlyphPath.Edge>,
        val times: MutableList<Double> = mutableListOf()
    ) {
        fun isStable(policy: Policy): Boolean =
            times.size >= policy.minimumStableSamples &&
                times.isNotEmpty() &&
                times.last() - times.first() >= policy.minimumStableInterval
    }

    private class IndicatorPrelude(
        val reading: SlotStripReader.Reading,
        val firstTime: Double,
        var lastTime: Double,
        var samples: Int
    )

    // -- Observable state --

    var state: TrackingState = TrackingState.INACTIVE
        private set
    var phaseSource: PhaseSource = PhaseSource.NONE
        private set
    var hackID: Int = 0
        private set

    private val slotList = mutableListOf<SlotObservation>()
    val slots: List<SlotObservation> get() = slotList

    var currentSlotIndex: Int? = null
        private set
    var inputSlotIndex: Int? = null
        private set
    var slotCountConflict: Boolean = false
        private set
    var revision: Int = 0
        private set
    var lastEvidenceTime: Double? = null
        private set
    var lastPhaseTime: Double? = null
        private set
    var isFrozen: Boolean = false
        private set

    /**
     * Whether the slots currently held were collected from a presentation this
     * tracker actually watched. A hack whose presentation was never legible must
     * not inherit the previous hack's answer just because its indicator reads
     * cleanly.
     */
    var observedPresentationForCurrentHack: Boolean = false
        private set

    var lastIndicatorTime: Double? = null
        private set
    var indicatorsObstructed: Boolean = false
        private set

    // -- Private state --

    private val hypotheses = mutableListOf<Hypothesis>()
    private val slotCountVotes = mutableMapOf<Int, Int>()
    private var inferredFromIndex: Int? = null
    private var blankSince: Double? = null
    private var lastSlotReleasedSince: Double? = null
    private var indicatorPrelude: IndicatorPrelude? = null
    private var inputHighlightTime: Double? = null
    private var indicatorPhaseAnchor: SlotStripReader.Reading? = null
    private var freshIndicatorStart = false
    private var lastStripReadingTime: Double? = null
    private var stripReturnedAfterAbsence = false
    private var coveredSince: Double? = null

    /**
     * The slot count the strip has independently and repeatedly shown.
     * Null while unproven or contradicted — never a guess from the number of
     * Glyphs that happen to have been recognised.
     */
    val expectedSlotCount: Int?
        get() {
            if (slotCountConflict) return null
            val qualified = slotCountVotes.filter { it.value >= policy.slotCountVotesRequired }
            return if (qualified.size == 1) qualified.keys.first() else null
        }

    /**
     * The shape currently being drawn in the open slot, once it has held long
     * enough to be worth showing. Never part of the sequence: only a boundary
     * can commit a slot.
     */
    val provisionalGlyph: GlyphID?
        get() = hypotheses
            .filter { it.isStable(policy) }
            .maxByOrNull { it.edges.size }
            ?.glyph

    /**
     * The contiguous run of confirmed Glyphs from slot zero.
     *
     * This, and only this, may be given to the predictor: a table prefix lookup
     * has no way to express "the second Glyph is unknown", so feeding it slot 3
     * in slot 2's place would silently answer a different question.
     */
    val confirmedPrefix: List<GlyphID>
        get() {
            val prefix = mutableListOf<GlyphID>()
            for (slot in slotList) {
                if (slot.status != SlotObservation.Status.CONFIRMED) break
                val glyph = slot.glyph ?: break
                prefix.add(glyph)
            }
            return prefix
        }

    // -- Evidence --

    fun accept(evidence: FrameEvidence): List<TrackingEvent> {
        if (evidence.sessionID != sessionID) {
            return listOf(
                TrackingEvent(
                    TrackingEvent.Kind.STALE_EVIDENCE_REJECTED,
                    evidence.time, hackID,
                    reason = "evidence from capture session ${evidence.sessionID}"
                )
            )
        }

        val events = mutableListOf<TrackingEvent>()

        val last = lastEvidenceTime
        if (last != null) {
            if (evidence.time < last) {
                events += invalidateTentative(evidence.time, "source time moved backwards")
                state = TrackingState.INTERRUPTED
                phaseSource = PhaseSource.NONE
                lastStripReadingTime = null
                lastPhaseTime = null
            } else if (evidence.time - last > policy.maximumFrameGap) {
                events += invalidateTentative(
                    evidence.time,
                    "frame gap of " + String.format("%.2f", evidence.time - last) + " s"
                )
                state = TrackingState.INTERRUPTED
                phaseSource = PhaseSource.NONE
            }
        }
        lastEvidenceTime = evidence.time

        if (evidence.slots != null) {
            val previousReading = lastStripReadingTime
            if (previousReading == null ||
                evidence.time - previousReading >= policy.minimumStripAbsenceForNewHack
            ) {
                stripReturnedAfterAbsence = true
            }
            lastStripReadingTime = evidence.time
        }

        val before = displayState()
        events += applyPhase(evidence)
        events += applySlots(evidence)
        events += applyFallback(evidence)
        events += applyShape(evidence)
        if (state == TrackingState.PRESENTING) stripReturnedAfterAbsence = false

        // Not keyed on events alone: the indicator advancing during player input
        // is the single most important thing a renderer can show, and it
        // produces no event — it is a reading, not a decision.
        if (events.isNotEmpty() || displayState() != before) revision++

        return events
    }

    /** A new source-clock epoch invalidates phase timing, not hack identity. */
    fun invalidatePhase() {
        lastPhaseTime = null
        if (state != TrackingState.INACTIVE) state = TrackingState.INTERRUPTED
        phaseSource = PhaseSource.NONE
        revision++
    }

    /** The user asked for a clean slate, or the session is ending. */
    fun reset() {
        state = TrackingState.INACTIVE
        phaseSource = PhaseSource.NONE
        slotList.clear()
        hypotheses.clear()
        slotCountVotes.clear()
        currentSlotIndex = null
        inputSlotIndex = null
        inputHighlightTime = null
        slotCountConflict = false
        isFrozen = false
        observedPresentationForCurrentHack = false
        lastIndicatorTime = null
        indicatorsObstructed = false
        coveredSince = null
        lastEvidenceTime = null
        lastPhaseTime = null
        inferredFromIndex = null
        blankSince = null
        lastSlotReleasedSince = null
        indicatorPrelude = null
        indicatorPhaseAnchor = null
        freshIndicatorStart = false
        lastStripReadingTime = null
        stripReturnedAfterAbsence = false
        revision++
    }

    // -- Phase --

    private fun applyPhase(evidence: FrameEvidence): List<TrackingEvent> {
        val events = mutableListOf<TrackingEvent>()
        var resolved = GamePhase.UNKNOWN
        var source = PhaseSource.NONE

        val header = evidence.header
        val lastPhase = lastPhaseTime
        if (header != null && header.phase != GamePhase.UNKNOWN &&
            header.sourceTime <= evidence.time &&
            evidence.time - header.sourceTime <= policy.unknownPhaseGrace
        ) {
            resolved = header.phase
            source = PhaseSource.HEADER_TEXT
            // The header's own frame time, so a lagging analysis shortens the
            // grace period instead of silently extending it.
            lastPhaseTime = max(
                lastPhase ?: Double.NEGATIVE_INFINITY,
                minOf(header.sourceTime, evidence.time)
            )
        } else if (indicatorPhaseAnchor == null) {
            val retained = currentPhase()
            if (lastPhase != null && lastPhase.isFinite() && evidence.time >= lastPhase &&
                evidence.time - lastPhase <= policy.unknownPhaseGrace && retained != null
            ) {
                resolved = retained
                source = PhaseSource.RETAINED
            }
        }

        val contradictoryHeader = source == PhaseSource.HEADER_TEXT && resolved != GamePhase.PRESENTING
        val indicatorPresentation = presentationFromIndicators(evidence, contradictoryHeader)
        if (source != PhaseSource.HEADER_TEXT && indicatorPresentation) {
            resolved = GamePhase.PRESENTING
            source = PhaseSource.INDICATOR_TRANSITION
        }

        // The countdown bar is evidence from THIS frame. A header reading is not:
        // text recognition runs behind the sampler, so a header saying
        // "presenting" can be several frames old while the bar in front of us
        // says the player is already drawing. When they disagree, the current
        // frame wins — collecting player strokes as presentation evidence is the
        // failure this whole design exists to prevent.
        if (evidence.hasInputTimerBar && evidence.hasValidCalibration &&
            observedPresentationForCurrentHack &&
            (state == TrackingState.PRESENTING || state == TrackingState.INPUT ||
                state == TrackingState.INTERRUPTED) &&
            resolved != GamePhase.COMMAND && resolved != GamePhase.COMPLETED
        ) {
            resolved = GamePhase.INPUT
            source = PhaseSource.INPUT_TIMER
            lastPhaseTime = evidence.time
        }

        val target = trackingStateFor(resolved)
        if (target == state) {
            phaseSource = source
            return events
        }

        if (state == TrackingState.PRESENTING) {
            if (target == TrackingState.INPUT) {
                if (source == PhaseSource.INPUT_TIMER && evidence.slots == null) {
                    val index = currentSlotIndex
                    if (index != null) {
                        // A timer without readable indicators can stop
                        // collection, but must not promote a possibly unfinished
                        // shape.
                        inferredFromIndex = minOf(inferredFromIndex ?: index, index)
                    }
                }
                events += finalizeCurrentSlot(evidence.time, "presentation ended, player input began")
            } else {
                // The presentation was abandoned rather than finished.
                // Tentative evidence is discarded; committed slots stay.
                events += invalidateTentative(evidence.time, "left presentation for $target")
            }
        }

        if (target == TrackingState.PRESENTING) {
            val fresh = source == PhaseSource.INDICATOR_TRANSITION &&
                freshIndicatorStart && stripReturnedAfterAbsence
            if (shouldStartNewHack(evidence.slots, fresh)) {
                events += startHack(evidence.time)
            } else {
                events.add(
                    TrackingEvent(
                        TrackingEvent.Kind.PHASE_CHANGED, evidence.time, hackID,
                        reason = "resumed presentation after $state"
                    )
                )
            }
        }

        if (target == TrackingState.COMMAND) {
            // Command-entry shapes are drawn on the same array with the same
            // vocabulary and must never reach a slot.
            hypotheses.clear()
            currentSlotIndex = null
            observedPresentationForCurrentHack = false
            events += discardRetainedSequence(evidence.time, "command channel opened")
        }

        if (target == TrackingState.INPUT && !observedPresentationForCurrentHack) {
            events += discardRetainedSequence(
                evidence.time,
                "input began but no presentation was observed"
            )
        }

        if (target == TrackingState.INPUT && !isFrozen) {
            isFrozen = true
            events.add(
                TrackingEvent(
                    TrackingEvent.Kind.SEQUENCE_FROZEN, evidence.time, hackID,
                    reason = "player input began; presented sequence retained"
                )
            )
        }

        if (target == TrackingState.COMPLETED) {
            observedPresentationForCurrentHack = false
            isFrozen = true
            events.add(
                TrackingEvent(
                    TrackingEvent.Kind.HACK_COMPLETED, evidence.time, hackID,
                    reason = "completion screen"
                )
            )
        }

        events.add(
            TrackingEvent(
                TrackingEvent.Kind.PHASE_CHANGED, evidence.time, hackID,
                reason = "$state -> $target via $source"
            )
        )
        state = target
        phaseSource = source
        if (target != TrackingState.INPUT) {
            inputSlotIndex = null
            inputHighlightTime = null
        }
        return events
    }

    /**
     * A presentation screen is a new hack unless it is the same one resuming
     * after frames the header could not be read in. Getting this wrong in either
     * direction is expensive: too eager and a glitch erases a collected
     * sequence, too reluctant and the previous hack's Glyphs are shown for the
     * new one.
     */
    private fun shouldStartNewHack(
        reading: SlotStripReader.Reading?,
        freshIndicatorStart: Boolean
    ): Boolean {
        if (state != TrackingState.INTERRUPTED || hackID == 0 || !isFrozen) return true
        // A verified empty-strip -> slot-0 transition after the strip was gone is
        // a new presentation, even when the missed countdown left the old hack
        // unfrozen and the new hack has exactly the same number of slots.
        if (freshIndicatorStart) return true
        val expected = expectedSlotCount
        if (reading != null && expected != null && reading.slotCount != expected) return true
        return false
    }

    /**
     * A selected row alone cannot identify presentation: input uses the same row.
     * Require its stable, empty, unselected prelude and the subsequent slot-0
     * transition. Keep that phase only while no countdown bar is drawn and with
     * compatible visible indicators; never restart from a mid-input highlight.
     */
    private fun presentationFromIndicators(
        evidence: FrameEvidence,
        contradictoryHeader: Boolean
    ): Boolean {
        freshIndicatorStart = false
        val reading = evidence.slots
        if (!policy.entersPresentationFromIndicators || contradictoryHeader ||
            state == TrackingState.INPUT || !evidence.hasValidCalibration ||
            evidence.hasInputTimerBar || reading == null
        ) {
            indicatorPrelude = null
            indicatorPhaseAnchor = null
            return false
        }

        fun agrees(other: SlotStripReader.Reading): Boolean {
            if (reading.slotCount != other.slotCount) return false
            if (reading.markerCentersX.size != other.markerCentersX.size) return false
            for (i in reading.markerCentersX.indices) {
                if (abs(reading.markerCentersX[i] - other.markerCentersX[i]) > 0.02) return false
            }
            return true
        }

        val anchor = indicatorPhaseAnchor
        if (anchor != null) {
            if (state == TrackingState.PRESENTING && !slotCountConflict && agrees(anchor)) {
                val selected = reading.highlightedIndex
                if (selected != null && selected >= (anchor.highlightedIndex ?: 0)) {
                    indicatorPhaseAnchor = reading
                    return true
                }
                // After the last Glyph the game drops the last indicator's
                // highlight and keeps the strip up until the countdown. That is
                // the tail of this presentation, not a departure from it.
                if (selected == null && anchor.highlightedIndex == reading.slotCount - 1) {
                    return true
                }
            }
            indicatorPhaseAnchor = null
        }

        val shapeKind = evidence.shape?.kind

        if (reading.highlightedIndex == null && shapeKind == ShapeKind.NO_STROKE) {
            val prelude = indicatorPrelude
            if (prelude != null && agrees(prelude.reading) &&
                evidence.time > prelude.lastTime &&
                evidence.time - prelude.lastTime <= policy.maximumFrameGap
            ) {
                prelude.samples++
                prelude.lastTime = evidence.time
            } else {
                indicatorPrelude = IndicatorPrelude(reading, evidence.time, evidence.time, 1)
            }
            return false
        }

        val prelude = indicatorPrelude
        indicatorPrelude = null

        if (reading.highlightedIndex != 0 || prelude == null) return false
        if (!agrees(prelude.reading)) return false
        if (prelude.samples < policy.minimumStableSamples) return false
        if (prelude.lastTime - prelude.firstTime < policy.minimumStableInterval) return false
        if (evidence.time < prelude.lastTime) return false
        if (evidence.time - prelude.lastTime > policy.maximumFrameGap) return false

        indicatorPhaseAnchor = reading
        freshIndicatorStart = true
        return true
    }

    private fun currentPhase(): GamePhase? = when (state) {
        TrackingState.COMMAND -> GamePhase.COMMAND
        TrackingState.PRESENTING -> GamePhase.PRESENTING
        TrackingState.INPUT -> GamePhase.INPUT
        TrackingState.COMPLETED -> GamePhase.COMPLETED
        TrackingState.INACTIVE, TrackingState.INTERRUPTED -> null
    }

    private fun trackingStateFor(phase: GamePhase): TrackingState = when (phase) {
        GamePhase.UNKNOWN ->
            if (state == TrackingState.INACTIVE) TrackingState.INACTIVE else TrackingState.INTERRUPTED
        GamePhase.COMMAND -> TrackingState.COMMAND
        GamePhase.PRESENTING -> TrackingState.PRESENTING
        GamePhase.INPUT -> TrackingState.INPUT
        GamePhase.COMPLETED -> TrackingState.COMPLETED
    }

    /** Everything about the previous hack is discarded here, exactly once. */
    private fun startHack(time: Double): List<TrackingEvent> {
        hackID++
        slotList.clear()
        hypotheses.clear()
        slotCountVotes.clear()
        slotCountConflict = false
        currentSlotIndex = null
        inputSlotIndex = null
        inputHighlightTime = null
        inferredFromIndex = null
        blankSince = null
        lastIndicatorTime = null
        indicatorsObstructed = false
        coveredSince = null
        isFrozen = false
        observedPresentationForCurrentHack = true
        return listOf(
            TrackingEvent(
                TrackingEvent.Kind.HACK_STARTED, time, hackID,
                reason = "presentation screen after $state"
            )
        )
    }

    // -- Slots --

    private fun applySlots(evidence: FrameEvidence): List<TrackingEvent> {
        // Readability is tracked whether or not the reading is usable, because
        // "the indicators stopped being legible mid-hack" is itself evidence.
        if (state == TrackingState.PRESENTING || state == TrackingState.INPUT) {
            if (evidence.slots != null) {
                lastIndicatorTime = evidence.time
                indicatorsObstructed = false
            } else {
                val lastIndicator = lastIndicatorTime
                if (lastIndicator == null ||
                    evidence.time - lastIndicator > policy.indicatorObstructionGrace
                ) {
                    indicatorsObstructed = true
                }
            }
        } else if (evidence.hasValidCalibration &&
            evidence.slotRejection == SlotStripReader.Rejection.COVERED_BY_OVERLAY
        ) {
            // Simplified from the original: the array-in-view gate is not
            // reproduced. See the class documentation.
            if (coveredSince == null) coveredSince = evidence.time
            val since = coveredSince
            indicatorsObstructed = since != null &&
                evidence.time - since >= policy.indicatorObstructionGrace
        } else {
            coveredSince = null
            indicatorsObstructed = false
        }

        val reading = evidence.slots ?: return emptyList()
        val events = mutableListOf<TrackingEvent>()

        if (state == TrackingState.PRESENTING || state == TrackingState.INPUT) {
            slotCountVotes[reading.slotCount] = (slotCountVotes[reading.slotCount] ?: 0) + 1
            val qualified = slotCountVotes.filter { it.value >= policy.slotCountVotesRequired }
            if (qualified.size > 1 && !slotCountConflict) {
                slotCountConflict = true
                events += invalidateTentative(evidence.time, "slot count changed mid-hack")
                events.add(
                    TrackingEvent(
                        TrackingEvent.Kind.SLOT_COUNT_CONFLICT, evidence.time, hackID,
                        reason = "counts " + qualified.keys.sorted().joinToString("/")
                    )
                )
            }
        }

        when (state) {
            TrackingState.PRESENTING -> {
                ensureSlots(reading.slotCount - 1)

                val highlighted = reading.highlightedIndex
                if (highlighted == null) {
                    events += releaseLastSlot(evidence, reading)
                    return events
                }
                lastSlotReleasedSince = null

                val firstInferred = inferredFromIndex
                if (firstInferred != null) {
                    // Do not promote guessed positions when the strip returns.
                    // Discard that region and re-anchor on the actual highlight.
                    for (index in firstInferred until slotList.size) {
                        slotList[index] = SlotObservation(index)
                    }
                    while (slotList.size > reading.slotCount) {
                        slotList.removeAt(slotList.lastIndex)
                    }
                    currentSlotIndex = null
                    hypotheses.clear()
                    inferredFromIndex = null
                    blankSince = null
                    events.add(
                        TrackingEvent(
                            TrackingEvent.Kind.EVIDENCE_INVALIDATED, evidence.time, hackID,
                            reason = "indicators restored; inferred positions discarded"
                        )
                    )
                }

                val open = currentSlotIndex
                if (open == null) {
                    events += openSlot(highlighted, evidence.time)
                    return events
                }

                if (highlighted > open) {
                    events += finalizeCurrentSlot(
                        evidence.time,
                        "indicator advanced to slot ${highlighted + 1}"
                    )
                    // Any slot the highlight skipped over never produced evidence.
                    for (skipped in (open + 1) until highlighted) {
                        ensureSlots(skipped)
                        slotList[skipped].status = SlotObservation.Status.MISSING
                        events.add(
                            TrackingEvent(
                                TrackingEvent.Kind.SLOT_MISSING, evidence.time, hackID,
                                slotIndex = skipped, reason = "indicator skipped this slot"
                            )
                        )
                    }
                    events += openSlot(highlighted, evidence.time)
                    events.add(
                        TrackingEvent(
                            TrackingEvent.Kind.SLOT_ADVANCED, evidence.time, hackID,
                            slotIndex = highlighted, reason = "verified indicator transition"
                        )
                    )
                } else if (highlighted < open) {
                    // The strip cannot walk backwards during one presentation.
                    events += invalidateTentative(
                        evidence.time,
                        "indicator moved back to slot ${highlighted + 1}"
                    )
                }
            }

            TrackingState.INPUT -> {
                if (slotList.any { it.status == SlotObservation.Status.INFERRED }) {
                    inputSlotIndex = null
                    inputHighlightTime = null
                } else {
                    val highlighted = reading.highlightedIndex
                    if (highlighted != null) {
                        inputSlotIndex = highlighted
                        inputHighlightTime = evidence.time
                    } else {
                        val seen = inputHighlightTime
                        if (seen == null ||
                            evidence.time - seen > policy.indicatorObstructionGrace
                        ) {
                            inputSlotIndex = null
                            inputHighlightTime = null
                        }
                        // Between indicators, not off them: hold the slot.
                    }
                }
            }

            else -> Unit
        }
        return events
    }

    /**
     * Once the last Glyph has been shown the game clears the array and drops the
     * highlight from the last indicator, while the strip stays.
     *
     * Only the last slot, only over a clear array held for the same interval that
     * separates provisional shapes, and only once a shape in it is stable: a
     * highlight that flickers while a Glyph is still being drawn, or drops before
     * one appears, closes nothing.
     */
    private fun releaseLastSlot(
        evidence: FrameEvidence,
        strip: SlotStripReader.Reading
    ): List<TrackingEvent> {
        val open = currentSlotIndex
        if (!policy.closesLastSlotOnRelease || open == null ||
            open != strip.slotCount - 1 || inferredFromIndex != null ||
            evidence.shape?.kind != ShapeKind.NO_STROKE ||
            hypotheses.none { it.isStable(policy) }
        ) {
            lastSlotReleasedSince = null
            return emptyList()
        }
        val since = lastSlotReleasedSince ?: evidence.time
        lastSlotReleasedSince = since
        if (evidence.time - since < policy.minimumBlankInterval) return emptyList()
        lastSlotReleasedSince = null
        return finalizeCurrentSlot(evidence.time, "last indicator released after the array cleared")
    }

    private fun ensureSlots(upTo: Int) {
        if (upTo < 0) return
        while (slotList.size <= upTo) slotList.add(SlotObservation(slotList.size))
    }

    private fun openSlot(index: Int, time: Double): List<TrackingEvent> {
        ensureSlots(index)
        val events = mutableListOf<TrackingEvent>()
        // Joining a presentation already in progress leaves real holes behind.
        for (earlier in 0 until index) {
            if (slotList[earlier].status == SlotObservation.Status.PENDING) {
                slotList[earlier].status = SlotObservation.Status.MISSING
                events.add(
                    TrackingEvent(
                        TrackingEvent.Kind.SLOT_MISSING, time, hackID,
                        slotIndex = earlier, reason = "joined presentation late"
                    )
                )
            }
        }
        if (!slotList[index].isSettled) {
            slotList[index].status = SlotObservation.Status.OBSERVING
        }
        currentSlotIndex = index
        hypotheses.clear()
        lastSlotReleasedSince = null
        return events
    }

    private fun finalizeCurrentSlot(time: Double, reason: String): List<TrackingEvent> {
        val index = currentSlotIndex ?: return emptyList()
        currentSlotIndex = null
        ensureSlots(index)
        if (slotList[index].isSettled) {
            hypotheses.clear()
            return emptyList()
        }

        val stable = hypotheses.filter { it.isStable(policy) }
        val sawSomething = hypotheses.isNotEmpty()
        val events = mutableListOf<TrackingEvent>()

        if (stable.isEmpty()) {
            slotList[index].status = if (sawSomething) {
                SlotObservation.Status.UNCERTAIN
            } else {
                SlotObservation.Status.MISSING
            }
            events.add(
                TrackingEvent(
                    if (sawSomething) TrackingEvent.Kind.SLOT_UNCERTAIN
                    else TrackingEvent.Kind.SLOT_MISSING,
                    time, hackID, slotIndex = index,
                    reason = reason + "; " +
                        if (sawSomething) "no shape held long enough" else "no shape evidence"
                )
            )
            hypotheses.clear()
            return events
        }

        if (stable.size == 1) {
            events += commit(stable[0], index, time, reason)
            hypotheses.clear()
            return events
        }

        // Several stable shapes. If their edge sets form a strict subset chain,
        // the largest is the finished drawing and the others are it part-drawn.
        // Otherwise the slot genuinely held more than one Glyph and is uncertain.
        val bySize = stable.sortedByDescending { it.edges.size }
        val largest = bySize.first()
        val nested = bySize.drop(1).all { candidate ->
            candidate.edges.size < largest.edges.size &&
                largest.edges.containsAll(candidate.edges)
        }

        if (nested) {
            events += commit(largest, index, time, reason + "; nested shapes, took the largest")
        } else {
            slotList[index].status = SlotObservation.Status.UNCERTAIN
            events.add(
                TrackingEvent(
                    TrackingEvent.Kind.SLOT_UNCERTAIN, time, hackID, slotIndex = index,
                    reason = reason + "; ${stable.size} incompatible stable shapes"
                )
            )
        }
        hypotheses.clear()
        return events
    }

    private fun commit(
        hypothesis: Hypothesis,
        index: Int,
        time: Double,
        reason: String
    ): List<TrackingEvent> {
        slotList[index].status = SlotObservation.Status.CONFIRMED
        slotList[index].glyph = hypothesis.glyph
        slotList[index].edges = hypothesis.edges
        return listOf(
            TrackingEvent(
                TrackingEvent.Kind.SLOT_COMMITTED, time, hackID,
                slotIndex = index, glyph = hypothesis.glyph, reason = reason
            )
        )
    }

    /**
     * With the header establishing presentation but the indicators unreadable,
     * blank-separated shapes are retained as inferred entries. Inferred slots
     * never reach the predictor.
     *
     * Reduced from the original: the original tracks `nextInferredIndex` across
     * several consecutive inferred slots. This version opens the next inferred
     * position whenever a stable shape follows a blank interval, which covers the
     * common case and stops cleanly when the indicators return.
     */
    private fun applyFallback(evidence: FrameEvidence): List<TrackingEvent> {
        if (state != TrackingState.PRESENTING) return emptyList()
        if (phaseSource != PhaseSource.HEADER_TEXT) return emptyList()
        if (evidence.slots != null) return emptyList()

        val shapeKind = evidence.shape?.kind ?: return emptyList()
        val events = mutableListOf<TrackingEvent>()

        if (shapeKind == ShapeKind.NO_STROKE) {
            if (blankSince == null) blankSince = evidence.time
            return events
        }

        if (shapeKind != ShapeKind.UNIQUE_VISIBLE_SHAPE) return events

        val blank = blankSince
        blankSince = null

        // Only a blank interval separates one inferred shape from the next.
        val stable = hypotheses.filter { it.isStable(policy) }
        if (stable.isEmpty() || blank == null) return events
        val hypothesis = stable.singleOrNull() ?: return events

        val index = inferredFromIndex ?: currentSlotIndex ?: 0
        ensureSlots(index)

        if (!slotList[index].isSettled) {
            slotList[index].status = SlotObservation.Status.INFERRED
            slotList[index].glyph = hypothesis.glyph
            slotList[index].edges = hypothesis.edges
            events.add(
                TrackingEvent(
                    TrackingEvent.Kind.SLOT_INFERRED, evidence.time, hackID,
                    slotIndex = index, glyph = hypothesis.glyph,
                    reason = "blank-separated shape with unreadable indicators"
                )
            )
            inferredFromIndex = index + 1
        }
        hypotheses.clear()
        return events
    }

    private fun applyShape(evidence: FrameEvidence): List<TrackingEvent> {
        if (state != TrackingState.PRESENTING || isFrozen) return emptyList()
        val shape = evidence.shape ?: return emptyList()

        // Only a shape that matched one template exactly, and only while the
        // phase came from evidence rather than retention.
        val glyph = shape.glyph
        if (shape.kind != ShapeKind.UNIQUE_VISIBLE_SHAPE || glyph == null) return emptyList()
        if (phaseSource == PhaseSource.NONE) return emptyList()

        val existing = hypotheses.firstOrNull { it.glyph == glyph }
        if (existing != null) {
            existing.times.add(evidence.time)
        } else {
            hypotheses.add(Hypothesis(glyph, shape.edges, mutableListOf(evidence.time)))
        }
        return emptyList()
    }

    // -- Invalidation helpers --

    private fun invalidateTentative(time: Double, reason: String): List<TrackingEvent> {
        hypotheses.clear()
        lastSlotReleasedSince = null
        inferredFromIndex = null
        blankSince = null
        return listOf(
            TrackingEvent(
                TrackingEvent.Kind.EVIDENCE_INVALIDATED, time, hackID, reason = reason
            )
        )
    }

    private fun discardRetainedSequence(time: Double, reason: String): List<TrackingEvent> {
        slotList.clear()
        hypotheses.clear()
        slotCountVotes.clear()
        slotCountConflict = false
        currentSlotIndex = null
        inputSlotIndex = null
        inferredFromIndex = null
        blankSince = null
        return listOf(
            TrackingEvent(
                TrackingEvent.Kind.SEQUENCE_FROZEN, time, hackID, reason = reason
            )
        )
    }

    /** Everything a renderer draws, so a change to any of it bumps [revision]. */
    private fun displayState(): List<Any?> = listOf(
        state,
        phaseSource,
        hackID,
        currentSlotIndex,
        inputSlotIndex,
        indicatorsObstructed,
        slotList.map { listOf(it.status, it.glyph, it.edges.size) }
    )
}
