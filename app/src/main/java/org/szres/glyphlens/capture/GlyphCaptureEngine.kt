package org.szres.glyphlens.capture

import android.os.Handler
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
import org.szres.glyphlens.recognition.image.GameUILayoutLocator
import org.szres.glyphlens.recognition.image.GlyphFrameObservation
import org.szres.glyphlens.recognition.image.GlyphFrameSession
import org.szres.glyphlens.recognition.image.LumaGate
import org.szres.glyphlens.recognition.image.SlotStripReader
import org.szres.glyphlens.recognition.image.toImageFrame
import org.szres.glyphlens.recognition.session.FrameEvidence
import org.szres.glyphlens.recognition.session.PresentationSnapshotFactory
import org.szres.glyphlens.recognition.session.PresentationTracker
import org.szres.glyphlens.settings.AppPreferences
import org.szres.glyphlens.tables.TableController

class GlyphCaptureEngine internal constructor(
    private val shapeTable: GlyphTable?,
    sequenceTable: SequenceTable?,
    sessionID: CaptureSessionID,
    private val header: HeaderOcrProcessor,
    private val powerSaving: () -> Boolean,
    private val meter: PowerMeter = PowerMeter(),
    private val uptimeNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) : AutoCloseable {
    constructor(
        tables: TableController,
        preferences: AppPreferences,
        sessionID: CaptureSessionID,
        handler: Handler,
        meter: PowerMeter = PowerMeter.shared,
    ) : this(
        shapeTable = tables.shapeTable,
        sequenceTable = tables.sequenceTable,
        sessionID = sessionID,
        header = HeaderOcrProcessor(handler, preferences.makeGameLanguageMemory(), meter),
        powerSaving = { true },
        meter = meter,
    )

    private val frameSession = shapeTable?.let(::GlyphFrameSession)
    private val tracker = PresentationTracker(sessionID)
    private val snapshots = PresentationSnapshotFactory(sequenceTable?.let(::SequencePredictor))
    private val pace = AnalysisRateController()
    private val gate = LumaGate()
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
    val diagnostics: CaptureDiagnostics get() = header

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
        if (closed) return latest
        meter.count(PowerMetric.FRAMES_DELIVERED)
        val intakeStarted = meter.now()
        var analysisStarted: Long? = null
        try {
            if (!acceptCapture(captured)) return latest
            val frame = captured.rgba
            val at = captured.sourceTimeSeconds
            val savesPower = powerSaving()
            val inHack = isInHack(at)
            val constrained = savesPower && power.isConstrained
            val decision = if (savesPower) {
                pace.decide(at, gate.read(frame), busy = sawActivity, inHack = inHack, constrained = constrained)
            } else {
                AnalysisRateController.Decision(AnalysisRateController.Mode.ACTIVE, pace.policy.activeInterval)
            }
            meter.noteAnalysisMode(
                mode = decision.mode,
                at = at,
                current = PowerReadingMode(decision.mode, decision.interval, savesPower, inHack),
            )
            if (at >= lastSample && at - lastSample < decision.interval) return latest
            lastSample = at
            val started = meter.now().also { analysisStarted = it }
            meter.count(PowerMetric.FRAMES_ANALYSED)
            meter.record(PowerMetric.FRAME_INTAKE, (started - intakeStarted).coerceAtLeast(0))

            val conversionStarted = meter.now()
            val image = frame.toImageFrame(fullColor = true)
            val color = image.topBand.image
            meter.finish(PowerMetric.CONVERSION, conversionStarted)
            val observation = if (decision.mode == AnalysisRateController.Mode.QUIET) {
                null
            } else {
                frameSession?.let { session ->
                    val step = meter.now()
                    val result = session.observe(image)
                    val total = (meter.now() - step).coerceAtLeast(0)
                    val hunt = session.lastGridHuntNanoseconds?.coerceIn(0, total)
                    if (hunt != null && session.validatedAt == at &&
                        result?.state == GlyphFrameObservation.State.NO_STROKE && result.calibration != null
                    ) {
                        fitFreshnessIsCurrentEpoch = true
                    }
                    if (hunt != null) meter.record(PowerMetric.GRID_HUNT, hunt)
                    meter.record(PowerMetric.GLYPH_READ, total - (hunt ?: 0))
                    result
                }
            }
            val slotStarted = meter.now()
            if (frameSession != null) {
                val layout = layoutLocator.observe(color, observation?.calibration, at)
                if (layout != slotReader.layout) slotReader = SlotStripReader(layout)
            }
            val slotResult = if (frameSession == null) null else layoutLocator.lastSlotResult
            val hasInputTimer = frameSession != null &&
                (slotResult?.getOrNull()?.hasInputTimerBar ?: slotReader.hasTimerBar(image))
            if (frameSession != null) meter.finish(PowerMetric.SLOT_STRIP, slotStarted)
            val layoutConsistent = observation?.calibration?.let(slotReader.layout::isConsistent) == true
            // A cached fit can position the overlay after it stops proving the current screen is active.
            val freshFit = fitFreshnessIsCurrentEpoch && layoutConsistent &&
                frameSession?.validatedAt?.let { at >= it && at - it <= 1.0 } == true
            val hasGlyphActivity = slotResult?.getOrNull() != null || hasInputTimer || freshFit
            sawActivity = savesPower && hasGlyphActivity

            tracker.accept(
                FrameEvidence.fromAnalysis(
                    sessionID = tracker.sessionID,
                    time = at,
                    header = header.evidence.reading(at, tracker.sessionID),
                    observation = observation,
                    slotResult = slotResult,
                    hasInputTimerBar = hasInputTimer,
                    layout = slotReader.layout,
                ),
            )
            val headerInterval = if (savesPower) {
                AnalysisRateController.headerInterval(decision.mode, inHack, constrained)
            } else {
                null
            }
            header.submit(
                frame = frame,
                image = image,
                color = color,
                session = tracker.sessionID,
                readsHeader = frameSession != null && (!savesPower || headerInterval != null),
                inHack = inHack,
                constrained = constrained,
                headerInterval = headerInterval,
                requiresFreshHeader = hasGlyphActivity,
                layout = slotReader.layout,
            )
            latest = snapshot(observation, at)
            return latest
        } finally {
            val started = analysisStarted
            if (started == null) {
                meter.finish(PowerMetric.SKIPPED_FRAME, intakeStarted)
            } else {
                meter.finish(PowerMetric.FRAME_WORK, started)
            }
        }
    }

    private fun acceptCapture(captured: CapturedFrame): Boolean {
        val at = captured.sourceTimeSeconds
        if (captured.continuityBroken || at < lastPresentation) {
            layoutLocator.reset()
            fitFreshnessIsCurrentEpoch = false
            sawActivity = false
            header.evidence.invalidatePhase()
            tracker.invalidatePhase()
        }
        lastPresentation = at
        // A repeated source time does not provide new pixels or fresh phase evidence.
        if (captured.status == CaptureFrameStatus.IDLE) return false
        if (captured.status != CaptureFrameStatus.COMPLETE) {
            frameSession?.reset()
            sawActivity = false
            latest = snapshot(
                observation = null,
                sourceTime = at,
                liveness = CaptureLiveness.INTERRUPTED,
                acceptance = AcceptanceMode.ProvisionalOnly("capture ${captured.status.name.lowercase()}"),
            )
            return false
        }
        if (captured.rgba.width >= captured.rgba.height) {
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
    ): GlyphDisplaySnapshot = snapshots.snapshot(
        tracker = tracker,
        observation = observation,
        arrivalUptime = uptimeNanos() / 1_000_000_000.0,
        sourceTime = sourceTime,
        liveness = liveness,
        acceptance = acceptance,
    )

    private fun isInHack(at: Double): Boolean {
        val phaseAge = at - (tracker.lastPhaseTime ?: Double.NEGATIVE_INFINITY)
        return tracker.state == TrackingState.PRESENTING || tracker.state == TrackingState.INPUT ||
            tracker.state == TrackingState.COMMAND ||
            (tracker.state == TrackingState.INTERRUPTED && phaseAge >= 0.0 && phaseAge < 5.0)
    }

    override fun close() {
        if (closed) return
        closed = true
        header.close()
    }
}
