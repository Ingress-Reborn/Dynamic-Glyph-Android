package org.szres.glyphlens.capture

import android.os.SystemClock
import org.szres.glyphlens.diagnostics.PowerMeter
import org.szres.glyphlens.diagnostics.PowerMetric
import org.szres.glyphlens.diagnostics.PowerReadingMode
import org.szres.glyphlens.overlay.OverlayGameGeometry
import org.szres.glyphlens.recognition.AcceptanceMode
import org.szres.glyphlens.recognition.CaptureLiveness
import org.szres.glyphlens.recognition.CaptureSessionID
import org.szres.glyphlens.recognition.GlyphDisplaySnapshot
import org.szres.glyphlens.recognition.GlyphTable
import org.szres.glyphlens.recognition.RgbaFrame
import org.szres.glyphlens.recognition.SequencePredictor
import org.szres.glyphlens.recognition.SequenceTable
import org.szres.glyphlens.recognition.TrackingState
import org.szres.glyphlens.recognition.image.AnalysisRateController
import org.szres.glyphlens.recognition.image.DeviceCalibration
import org.szres.glyphlens.recognition.image.GameUILayoutLocator
import org.szres.glyphlens.recognition.image.GlyphFrameObservation
import org.szres.glyphlens.recognition.image.GlyphFrameSession
import org.szres.glyphlens.recognition.image.LumaGate
import org.szres.glyphlens.recognition.image.LumaGateReading
import org.szres.glyphlens.recognition.image.MapControlReader
import org.szres.glyphlens.recognition.image.NodeArrayDetection
import org.szres.glyphlens.recognition.image.SlotStripReader
import org.szres.glyphlens.recognition.image.toImageFrame
import org.szres.glyphlens.recognition.session.FrameEvidence
import org.szres.glyphlens.recognition.session.PresentationSnapshotFactory
import org.szres.glyphlens.recognition.session.PresentationTracker
import org.szres.glyphlens.tables.TableController

class GlyphCaptureEngine internal constructor(
    private val shapeTable: GlyphTable?,
    sequenceTable: SequenceTable?,
    sessionID: CaptureSessionID,
    private val powerSaving: () -> Boolean,
    private val meter: PowerMeter = PowerMeter(),
    private val uptimeNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
    initialCalibration: DeviceCalibration? = null,
    onCalibration: (DeviceCalibration) -> Unit = {},
) : AutoCloseable {
    constructor(
        tables: TableController,
        sessionID: CaptureSessionID,
        meter: PowerMeter = PowerMeter.shared,
        initialCalibration: DeviceCalibration? = null,
        onCalibration: (DeviceCalibration) -> Unit = {},
    ) : this(
        shapeTable = tables.shapeTable,
        sequenceTable = tables.sequenceTable,
        sessionID = sessionID,
        powerSaving = { true },
        meter = meter,
        initialCalibration = initialCalibration,
        onCalibration = onCalibration,
    )

    private val frameSession = shapeTable?.let { GlyphFrameSession(it, initialCalibration, onCalibration) }
    private val tracker = PresentationTracker(sessionID)
    private val snapshots = PresentationSnapshotFactory(sequenceTable?.let(::SequencePredictor))
    private val pace = AnalysisRateController()
    private val gate = LumaGate()
    private val mapControls = MapControlReader()
    private var hasMapControl = false
    private val layoutLocator = GameUILayoutLocator()
    private var slotReader = SlotStripReader()
    private var lastSample = Double.NEGATIVE_INFINITY
    private var lastPresentation = Double.NEGATIVE_INFINITY
    private var lastLooked = Double.NEGATIVE_INFINITY
    private var sawActivity = false
    private var fitFreshnessIsCurrentEpoch = true
    private var closed = false

    var latest = GlyphDisplaySnapshot.waiting(
        sessionID = sessionID,
        liveness = CaptureLiveness.STARTING,
        acceptance = AcceptanceMode.TrackingSequence,
    )
        private set

    val tableForRendering = shapeTable

    internal val overlayGameGeometry: OverlayGameGeometry?
        get() = frameSession?.calibration?.let { fit ->
            OverlayGameGeometry(
                arrayTopFraction = fit.origin.y / fit.imageHeight,
                timerBottomFraction = slotReader.layout.inputTimerBarBottom.takeIf { layoutLocator.hasLocatedTimer },
                timerBottomEstimateFraction = slotReader.layout.inputTimerBarBottom.takeIf {
                    layoutLocator.lastSlotResult.isSuccess
                },
            )
        }

    fun process(frame: RgbaFrame, power: PowerConditions.State): GlyphDisplaySnapshot = process(
        CapturedFrame(frame, arrivalUptimeNanos = uptimeNanos()),
        power,
    )

    fun process(captured: CapturedFrame, power: PowerConditions.State): GlyphDisplaySnapshot {
        val frame = captured.rgba
        val wanted = admit(
            sourceTimeNanos = frame.timestampNanos,
            status = captured.status,
            continuityBroken = captured.continuityBroken,
            width = frame.width,
            height = frame.height,
            power = power,
        ) { gate.read(frame) }
        return if (wanted) analyse(frame) else latest
    }

    /** A capture whose pixels are still in the source buffer and are copied only on request. */
    internal interface FramePixels {
        /** Every [rowStep]th row from [firstRow], packed in order as RGBA. */
        fun rows(firstRow: Int, rowStep: Int): ByteArray

        /** The whole frame. */
        fun frame(): RgbaFrame
    }

    /**
     * As [process] of the copied frame, but a frame the pace rejects costs at most the rows the
     * luminance gate samples, and none at all during a hack.
     */
    internal fun process(
        pixels: FramePixels,
        sourceTimeNanos: Long,
        width: Int,
        height: Int,
        status: CaptureFrameStatus,
        continuityBroken: Boolean,
        power: PowerConditions.State,
    ): GlyphDisplaySnapshot {
        val wanted = admit(sourceTimeNanos, status, continuityBroken, width, height, power) {
            gate.readSampledRows(pixels.rows(gate.firstSampledRow, gate.step), width, height)
        }
        return if (wanted) analyse(pixels.frame()) else latest
    }

    /**
     * Decides from a frame's metadata whether its pixels will be analysed, so a caller can leave
     * the rest of a rejected frame uncopied. [luma] is asked only when the pace depends on the
     * screen, and must describe this frame exactly as [LumaGate.read] would. A true result must
     * be followed by [analyse] with the same frame; otherwise [latest] is already current.
     */
    internal fun admit(
        sourceTimeNanos: Long,
        status: CaptureFrameStatus,
        continuityBroken: Boolean,
        width: Int,
        height: Int,
        power: PowerConditions.State,
        luma: () -> LumaGateReading?,
    ): Boolean {
        admitted = null
        if (closed) return false
        meter.count(PowerMetric.FRAMES_DELIVERED)
        val intakeStarted = meter.now()
        var accepted = false
        try {
            val at = sourceTimeNanos / 1_000_000_000.0
            if (!acceptCapture(at, status, continuityBroken, width, height)) return false
            val savesPower = powerSaving()
            val inHack = !hasMapControl && isInHack(at)
            val constrained = savesPower && power.isConstrained
            val decision = if (savesPower) {
                pace.decide(at, if (inHack) null else luma(), busy = sawActivity, inHack = inHack, constrained = constrained)
            } else {
                AnalysisRateController.Decision(AnalysisRateController.Mode.ACTIVE, pace.policy.activeInterval)
            }
            meter.noteAnalysisMode(
                mode = decision.mode,
                at = at,
                current = PowerReadingMode(decision.mode, decision.interval, savesPower, inHack),
            )
            if (at >= lastSample && at - lastSample < decision.interval) return false
            lastSample = at
            meter.record(PowerMetric.FRAME_INTAKE, (meter.now() - intakeStarted).coerceAtLeast(0))
            admitted = Admission(sourceTimeNanos, width, height, decision, savesPower)
            accepted = true
            return true
        } finally {
            if (!accepted) meter.finish(PowerMetric.SKIPPED_FRAME, intakeStarted)
        }
    }

    private class Admission(
        val sourceTimeNanos: Long,
        val width: Int,
        val height: Int,
        val decision: AnalysisRateController.Decision,
        val savesPower: Boolean,
    )

    private var admitted: Admission? = null

    /** Analyses the pixels of the frame [admit] just accepted. */
    internal fun analyse(frame: RgbaFrame): GlyphDisplaySnapshot {
        val admission = admitted
        admitted = null
        check(
            admission != null && admission.sourceTimeNanos == frame.timestampNanos &&
                admission.width == frame.width && admission.height == frame.height,
        ) { "analyse requires the frame that admit accepted" }
        val at = frame.timestampNanos / 1_000_000_000.0
        val decision = admission.decision
        val savesPower = admission.savesPower
        val started = meter.now()
        try {
            meter.count(PowerMetric.FRAMES_ANALYSED)

            val conversionStarted = meter.now()
            val image = frame.toImageFrame(fullColor = true)
            val color = image.topBand.image
            meter.finish(PowerMetric.CONVERSION, conversionStarted)
            hasMapControl = mapControls.read(color, at) != null
            if (hasMapControl) {
                tracker.observeMap(at)
                sawActivity = false
                latest = snapshot(observation = null, sourceTime = at)
                return latest
            }
            val retainedFit = frameSession?.retainCalibration(image.luminance, at)
            val slotStarted = meter.now()
            if (frameSession != null) {
                val layout = layoutLocator.observe(color, retainedFit, at)
                if (layout != slotReader.layout) slotReader = SlotStripReader(layout)
            }
            var slotResult = if (frameSession == null) null else layoutLocator.lastSlotResult
            var hasInputTimer = frameSession != null &&
                (slotResult?.getOrNull()?.hasInputTimerBar ?: slotReader.hasTimerBar(image))
            if (frameSession != null) meter.finish(PowerMetric.SLOT_STRIP, slotStarted)
            val inputOnly = tracker.state == TrackingState.INPUT && tracker.isFrozen && hasInputTimer &&
                retainedFit?.let(slotReader.layout::isConsistent) == true
            val observation = if (decision.mode == AnalysisRateController.Mode.QUIET || inputOnly) {
                null
            } else {
                frameSession?.let { session ->
                    val step = meter.now()
                    val result = session.observe(image)
                    val total = (meter.now() - step).coerceAtLeast(0)
                    val hunt = session.lastGridHuntNanoseconds?.coerceIn(0, total)
                    if (session.validatedAt == at &&
                        result?.state == GlyphFrameObservation.State.NO_STROKE && result.calibration != null
                    ) {
                        fitFreshnessIsCurrentEpoch = true
                    }
                    if (hunt != null) meter.record(PowerMetric.GRID_HUNT, hunt)
                    meter.record(PowerMetric.GLYPH_READ, total - (hunt ?: 0))
                    result
                }
            }
            val fit = if (inputOnly) retainedFit else observation?.calibration
            if (frameSession != null && fit != retainedFit) {
                val relocatedAt = meter.now()
                val layout = layoutLocator.observe(color, fit, at)
                if (layout != slotReader.layout) slotReader = SlotStripReader(layout)
                slotResult = layoutLocator.lastSlotResult
                hasInputTimer = slotResult.getOrNull()?.hasInputTimerBar ?: slotReader.hasTimerBar(image)
                meter.finish(PowerMetric.SLOT_STRIP, relocatedAt)
            }
            val layoutConsistent = fit?.let(slotReader.layout::isConsistent) == true
            // A cached fit can position the overlay after it stops proving the current screen is active.
            val freshFit = fitFreshnessIsCurrentEpoch && layoutConsistent &&
                frameSession?.validatedAt?.let { at >= it && at - it <= 1.0 } == true
            val hasGlyphActivity = slotResult?.getOrNull() != null || hasInputTimer || freshFit
            sawActivity = savesPower && hasGlyphActivity

            tracker.accept(
                FrameEvidence.fromAnalysis(
                    sessionID = tracker.sessionID,
                    time = at,
                    observation = observation,
                    slotResult = slotResult,
                    hasInputTimerBar = hasInputTimer,
                    layout = slotReader.layout,
                    calibration = fit,
                ),
            )
            latest = snapshot(observation, at, calibration = fit)
            return latest
        } finally {
            meter.finish(PowerMetric.FRAME_WORK, started)
        }
    }

    private fun acceptCapture(
        at: Double,
        status: CaptureFrameStatus,
        continuityBroken: Boolean,
        width: Int,
        height: Int,
    ): Boolean {
        if (continuityBroken || at < lastPresentation) {
            mapControls.reset()
            hasMapControl = false
            layoutLocator.reset()
            fitFreshnessIsCurrentEpoch = false
            sawActivity = false
            tracker.invalidatePhase()
        }
        lastPresentation = at
        // A repeated source time does not provide new pixels or fresh phase evidence.
        if (status == CaptureFrameStatus.IDLE) return false
        if (status != CaptureFrameStatus.COMPLETE) {
            mapControls.reset()
            hasMapControl = false
            frameSession?.reset()
            sawActivity = false
            latest = snapshot(
                observation = null,
                sourceTime = at,
                liveness = CaptureLiveness.INTERRUPTED,
                acceptance = AcceptanceMode.ProvisionalOnly("capture ${status.name.lowercase()}"),
            )
            return false
        }
        if (width >= height) {
            mapControls.reset()
            hasMapControl = false
            frameSession?.reset()
            sawActivity = false
            tracker.reset()
            latest = snapshot(
                observation = null,
                sourceTime = at,
                acceptance = AcceptanceMode.ProvisionalOnly("portrait game screen required"),
            )
            return false
        }
        if (at >= lastLooked && at - lastLooked < 0.049) return false
        lastLooked = at
        return true
    }

    private fun snapshot(
        observation: GlyphFrameObservation?,
        sourceTime: Double,
        liveness: CaptureLiveness = CaptureLiveness.LIVE,
        acceptance: AcceptanceMode = AcceptanceMode.TrackingSequence,
        calibration: NodeArrayDetection? = observation?.calibration,
    ): GlyphDisplaySnapshot = snapshots.snapshot(
        tracker = tracker,
        observation = observation,
        arrivalUptime = uptimeNanos() / 1_000_000_000.0,
        sourceTime = sourceTime,
        liveness = liveness,
        acceptance = acceptance,
        calibration = calibration,
        hasMapControl = hasMapControl,
    )

    private fun isInHack(at: Double): Boolean {
        val phaseAge = at - (tracker.lastPhaseTime ?: Double.NEGATIVE_INFINITY)
        return tracker.state == TrackingState.PRESENTING || tracker.state == TrackingState.INPUT ||
            tracker.state == TrackingState.COMMAND ||
            (tracker.state == TrackingState.INTERRUPTED && phaseAge >= 0.0 && phaseAge < 5.0)
    }

    fun resetCalibration() {
        frameSession?.resetCalibration()
        layoutLocator.reset()
        slotReader = SlotStripReader()
        tracker.reset()
        sawActivity = false
        fitFreshnessIsCurrentEpoch = false
        latest = GlyphDisplaySnapshot.waiting(
            sessionID = tracker.sessionID,
            liveness = CaptureLiveness.LIVE,
            acceptance = AcceptanceMode.TrackingSequence,
        )
    }

    override fun close() {
        if (closed) return
        closed = true
    }
}
