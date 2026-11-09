package com.dynamicglyph.capture

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.dynamicglyph.recognition.AnalysisRateController
import com.dynamicglyph.recognition.CaptureSessionID
import com.dynamicglyph.recognition.FrameEvidence
import com.dynamicglyph.recognition.GameUILayout
import com.dynamicglyph.recognition.GlyphDisplaySnapshot
import com.dynamicglyph.recognition.GlyphFrameRecognizer
import com.dynamicglyph.recognition.GlyphFrameSession
import com.dynamicglyph.recognition.GlyphTable
import com.dynamicglyph.recognition.HeaderReading
import com.dynamicglyph.recognition.LumaGate
import com.dynamicglyph.recognition.PresentationTracker
import com.dynamicglyph.recognition.SequencePredictor
import com.dynamicglyph.recognition.SequenceTable
import com.dynamicglyph.recognition.ShapeEvidence
import com.dynamicglyph.recognition.ShapeKind
import com.dynamicglyph.recognition.SlotObservation
import com.dynamicglyph.recognition.SlotStripReader
import com.dynamicglyph.recognition.TrackingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Screen capture and the per-frame recognition pipeline.
 *
 * Replaces the iOS capture stack: MediaProjection stands in for
 * ScreenCaptureKit/ReplayKit, and because everything runs in one process there
 * is no shared-memory ring, no Darwin notifications and no broadcast extension.
 *
 * Per frame, in the original's order:
 * 1. Brightness gate — is this frame worth analysing at all?
 * 2. Rate controller — how often to analyse.
 * 3. Glyph observation against the cached calibration.
 * 4. Slot strip and countdown bar, from the colour band.
 * 5. Header text, from OCR submitted a frame or two earlier.
 * 6. Tracker, then the display snapshot.
 */
class CaptureEngine(
    private val context: Context,
    private val onSnapshot: (GlyphDisplaySnapshot) -> Unit
) {

    private companion object {
        const val TAG = "CaptureEngine"
        const val VIRTUAL_DISPLAY_NAME = "DynamicGlyph"
        const val MAX_FRAME_WIDTH = 720
        const val HEADER_INTERVAL_IN_HACK = 0.1
        const val HEADER_INTERVAL_IDLE = 0.5

        /**
         * How old a header reading may be and still describe the current frame.
         * The original allows 0.35 s; OCR here is submitted at 10 Hz inside a
         * hack, so a slightly looser bound keeps a reading usable across the
         * gap between submissions without ever presenting stale text as current.
         */
        const val MAX_HEADER_AGE = 0.45
    }

    private val layout = GameUILayout.ENGLISH_PORTRAIT
    private val lumaGate = LumaGate()
    private val rateController = AnalysisRateController()
    private val slotReader = SlotStripReader(layout)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var headerRecognizer: HeaderTextRecognizer? = null

    private var predictor: SequencePredictor? = null
    private var frameSession: GlyphFrameSession? = null
    private var tracker: PresentationTracker? = null

    /** Monotonic session counter, so a stale in-flight OCR result can be dropped. */
    private var sessionCounter = 0L

    @Volatile
    private var capturing = false

    // -- Header OCR state: written from the OCR coroutine, read from the capture thread --

    @Volatile
    private var latestHeader: HeaderReading? = null

    @Volatile
    private var latestHeaderTime = 0.0

    @Volatile
    private var ocrInFlight = false
    private var lastOcrSubmit = Double.NEGATIVE_INFINITY
    private var ocrGeneration = 0

    val isCapturing: Boolean get() = capturing

    /**
     * Load the shape and sequence tables from CSV text.
     *
     * Both are required. Without a shape table there is nothing to recognise;
     * without a sequence table the display says prediction is unavailable rather
     * than inventing an answer.
     */
    fun loadTables(glyphCsv: String, sequenceCsv: String) {
        val glyphs = GlyphTable.parse(glyphCsv)
        val sequences = SequenceTable.parse(sequenceCsv)
        predictor = SequencePredictor(sequences)
        frameSession = GlyphFrameSession(glyphs)
        Log.i(TAG, "Tables loaded: ${glyphs.count} glyphs, ${sequences.count} sequences")
    }

    fun hasTables(): Boolean = frameSession != null

    /**
     * Start capturing from a MediaProjection consent result.
     *
     * Returns `null` on success, or a plain-language reason for failure. The
     * caller shows that reason in the overlay: a silent `false` left the user
     * staring at a screen with nothing on it and no way to tell what went wrong.
     */
    fun start(resultCode: Int, data: Intent): String? {
        if (capturing) return null
        if (frameSession == null) {
            Log.e(TAG, "Refusing to start without a shape table")
            return "没有加载图形表，请先在 App 里加载两个 CSV"
        }

        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager
        val mediaProjection = try {
            manager.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed", e)
            return "截屏授权失效：${e.javaClass.simpleName}\n请重新点「开始截屏」授权"
        } ?: return "系统没有授予截屏权限\n请重新点「开始截屏」并选「立即开始」"

        val metrics = DisplayMetrics()
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        var width = metrics.widthPixels
        var height = metrics.heightPixels
        if (width > MAX_FRAME_WIDTH) {
            val scale = MAX_FRAME_WIDTH.toDouble() / width
            width = (width * scale).toInt()
            height = (height * scale).toInt()
        }

        val thread = HandlerThread("GlyphCapture").also { it.start() }
        val handler = Handler(thread.looper)

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ source ->
            val image = try {
                source.acquireLatestImage()
            } catch (e: IllegalStateException) {
                null
            }
            if (image != null) {
                try {
                    processFrame(image)
                } catch (e: Exception) {
                    Log.e(TAG, "Frame processing failed", e)
                } finally {
                    image.close()
                }
            }
        }, handler)

        val display = try {
            mediaProjection.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            )
        } catch (e: Exception) {
            Log.e(TAG, "createVirtualDisplay failed", e)
            reader.close()
            thread.quitSafely()
            mediaProjection.stop()
            return "无法开始录屏：${e.javaClass.simpleName}\n${e.message ?: ""}"
        }
        if (display == null) {
            // HyperOS and some OEM builds refuse the virtual display without
            // throwing. Treating that as success produced a live service with no
            // frames and an overlay nobody could see.
            Log.e(TAG, "createVirtualDisplay returned null")
            reader.close()
            thread.quitSafely()
            mediaProjection.stop()
            return "系统拒绝了录屏（虚拟显示器为空）\n请检查电池权限与后台弹出界面权限"
        }

        projection = mediaProjection
        virtualDisplay = display
        imageReader = reader
        captureThread = thread
        captureHandler = handler
        headerRecognizer = HeaderTextRecognizer(layout)

        sessionCounter++
        tracker = PresentationTracker(CaptureSessionID(sessionCounter))
        rateController.reset()
        frameSession?.reset()
        latestHeader = null
        latestHeaderTime = 0.0
        lastOcrSubmit = Double.NEGATIVE_INFINITY
        ocrGeneration++

        capturing = true
        Log.i(TAG, "Capture started at ${width}x$height")
        return null
    }

    fun stop() {
        capturing = false
        ocrGeneration++
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projection?.stop()
        projection = null
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        headerRecognizer?.close()
        headerRecognizer = null
        tracker = null
        frameSession?.reset()
        latestHeader = null
        ocrInFlight = false
        Log.i(TAG, "Capture stopped")
    }

    /** Release everything, including the coroutine scope. */
    fun release() {
        stop()
        scope.cancel()
    }

    // -- Per frame --

    private fun processFrame(image: Image) {
        val session = frameSession ?: return
        val activeTracker = tracker ?: return

        val plane = image.planes[0]
        val width = image.width
        val height = image.height
        val argb = ImageDecoder.argbFromPlane(
            plane.buffer, width, height, plane.pixelStride, plane.rowStride
        )

        // Monotonic seconds. The tracker compares these across frames and treats
        // a backwards step as an interrupted session.
        val time = SystemClock.elapsedRealtimeNanos() / 1_000_000_000.0

        val luma = lumaGate.read(width, height) { x, y ->
            LumaGate.lumaOfArgb(argb[y * width + x])
        }

        val inHack = activeTracker.state == TrackingState.PRESENTING ||
            activeTracker.state == TrackingState.INPUT

        val decision = rateController.decide(time, luma, inHack, isConstrained())
        if (decision == null) return

        submitHeaderOcr(argb, width, height, time, inHack)

        val luminance = ImageDecoder.luminanceFromArgb(argb, width, height)
        val observation = session.observe(luminance, time)

        val band = ImageDecoder.topBandFromArgb(argb, width, height, layout)
        val slotResult = slotReader.read(band)
        // Read the bar independently: it is the strongest phase evidence, and a
        // strip rejection must not hide it.
        val hasTimerBar = slotReader.hasTimerBar(band)

        val reading = (slotResult as? SlotStripReader.Result.Success)?.reading
        val rejection = (slotResult as? SlotStripReader.Result.Failure)?.rejection

        val evidence = FrameEvidence(
            sessionID = activeTracker.sessionID,
            time = time,
            header = freshHeader(time),
            slots = reading,
            slotRejection = rejection,
            shape = observation?.let(::toShapeEvidence),
            hasValidCalibration = observation?.calibration != null,
            hasInputTimerBar = hasTimerBar
        )

        activeTracker.accept(evidence)
        publish(activeTracker, time)
    }

    private fun toShapeEvidence(observation: GlyphFrameRecognizer.Observation): ShapeEvidence {
        val kind = when (observation.state) {
            GlyphFrameRecognizer.State.UNIQUE_VISIBLE_SHAPE -> ShapeKind.UNIQUE_VISIBLE_SHAPE
            GlyphFrameRecognizer.State.NO_STROKE -> ShapeKind.NO_STROKE
            GlyphFrameRecognizer.State.AMBIGUOUS_SHAPE -> ShapeKind.AMBIGUOUS_SHAPE
            GlyphFrameRecognizer.State.UNRECOGNIZED_STROKE -> ShapeKind.UNRECOGNIZED_STROKE
            GlyphFrameRecognizer.State.UNEXPLAINED_INK -> ShapeKind.UNEXPLAINED_INK
            GlyphFrameRecognizer.State.CALIBRATION_UNAVAILABLE -> ShapeKind.CALIBRATION_UNAVAILABLE
            GlyphFrameRecognizer.State.CALIBRATION_MISMATCH -> ShapeKind.CALIBRATION_MISMATCH
        }
        val glyph = if (kind == ShapeKind.UNIQUE_VISIBLE_SHAPE) {
            observation.exactMatches.singleOrNull()
        } else {
            null
        }
        return ShapeEvidence(kind, glyph, observation.edges, observation.extensions)
    }

    // -- Header OCR --

    private fun submitHeaderOcr(
        argb: IntArray,
        width: Int,
        height: Int,
        time: Double,
        inHack: Boolean
    ) {
        val recognizer = headerRecognizer ?: return
        if (ocrInFlight) return

        val interval = if (inHack) HEADER_INTERVAL_IN_HACK else HEADER_INTERVAL_IDLE
        if (time - lastOcrSubmit < interval) return
        lastOcrSubmit = time

        val top = (layout.headerTextTop * height).toInt().coerceIn(0, height - 1)
        val bottom = (layout.headerTextBottom * height).toInt().coerceIn(top + 1, height)
        val rows = bottom - top
        if (rows <= 0) return

        val band = ImageDecoder.bitmapRows(argb, width, height, top, rows)
        val generation = ocrGeneration
        ocrInFlight = true

        scope.launch {
            try {
                val reading = recognizer.readBand(band)
                // Drop results from a session that has since stopped or restarted.
                if (generation == ocrGeneration) {
                    // Stamp the frame time, not the arrival time: the tracker
                    // measures its grace period from the frame the text came from.
                    latestHeader = reading.copy(sourceTime = time)
                    latestHeaderTime = time
                }
            } catch (e: Exception) {
                Log.w(TAG, "Header OCR failed", e)
            } finally {
                band.recycle()
                ocrInFlight = false
            }
        }
    }

    /**
     * The most recent header reading, if it is fresh enough to describe this
     * frame. Text recognition runs behind the sampler, so a stale reading is
     * worse than none: the tracker would extend its grace period on old news.
     */
    private fun freshHeader(time: Double): HeaderReading? {
        val reading = latestHeader ?: return null
        return if (time - latestHeaderTime <= MAX_HEADER_AGE) reading else null
    }

    // -- Display --

    private fun publish(activeTracker: PresentationTracker, time: Double) {
        val prefix = activeTracker.confirmedPrefix
        val prediction = predictor?.predict(prefix)
        val provisional = activeTracker.provisionalGlyph

        val slots = activeTracker.slots.map { slot ->
            GlyphDisplaySnapshot.SlotDisplay(
                index = slot.index,
                status = slot.status,
                glyph = slot.glyph,
                edges = slot.edges
            )
        }

        val status = displayStatus(activeTracker)
        onSnapshot(
            GlyphDisplaySnapshot(
                sessionID = activeTracker.sessionID,
                hackID = activeTracker.hackID,
                revision = activeTracker.revision,
                slots = slots,
                expectedSlotCount = activeTracker.expectedSlotCount,
                provisionalGlyph = provisional,
                prediction = prediction,
                status = status,
                statusLine = statusLine(status, activeTracker),
                captureLiveness = GlyphDisplaySnapshot.CaptureLiveness.LIVE,
                uptime = time
            )
        )
    }

    /**
     * Status by the original's priority: stopped, stale, obstructed, inferred,
     * provisional, conflicted, then the tracker's own phase.
     */
    private fun displayStatus(activeTracker: PresentationTracker): GlyphDisplaySnapshot.DisplayStatus {
        if (!capturing) return GlyphDisplaySnapshot.DisplayStatus.STOPPED
        if (activeTracker.state == TrackingState.INTERRUPTED) {
            return GlyphDisplaySnapshot.DisplayStatus.STALE
        }
        if (activeTracker.indicatorsObstructed) {
            return GlyphDisplaySnapshot.DisplayStatus.OBSTRUCTED
        }
        if (activeTracker.slots.any { it.status == SlotObservation.Status.INFERRED }) {
            return GlyphDisplaySnapshot.DisplayStatus.INFERRED
        }
        if (activeTracker.provisionalGlyph != null) {
            return GlyphDisplaySnapshot.DisplayStatus.PROVISIONAL
        }
        if (activeTracker.slotCountConflict) {
            return GlyphDisplaySnapshot.DisplayStatus.CONFLICTED
        }
        return when (activeTracker.state) {
            TrackingState.PRESENTING -> GlyphDisplaySnapshot.DisplayStatus.COLLECTING
            TrackingState.INPUT -> GlyphDisplaySnapshot.DisplayStatus.YOUR_TURN
            TrackingState.COMPLETED -> GlyphDisplaySnapshot.DisplayStatus.COMPLETE
            TrackingState.COMMAND, TrackingState.INACTIVE ->
                GlyphDisplaySnapshot.DisplayStatus.WAITING
            TrackingState.INTERRUPTED -> GlyphDisplaySnapshot.DisplayStatus.STALE
        }
    }

    private fun statusLine(
        status: GlyphDisplaySnapshot.DisplayStatus,
        activeTracker: PresentationTracker
    ): String = when (status) {
        GlyphDisplaySnapshot.DisplayStatus.STOPPED -> "Stopped"
        GlyphDisplaySnapshot.DisplayStatus.STALE -> "Screen unreadable"
        GlyphDisplaySnapshot.DisplayStatus.OBSTRUCTED -> "Indicators covered"
        GlyphDisplaySnapshot.DisplayStatus.INFERRED -> "Inferred — indicators covered"
        GlyphDisplaySnapshot.DisplayStatus.PROVISIONAL -> "Reading…"
        GlyphDisplaySnapshot.DisplayStatus.CONFLICTED -> "Slot count changed"
        GlyphDisplaySnapshot.DisplayStatus.WAITING -> "Waiting for a glyph hack"
        GlyphDisplaySnapshot.DisplayStatus.COLLECTING -> {
            val expected = activeTracker.expectedSlotCount
            if (expected != null) {
                "Collecting ${activeTracker.confirmedPrefix.size}/$expected"
            } else {
                "Collecting…"
            }
        }
        GlyphDisplaySnapshot.DisplayStatus.YOUR_TURN -> {
            val expected = activeTracker.expectedSlotCount
            val index = activeTracker.inputSlotIndex
            when {
                index != null && expected != null -> "Your turn — slot ${index + 1} of $expected"
                index != null -> "Your turn — slot ${index + 1}"
                else -> "Your turn"
            }
        }
        GlyphDisplaySnapshot.DisplayStatus.COMPLETE -> "Hack complete"
        GlyphDisplaySnapshot.DisplayStatus.UNKNOWN_PHASE -> "Reading…"
    }

    /**
     * Low power mode or a hot device halves the analysis rate outside a hack.
     * Inside one, the blank between Glyphs is short enough to need full rate.
     */
    private fun isConstrained(): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        if (power.isPowerSaveMode) return true
        return power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
    }
}
