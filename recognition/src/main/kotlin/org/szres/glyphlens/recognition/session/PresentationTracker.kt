package org.szres.glyphlens.recognition.session

import org.szres.glyphlens.recognition.CaptureSessionID
import org.szres.glyphlens.recognition.GlyphID
import org.szres.glyphlens.recognition.GlyphPath
import org.szres.glyphlens.recognition.PhaseSource
import org.szres.glyphlens.recognition.SequenceTable
import org.szres.glyphlens.recognition.SlotObservation
import org.szres.glyphlens.recognition.TrackingEvent
import org.szres.glyphlens.recognition.TrackingState
import org.szres.glyphlens.recognition.image.SlotStripReading
import org.szres.glyphlens.recognition.image.SlotStripRejection
import org.szres.glyphlens.recognition.text.GamePhase
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Turns timestamped frame evidence into a presented sequence.
 *
 * A Glyph is confirmed only by an independently detected boundary. Player and
 * command strokes never become presentation evidence. Inferred observations
 * retained while indicators are covered are display-only and never enter the
 * confirmed prefix used for prediction or guidance.
 */
class PresentationTracker(
    val sessionID: CaptureSessionID,
    val policy: Policy = Policy(),
) {
    data class Policy(
        val minimumStableInterval: Double = 0.045,
        val minimumStableSamples: Int = 2,
        val maximumFrameGap: Double = 1.0,
        val unknownPhaseGrace: Double = 0.75,
        val stalenessThreshold: Double = 1.5,
        val slotCountVotesRequired: Int = 2,
        val indicatorObstructionGrace: Double = 0.35,
        val minimumBlankInterval: Double = 0.045,
        val entersPresentationFromIndicators: Boolean = true,
        val closesLastSlotOnRelease: Boolean = true,
        val minimumStripAbsenceForNewHack: Double = 1.5,
    )

    private data class Hypothesis(
        val glyph: GlyphID,
        val edges: Set<GlyphPath.Edge>,
        val times: MutableList<Double>,
    ) {
        fun isStable(policy: Policy): Boolean =
            times.size >= policy.minimumStableSamples &&
                (times.lastOrNull() ?: 0.0) - (times.firstOrNull() ?: 0.0) >=
                policy.minimumStableInterval
    }

    var state: TrackingState = TrackingState.INACTIVE
        private set
    var phaseSource: PhaseSource = PhaseSource.NONE
        private set
    /** True only when the most recently accepted frame independently proves its phase with pixels. */
    var phaseSupportedByCurrentPixels: Boolean = false
        private set
    var hackID: Int = 0
        private set

    private val slotStorage = mutableListOf<SlotObservation>()

    /** Defensive value copies preserve the snapshot semantics of Swift value types. */
    val slots: List<SlotObservation>
        get() = slotStorage.map { it.copy(alternatives = it.alternatives.toList()) }

    var currentSlotIndex: Int? = null
        private set

    /** Display-only player highlight; never a source of shape evidence. */
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
    var observedPresentationForCurrentHack: Boolean = false
        private set
    private var inputStartedAt: Double? = null

    /** A brief completion title can finish OCR after the input pixels have disappeared. */
    val completionHeaderSince: Double?
        get() = inputStartedAt.takeIf {
            isFrozen && observedPresentationForCurrentHack &&
                (state == TrackingState.INPUT || state == TrackingState.INTERRUPTED)
        }

    var lastIndicatorTime: Double? = null
        private set
    var indicatorsObstructed: Boolean = false
        private set

    private var coveredSince: Double? = null

    private data class DisplayState(
        val state: TrackingState,
        val phaseSource: PhaseSource,
        val hackID: Int,
        val currentSlotIndex: Int?,
        val inputSlotIndex: Int?,
        val indicatorsObstructed: Boolean,
        val slots: List<SlotObservation>,
    )

    private val displayState: DisplayState
        get() = DisplayState(
            state,
            phaseSource,
            hackID,
            currentSlotIndex,
            inputSlotIndex,
            indicatorsObstructed,
            slots,
        )

    private val hypotheses = mutableListOf<Hypothesis>()
    private val slotCountVotes = mutableMapOf<Int, Int>()
    private var inferredFromIndex: Int? = null
    private var nextInferredIndex: Int? = null
    private var blankSince: Double? = null
    private var lastSlotReleasedSince: Double? = null

    private data class IndicatorPrelude(
        val reading: SlotStripReading,
        val firstTime: Double,
        var lastTime: Double,
        var samples: Int,
    )

    private var indicatorPrelude: IndicatorPrelude? = null
    private var inputHighlightTime: Double? = null
    private var indicatorPhaseAnchor: SlotStripReading? = null
    private var freshIndicatorStart = false
    private var lastStripReadingTime: Double? = null
    private var stripReturnedAfterAbsence = false

    /** The independently and repeatedly shown slot count; null while unproven or contradicted. */
    val expectedSlotCount: Int?
        get() {
            if (slotCountConflict) return null
            val qualified = slotCountVotes.filterValues { it >= policy.slotCountVotesRequired }
            return qualified.keys.singleOrNull()
        }

    /** Only the contiguous confirmed run from slot zero may be sent to a predictor. */
    val confirmedPrefix: List<GlyphID>
        get() {
            val prefix = mutableListOf<GlyphID>()
            for (slot in slotStorage) {
                if (slot.status != SlotObservation.Status.CONFIRMED) break
                val glyph = slot.glyph ?: break
                prefix += glyph
            }
            return prefix
        }

    fun accept(evidence: FrameEvidence): List<TrackingEvent> {
        phaseSupportedByCurrentPixels = false
        if (evidence.sessionID != sessionID) {
            return listOf(
                event(
                    TrackingEvent.Kind.STALE_EVIDENCE_REJECTED,
                    evidence.time,
                    reason = "evidence from capture session ${evidence.sessionID}",
                ),
            )
        }
        val events = mutableListOf<TrackingEvent>()

        lastEvidenceTime?.let { last ->
            if (evidence.time < last) {
                events += invalidateTentative(evidence.time, "source time moved backwards")
                state = TrackingState.INTERRUPTED
                phaseSource = PhaseSource.NONE
                lastStripReadingTime = null
                lastPhaseTime = null
                inputStartedAt = null
            } else if (evidence.time - last > policy.maximumFrameGap) {
                events += invalidateTentative(
                    evidence.time,
                    "frame gap of ${secondsDescription(evidence.time - last)}",
                )
                state = TrackingState.INTERRUPTED
                phaseSource = PhaseSource.NONE
            }
        }
        lastEvidenceTime = evidence.time
        if (evidence.slots != null) {
            val previous = lastStripReadingTime
            if (previous == null ||
                evidence.time - previous >= policy.minimumStripAbsenceForNewHack
            ) {
                stripReturnedAfterAbsence = true
            }
            lastStripReadingTime = evidence.time
        }

        val before = displayState
        events += applyPhase(evidence)
        events += applySlots(evidence)
        events += applyFallback(evidence)
        events += applyShape(evidence)
        if (state == TrackingState.PRESENTING) stripReturnedAfterAbsence = false
        // Input-highlight movement is a reading, not a decision, but still requires a redraw.
        if (events.isNotEmpty() || displayState != before) revision += 1
        return events
    }

    /** A new source-clock epoch invalidates phase timing, not committed slots or hack identity. */
    fun invalidatePhase() {
        phaseSupportedByCurrentPixels = false
        lastPhaseTime = null
        inputStartedAt = null
        if (state != TrackingState.INACTIVE) state = TrackingState.INTERRUPTED
        phaseSource = PhaseSource.NONE
        revision += 1
    }

    fun reset() {
        phaseSupportedByCurrentPixels = false
        inputStartedAt = null
        state = TrackingState.INACTIVE
        phaseSource = PhaseSource.NONE
        slotStorage.clear()
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
        nextInferredIndex = null
        blankSince = null
        lastSlotReleasedSince = null
        indicatorPrelude = null
        indicatorPhaseAnchor = null
        freshIndicatorStart = false
        lastStripReadingTime = null
        stripReturnedAfterAbsence = false
        revision += 1
    }

    private fun applyPhase(evidence: FrameEvidence): List<TrackingEvent> {
        val events = mutableListOf<TrackingEvent>()
        var resolved = GamePhase.unknown
        var source = PhaseSource.NONE

        val header = evidence.header
        if (header != null && header.phase != GamePhase.unknown &&
            header.sourceTime <= evidence.time &&
            evidence.time - header.sourceTime <= policy.unknownPhaseGrace
        ) {
            resolved = header.phase
            source = PhaseSource.HEADER_TEXT
            lastPhaseTime = max(
                lastPhaseTime ?: Double.NEGATIVE_INFINITY,
                min(header.sourceTime, evidence.time),
            )
        } else if (indicatorPhaseAnchor == null) {
            val last = lastPhaseTime
            val retained = currentPhase
            if (last != null && last.isFinite() && evidence.time >= last &&
                evidence.time - last <= policy.unknownPhaseGrace && retained != null
            ) {
                resolved = retained
                source = PhaseSource.RETAINED
            }
        }

        val indicatorPresentation = presentationFromIndicators(
            evidence,
            contradictoryHeader = source == PhaseSource.HEADER_TEXT && resolved != GamePhase.presenting,
        )
        phaseSupportedByCurrentPixels = indicatorPresentation
        if (source != PhaseSource.HEADER_TEXT && indicatorPresentation) {
            resolved = GamePhase.presenting
            source = PhaseSource.INDICATOR_TRANSITION
        }

        // Current pixels beat delayed OCR: otherwise player strokes could enter a presentation slot.
        if (evidence.hasInputTimerBar && evidence.hasValidCalibration &&
            observedPresentationForCurrentHack &&
            (state == TrackingState.PRESENTING || state == TrackingState.INPUT ||
                state == TrackingState.INTERRUPTED) &&
            resolved != GamePhase.command && resolved != GamePhase.completed
        ) {
            resolved = GamePhase.input
            source = PhaseSource.INPUT_TIMER
            phaseSupportedByCurrentPixels = true
            lastPhaseTime = evidence.time
        }

        val target = trackingStateFor(resolved)
        if (target == state) {
            phaseSource = source
            return events
        }

        if (state == TrackingState.PRESENTING) {
            if (target == TrackingState.INPUT) {
                val open = currentSlotIndex
                if (source == PhaseSource.INPUT_TIMER && evidence.slots == null && open != null) {
                    inferredFromIndex = min(inferredFromIndex ?: open, open)
                }
                events += finalizeCurrentSlot(
                    evidence.time,
                    "presentation ended, player input began",
                )
            } else {
                events += invalidateTentative(evidence.time, "left presentation for ${stateName(target)}")
            }
        }

        if (target == TrackingState.PRESENTING) {
            val freshStart = source == PhaseSource.INDICATOR_TRANSITION && freshIndicatorStart &&
                stripReturnedAfterAbsence
            if (shouldStartNewHack(evidence.slots, freshStart)) {
                events += startHack(evidence.time)
            } else {
                events += event(
                    TrackingEvent.Kind.PHASE_CHANGED,
                    evidence.time,
                    reason = "resumed presentation after ${stateName(state)}",
                )
            }
        }

        if (target == TrackingState.COMMAND) {
            hypotheses.clear()
            currentSlotIndex = null
            observedPresentationForCurrentHack = false
            events += discardRetainedSequence(evidence.time, "command channel opened")
        }

        if (target == TrackingState.INPUT && !observedPresentationForCurrentHack) {
            events += discardRetainedSequence(
                evidence.time,
                "input began but no presentation was observed",
            )
        }

        if (target == TrackingState.INPUT && !isFrozen) {
            inputStartedAt = evidence.time.takeIf { observedPresentationForCurrentHack }
            isFrozen = true
            events += event(
                TrackingEvent.Kind.SEQUENCE_FROZEN,
                evidence.time,
                reason = "player input began; presented sequence retained",
            )
        }

        if (target == TrackingState.COMPLETED) {
            observedPresentationForCurrentHack = false
            isFrozen = true
            events += event(
                TrackingEvent.Kind.HACK_COMPLETED,
                evidence.time,
                reason = "completion screen",
            )
        }

        events += event(
            TrackingEvent.Kind.PHASE_CHANGED,
            evidence.time,
            reason = "${stateName(state)} -> ${stateName(target)} via ${sourceName(source)}",
        )
        state = target
        phaseSource = source
        if (target != TrackingState.INPUT) {
            inputSlotIndex = null
            inputHighlightTime = null
        }
        return events
    }

    private fun shouldStartNewHack(reading: SlotStripReading?, freshStart: Boolean): Boolean {
        if (!(state == TrackingState.INTERRUPTED && hackID > 0 && !isFrozen)) return true
        if (freshStart) return true
        val expected = expectedSlotCount
        if (reading != null && expected != null && reading.slotCount != expected) return true
        return false
    }

    private fun presentationFromIndicators(
        evidence: FrameEvidence,
        contradictoryHeader: Boolean,
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

        fun agrees(other: SlotStripReading): Boolean =
            reading.slotCount == other.slotCount &&
                reading.markerCentersX.size == other.markerCentersX.size &&
                reading.markerCentersX.zip(other.markerCentersX).all { (a, b) -> abs(a - b) <= 0.02 }

        val anchor = indicatorPhaseAnchor
        if (anchor != null) {
            if (state == TrackingState.PRESENTING && !slotCountConflict && agrees(anchor)) {
                val selected = reading.highlightedIndex
                if (selected != null && selected >= (anchor.highlightedIndex ?: 0)) {
                    indicatorPhaseAnchor = reading
                    return true
                }
                // Released last indicator is the presentation tail, not a new prelude.
                if (selected == null && anchor.highlightedIndex == reading.slotCount - 1) return true
            }
            indicatorPhaseAnchor = null
        }

        if (reading.highlightedIndex == null && evidence.shape.kind == ShapeEvidence.Kind.NO_STROKE) {
            val existing = indicatorPrelude
            if (existing != null && agrees(existing.reading) && evidence.time > existing.lastTime &&
                evidence.time - existing.lastTime <= policy.maximumFrameGap
            ) {
                existing.samples += 1
                existing.lastTime = evidence.time
            } else {
                indicatorPrelude = IndicatorPrelude(reading, evidence.time, evidence.time, 1)
            }
            return false
        }

        // Swift's defer clears the prelude after this transition has been evaluated.
        val prelude = indicatorPrelude
        indicatorPrelude = null
        if (reading.highlightedIndex != 0 || prelude == null || !agrees(prelude.reading) ||
            prelude.samples < policy.minimumStableSamples ||
            prelude.lastTime - prelude.firstTime < policy.minimumStableInterval ||
            evidence.time < prelude.lastTime ||
            evidence.time - prelude.lastTime > policy.maximumFrameGap
        ) {
            return false
        }
        indicatorPhaseAnchor = reading
        freshIndicatorStart = true
        return true
    }

    private val currentPhase: GamePhase?
        get() = when (state) {
            TrackingState.COMMAND -> GamePhase.command
            TrackingState.PRESENTING -> GamePhase.presenting
            TrackingState.INPUT -> GamePhase.input
            TrackingState.COMPLETED -> GamePhase.completed
            TrackingState.INACTIVE, TrackingState.INTERRUPTED -> null
        }

    private fun trackingStateFor(phase: GamePhase): TrackingState = when (phase) {
        GamePhase.unknown -> if (state == TrackingState.INACTIVE) TrackingState.INACTIVE else TrackingState.INTERRUPTED
        GamePhase.command -> TrackingState.COMMAND
        GamePhase.presenting -> TrackingState.PRESENTING
        GamePhase.input -> TrackingState.INPUT
        GamePhase.completed -> TrackingState.COMPLETED
    }

    private fun startHack(time: Double): List<TrackingEvent> {
        hackID += 1
        inputStartedAt = null
        slotStorage.clear()
        hypotheses.clear()
        slotCountVotes.clear()
        slotCountConflict = false
        currentSlotIndex = null
        inputSlotIndex = null
        inputHighlightTime = null
        inferredFromIndex = null
        nextInferredIndex = null
        blankSince = null
        lastIndicatorTime = null
        indicatorsObstructed = false
        coveredSince = null
        isFrozen = false
        observedPresentationForCurrentHack = true
        return listOf(
            event(
                TrackingEvent.Kind.HACK_STARTED,
                time,
                reason = "presentation screen after ${stateName(state)}",
            ),
        )
    }

    private fun applySlots(evidence: FrameEvidence): List<TrackingEvent> {
        if (state == TrackingState.PRESENTING || state == TrackingState.INPUT) {
            if (evidence.slots != null) {
                lastIndicatorTime = evidence.time
                indicatorsObstructed = false
            } else if (lastIndicatorTime == null ||
                evidence.time - (lastIndicatorTime ?: evidence.time) > policy.indicatorObstructionGrace
            ) {
                indicatorsObstructed = true
            }
        } else if (evidence.hasValidCalibration &&
            evidence.slotRejection == SlotStripRejection.CoveredByOverlay
        ) {
            val arrayInView = evidence.shape.kind == ShapeEvidence.Kind.NO_STROKE ||
                evidence.shape.kind == ShapeEvidence.Kind.UNIQUE_VISIBLE_SHAPE
            if (coveredSince == null && arrayInView) coveredSince = evidence.time
            indicatorsObstructed = coveredSince?.let {
                evidence.time - it >= policy.indicatorObstructionGrace
            } ?: false
        } else {
            coveredSince = null
            indicatorsObstructed = false
        }

        val reading = evidence.slots ?: return emptyList()
        val events = mutableListOf<TrackingEvent>()
        if (state == TrackingState.PRESENTING || state == TrackingState.INPUT) {
            slotCountVotes[reading.slotCount] = (slotCountVotes[reading.slotCount] ?: 0) + 1
            val qualified = slotCountVotes.filterValues { it >= policy.slotCountVotesRequired }
            if (qualified.size > 1 && !slotCountConflict) {
                slotCountConflict = true
                events += invalidateTentative(evidence.time, "slot count changed mid-hack")
                events += event(
                    TrackingEvent.Kind.SLOT_COUNT_CONFLICT,
                    evidence.time,
                    reason = "counts ${qualified.keys.sorted().joinToString("/")}",
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
                val inferred = inferredFromIndex
                if (inferred != null) {
                    for (index in inferred until slotStorage.size) {
                        slotStorage[index] = SlotObservation(index)
                    }
                    if (slotStorage.size > reading.slotCount) {
                        slotStorage.subList(reading.slotCount, slotStorage.size).clear()
                    }
                    currentSlotIndex = null
                    hypotheses.clear()
                    inferredFromIndex = null
                    nextInferredIndex = null
                    blankSince = null
                    events += event(
                        TrackingEvent.Kind.EVIDENCE_INVALIDATED,
                        evidence.time,
                        reason = "indicators restored; inferred positions discarded",
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
                        "indicator advanced to slot ${highlighted + 1}",
                    )
                    for (skipped in (open + 1) until highlighted) {
                        ensureSlots(skipped)
                        slotStorage[skipped].status = SlotObservation.Status.MISSING
                        slotStorage[skipped].boundaryReason = "indicator skipped this slot"
                        events += event(
                            TrackingEvent.Kind.SLOT_MISSING,
                            evidence.time,
                            slotIndex = skipped,
                            reason = "indicator skipped this slot",
                        )
                    }
                    events += openSlot(highlighted, evidence.time)
                    events += event(
                        TrackingEvent.Kind.SLOT_ADVANCED,
                        evidence.time,
                        slotIndex = highlighted,
                        reason = "verified indicator transition",
                    )
                } else if (highlighted < open) {
                    events += invalidateTentative(
                        evidence.time,
                        "indicator moved back to slot ${highlighted + 1}",
                    )
                }
            }
            TrackingState.INPUT -> {
                if (slotStorage.any { it.status == SlotObservation.Status.INFERRED }) {
                    inputSlotIndex = null
                    inputHighlightTime = null
                } else {
                    val highlighted = reading.highlightedIndex
                    val seen = inputHighlightTime
                    if (highlighted != null) {
                        inputSlotIndex = highlighted
                        inputHighlightTime = evidence.time
                    } else if (seen != null && evidence.time >= seen &&
                        evidence.time - seen <= policy.indicatorObstructionGrace
                    ) {
                        // Between indicators: hold the current display slot.
                    } else {
                        inputSlotIndex = null
                        inputHighlightTime = null
                    }
                }
            }
            else -> Unit
        }
        return events
    }

    private fun releaseLastSlot(
        evidence: FrameEvidence,
        strip: SlotStripReading,
    ): List<TrackingEvent> {
        val open = currentSlotIndex
        if (!policy.closesLastSlotOnRelease || open == null || open != strip.slotCount - 1 ||
            inferredFromIndex != null || evidence.shape.kind != ShapeEvidence.Kind.NO_STROKE ||
            hypotheses.none { it.isStable(policy) }
        ) {
            lastSlotReleasedSince = null
            return emptyList()
        }
        val since = lastSlotReleasedSince ?: evidence.time
        lastSlotReleasedSince = since
        if (evidence.time - since < policy.minimumBlankInterval) return emptyList()
        lastSlotReleasedSince = null
        return finalizeCurrentSlot(
            evidence.time,
            "last indicator released after the array cleared",
        )
    }

    private fun ensureSlots(upTo: Int) {
        if (upTo < 0) return
        while (slotStorage.size <= upTo) slotStorage += SlotObservation(slotStorage.size)
    }

    private fun openSlot(index: Int, time: Double): List<TrackingEvent> {
        ensureSlots(index)
        val events = mutableListOf<TrackingEvent>()
        for (earlier in 0 until index) {
            if (slotStorage[earlier].status == SlotObservation.Status.PENDING) {
                slotStorage[earlier].status = SlotObservation.Status.MISSING
                slotStorage[earlier].boundaryReason = "presentation was already past this slot"
                events += event(
                    TrackingEvent.Kind.SLOT_MISSING,
                    time,
                    slotIndex = earlier,
                    reason = "joined presentation late",
                )
            }
        }
        if (!slotStorage[index].isSettled) {
            slotStorage[index].status = SlotObservation.Status.OBSERVING
            slotStorage[index].boundaryReason = null
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
        if (slotStorage[index].isSettled) {
            hypotheses.clear()
            return emptyList()
        }

        val stable = hypotheses.filter { it.isStable(policy) }
        slotStorage[index].boundaryReason = reason
        if (stable.isEmpty()) {
            val sawSomething = hypotheses.isNotEmpty()
            slotStorage[index].status = if (sawSomething) {
                SlotObservation.Status.UNCERTAIN
            } else {
                SlotObservation.Status.MISSING
            }
            val sampleCount = hypotheses.sumOf { it.times.size }
            if (sawSomething) {
                slotStorage[index].alternatives = hypotheses.map { it.glyph }.sorted()
            }
            hypotheses.clear()
            return listOf(
                if (sawSomething) {
                    event(
                        TrackingEvent.Kind.SLOT_UNCERTAIN,
                        time,
                        slotIndex = index,
                        reason = "$reason; no shape held long enough",
                        sampleCount = sampleCount,
                    )
                } else {
                    event(
                        TrackingEvent.Kind.SLOT_MISSING,
                        time,
                        slotIndex = index,
                        reason = "$reason; no shape evidence",
                    )
                },
            )
        }

        if (stable.size == 1) {
            val result = commit(stable.single(), index, time, reason, emptyList())
            hypotheses.clear()
            return result
        }

        val ordered = stable.sortedBy { it.edges.size }
        val nested = ordered.zip(ordered.drop(1)).all { (smaller, larger) ->
            smaller.edges.size < larger.edges.size && larger.edges.containsAll(smaller.edges)
        }
        if (!nested) {
            slotStorage[index].status = SlotObservation.Status.UNCERTAIN
            slotStorage[index].alternatives = stable.map { it.glyph }.sorted()
            val sampleCount = stable.sumOf { it.times.size }
            hypotheses.clear()
            return listOf(
                event(
                    TrackingEvent.Kind.SLOT_UNCERTAIN,
                    time,
                    slotIndex = index,
                    reason = "$reason; ${stable.size} unrelated stable shapes",
                    sampleCount = sampleCount,
                ),
            )
        }
        val result = commit(
            ordered.last(),
            index,
            time,
            "$reason; grew through ${ordered.size} nested shapes",
            ordered.dropLast(1).map { it.glyph },
        )
        hypotheses.clear()
        return result
    }

    private fun commit(
        hypothesis: Hypothesis,
        index: Int,
        time: Double,
        reason: String,
        alternatives: List<GlyphID>,
    ): List<TrackingEvent> {
        val inferred = inferredFromIndex?.let { index >= it } == true
        val slot = slotStorage[index]
        slot.status = if (inferred) SlotObservation.Status.INFERRED else SlotObservation.Status.CONFIRMED
        slot.glyph = hypothesis.glyph
        slot.alternatives = alternatives
        slot.firstSampleTime = hypothesis.times.firstOrNull()
        slot.lastSampleTime = hypothesis.times.lastOrNull()
        slot.sampleCount = hypothesis.times.size
        slot.boundaryReason = reason
        return listOf(
            event(
                if (inferred) TrackingEvent.Kind.SLOT_INFERRED else TrackingEvent.Kind.SLOT_COMMITTED,
                time,
                slotIndex = index,
                glyph = hypothesis.glyph,
                reason = reason,
                sampleCount = hypothesis.times.size,
            ),
        )
    }

    private fun applyFallback(evidence: FrameEvidence): List<TrackingEvent> {
        if (state != TrackingState.PRESENTING || isFrozen || !evidence.hasValidCalibration ||
            evidence.slots != null
        ) {
            blankSince = null
            return emptyList()
        }
        // Retained phase is not enough: it could now be command entry or player input.
        if (phaseSource != PhaseSource.HEADER_TEXT) {
            blankSince = null
            return emptyList()
        }
        if (inferredFromIndex == null) {
            val first = currentSlotIndex
                ?: slotStorage.indexOfFirst { !it.isSettled }.takeIf { it >= 0 }
                ?: slotStorage.size
            inferredFromIndex = first
            nextInferredIndex = first
        }
        if (evidence.shape.kind == ShapeEvidence.Kind.NO_STROKE) {
            if (blankSince == null) blankSince = evidence.time
            val since = blankSince
            val index = currentSlotIndex
            if (since != null && evidence.time - since >= policy.minimumBlankInterval &&
                index != null && hypotheses.isNotEmpty()
            ) {
                val events = finalizeCurrentSlot(
                    evidence.time,
                    "clear array during presentation; position unverified",
                )
                nextInferredIndex = index + 1
                return events
            }
            return emptyList()
        }
        blankSince = null
        val index = nextInferredIndex
        if (evidence.shape.kind == ShapeEvidence.Kind.UNIQUE_VISIBLE_SHAPE &&
            currentSlotIndex == null && index != null &&
            index < (expectedSlotCount ?: SequenceTable.maximumLength)
        ) {
            return openSlot(index, evidence.time)
        }
        return emptyList()
    }

    private fun applyShape(evidence: FrameEvidence): List<TrackingEvent> {
        val index = currentSlotIndex
        if (state != TrackingState.PRESENTING || phaseSource == PhaseSource.NONE || index == null ||
            !evidence.hasValidCalibration || evidence.shape.kind != ShapeEvidence.Kind.UNIQUE_VISIBLE_SHAPE
        ) {
            return emptyList()
        }
        val glyph = evidence.shape.glyph ?: return emptyList()
        if (slotStorage[index].isSettled) return emptyList()

        val existing = hypotheses.indexOfFirst { it.glyph == glyph }
        if (existing >= 0) {
            val last = hypotheses[existing].times.lastOrNull()
            if (last != null && evidence.time <= last) return emptyList()
            hypotheses[existing].times += evidence.time
        } else {
            hypotheses += Hypothesis(glyph, evidence.shape.edges.toSet(), mutableListOf(evidence.time))
        }
        slotStorage[index].status = SlotObservation.Status.OBSERVING
        if (slotStorage[index].firstSampleTime == null) slotStorage[index].firstSampleTime = evidence.time
        slotStorage[index].lastSampleTime = evidence.time
        return emptyList()
    }

    private fun discardRetainedSequence(time: Double, reason: String): List<TrackingEvent> {
        inputStartedAt = null
        if (slotStorage.isEmpty() && slotCountVotes.isEmpty()) return emptyList()
        val discarded = slotStorage.mapNotNull { it.glyph }.joinToString(",") { it.name }
        slotStorage.clear()
        hypotheses.clear()
        slotCountVotes.clear()
        slotCountConflict = false
        currentSlotIndex = null
        inputSlotIndex = null
        inputHighlightTime = null
        isFrozen = false
        inferredFromIndex = null
        nextInferredIndex = null
        blankSince = null
        return listOf(
            event(
                TrackingEvent.Kind.EVIDENCE_INVALIDATED,
                time,
                reason = if (discarded.isEmpty()) reason else "$reason; discarded $discarded",
            ),
        )
    }

    private fun invalidateTentative(time: Double, reason: String): List<TrackingEvent> {
        indicatorPrelude = null
        indicatorPhaseAnchor = null
        blankSince = null
        nextInferredIndex = null
        if (hypotheses.isEmpty() && currentSlotIndex == null) return emptyList()
        hypotheses.clear()
        val index = currentSlotIndex
        if (index != null && !slotStorage[index].isSettled) {
            slotStorage[index].status = SlotObservation.Status.PENDING
            slotStorage[index].firstSampleTime = null
            slotStorage[index].lastSampleTime = null
        }
        currentSlotIndex = null
        return listOf(event(TrackingEvent.Kind.EVIDENCE_INVALIDATED, time, reason = reason))
    }

    private fun event(
        kind: TrackingEvent.Kind,
        time: Double,
        slotIndex: Int? = null,
        glyph: GlyphID? = null,
        reason: String,
        sampleCount: Int = 0,
    ): TrackingEvent = TrackingEvent(
        kind = kind,
        time = time,
        hackID = hackID,
        slotIndex = slotIndex,
        glyph = glyph,
        reason = reason,
        sampleCount = sampleCount,
    )

    private fun stateName(state: TrackingState): String = when (state) {
        TrackingState.INACTIVE -> "inactive"
        TrackingState.COMMAND -> "command"
        TrackingState.PRESENTING -> "presenting"
        TrackingState.INPUT -> "input"
        TrackingState.COMPLETED -> "completed"
        TrackingState.INTERRUPTED -> "interrupted"
    }

    private fun sourceName(source: PhaseSource): String = when (source) {
        PhaseSource.HEADER_TEXT -> "headerText"
        PhaseSource.RETAINED -> "retained"
        PhaseSource.INPUT_TIMER -> "inputTimer"
        PhaseSource.INDICATOR_TRANSITION -> "indicatorTransition"
        PhaseSource.NONE -> "none"
    }

    private fun secondsDescription(value: Double): String {
        if (!value.isFinite()) return "$value s"
        val milliseconds = round(value * 1000.0).toInt()
        val sign = if (milliseconds < 0) "-" else ""
        val magnitude = abs(milliseconds)
        return "$sign${magnitude / 1000}.${(magnitude % 1000).toString().padStart(3, '0')} s"
    }
}
