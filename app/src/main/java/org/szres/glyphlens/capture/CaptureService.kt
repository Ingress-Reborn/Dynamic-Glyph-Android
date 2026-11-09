package org.szres.glyphlens.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import org.szres.glyphlens.R
import org.szres.glyphlens.diagnostics.PowerMemoryKind
import org.szres.glyphlens.diagnostics.PowerMeter
import org.szres.glyphlens.diagnostics.PowerMetric
import org.szres.glyphlens.overlay.OverlayController
import org.szres.glyphlens.recognition.CaptureSessionID
import org.szres.glyphlens.recognition.GlyphDisplaySnapshot
import org.szres.glyphlens.recognition.TrackingState
import org.szres.glyphlens.settings.AppPreferences
import org.szres.glyphlens.settings.RecognitionMode
import org.szres.glyphlens.tables.TableController
import org.szres.glyphlens.ui.MainActivity

class CaptureService : Service() {
    enum class SessionState { STOPPED, STANDBY, STARTING, RUNNING, RESULT_READY, STOPPING }

    private lateinit var captureThread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var mainHandler: Handler
    private lateinit var displayManager: DisplayManager
    private lateinit var preferences: AppPreferences
    private lateinit var tables: TableController
    private lateinit var powerConditions: PowerConditions
    private lateinit var preferenceStore: SharedPreferences
    private lateinit var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener
    private lateinit var overlay: OverlayController

    private var captureEngine: GlyphCaptureEngine? = null
    private var sourceMonitor = CaptureSourceMonitor()
    private var frameAdmission = CaptureFrameAdmission()
    private var projectedContentSize: CaptureSize? = null
    private var sessionGeneration = 0L
    private var displayListenerRegistered = false
    private var lastRenderKey: List<Any?>? = null
    private var overlayRefresh: Runnable? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var captureSize: CaptureSize? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopSession(getString(R.string.status_system_stopped))
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (width <= 0 || height <= 0 || virtualDisplay == null) return
            val size = CaptureSize.forContent(width, height, readCaptureSize().densityDpi)
            projectedContentSize = size
            if (size != captureSize) resizeCapture(size)
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY || virtualDisplay == null) return
            if (Build.VERSION.SDK_INT >= 34 && projectedContentSize != null) return
            val size = readCaptureSize()
            if (size != captureSize) resizeCapture(size)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        captureThread = HandlerThread("glyph-capture").apply { start() }
        handler = Handler(captureThread.looper)
        mainHandler = Handler(Looper.getMainLooper())
        displayManager = getSystemService(DisplayManager::class.java)
        preferences = AppPreferences(this)
        PowerMeter.sharedAndroidGauges.attach(this)
        tables = TableController(this).apply { loadStored() }
        powerConditions = PowerConditions(this)
        overlay = OverlayController(
            this,
            onStartRecognition = { handler.post { resumeRecognition() } },
            onClose = { handler.post { pauseRecognition() } },
        )
        preferenceStore = getSharedPreferences("glyph-lens", MODE_PRIVATE)
        preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "display.floatingWindow") {
                if (preferences.showsFloatingWindow) showOverlay() else hideOverlay()
            }
        }
        preferenceStore.registerOnSharedPreferenceChangeListener(preferenceListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                handler.post { startSession(resultCode, data) }
            }
            ACTION_STOP -> handler.post { stopSession(getString(R.string.status_capture_stopped)) }
            ACTION_RESUME_RECOGNITION -> handler.post { resumeRecognition() }
            ACTION_PAUSE_RECOGNITION -> handler.post { pauseRecognition() }
            ACTION_HIDE_OVERLAY -> hideOverlay()
            ACTION_SHOW_OVERLAY -> showOverlay()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        preferenceStore.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        overlay.hide()
        isOverlayVisible = false
        PowerMeter.shared.end()
        handler.post {
            sessionGeneration++
            releaseCapture()
            currentState = SessionState.STOPPED
            publishState()
            captureThread.quitSafely()
        }
        super.onDestroy()
    }

    private fun startSession(resultCode: Int, data: Intent?) {
        if (currentState != SessionState.STOPPED) return
        currentState = SessionState.STARTING
        PowerMeter.shared.reset()
        PowerMeter.shared.end()
        frameCount = 0
        latestSnapshot = null
        sourceMonitor = CaptureSourceMonitor()
        frameAdmission = CaptureFrameAdmission()
        projectedContentSize = null
        sessionGeneration++
        publishState()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
        if (resultCode != Activity.RESULT_OK || data == null) {
            stopSession(getString(R.string.status_start_failed))
            return
        }

        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            projection = manager.getMediaProjection(resultCode, data)
            projection?.registerCallback(projectionCallback, handler)
            val size = readCaptureSize()
            captureSize = size
            // A projection token creates one display. Standby detaches its surface instead of reusing the token.
            virtualDisplay = projection?.createVirtualDisplay(
                "glyph-lens", size.width, size.height, size.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                null, null, handler,
            )
            displayManager.registerDisplayListener(displayListener, handler)
            displayListenerRegistered = true
            currentState = SessionState.STANDBY
            if (preferences.recognitionMode == RecognitionMode.POWER_SAVING) {
                preferences.showsFloatingWindow = true
                updateNotification()
                publishState()
                showOverlay()
            } else {
                resumeRecognition()
            }
        } catch (_: SecurityException) {
            stopSession(getString(R.string.status_start_failed))
        } catch (_: IllegalStateException) {
            stopSession(getString(R.string.status_start_failed))
        }
    }

    private fun resumeRecognition() {
        if (currentState != SessionState.STANDBY && currentState != SessionState.RESULT_READY) return
        val display = virtualDisplay ?: return
        val size = captureSize ?: return
        currentState = SessionState.STARTING
        sessionGeneration++
        sourceMonitor = CaptureSourceMonitor()
        frameAdmission = CaptureFrameAdmission()
        frameCount = 0
        latestSnapshot = null
        lastRenderKey = null
        PowerMeter.shared.reset()
        try {
            val engine = GlyphCaptureEngine(
                tables = tables,
                preferences = preferences,
                sessionID = CaptureSessionID(SystemClock.elapsedRealtimeNanos().toULong()),
                handler = handler,
                meter = PowerMeter.shared,
            )
            captureEngine = engine
            reader = createReader(size)
            display.surface = reader!!.surface
            updateNotification()
            publishSnapshot(engine.latest)
            publishState()
            showOverlay()
        } catch (_: SecurityException) {
            stopSession(getString(R.string.status_start_failed))
        } catch (_: IllegalStateException) {
            stopSession(getString(R.string.status_start_failed))
        }
    }

    private fun pauseRecognition() {
        if (!isRecognizing && currentState != SessionState.RESULT_READY) return
        currentState = SessionState.STANDBY
        sessionGeneration++
        releaseRecognition()
        PowerMeter.shared.end()
        latestSnapshot = null
        lastRenderKey = null
        updateNotification()
        publishState()
        showOverlay()
    }

    private fun createReader(size: CaptureSize): ImageReader =
        ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener(::onImageAvailable, handler)
        }

    private fun onImageAvailable(source: ImageReader) {
        if (source !== reader || !isRecognizing) return
        val roundFinished = source.acquireLatestImage()?.use { image ->
            val arrival = SystemClock.elapsedRealtimeNanos()
            val metadata = sourceMonitor.observe(image.timestamp, arrival)
            frameCount++
            // Accessing Image.planes maps the hardware buffer, so reject frames before this call.
            if (!frameAdmission.accept(image.timestamp, metadata)) {
                PowerMeter.shared.count(PowerMetric.FRAMES_DELIVERED)
                PowerMeter.shared.finish(PowerMetric.SKIPPED_FRAME, arrival)
                return@use false
            }
            val plane = image.planes.single()
            val frame = try {
                val copyStarted = PowerMeter.shared.now()
                FrameConverter.read(
                    image.width, image.height, image.timestamp,
                    plane.pixelStride, plane.rowStride, plane.buffer,
                ).also { PowerMeter.shared.finish(PowerMetric.CAPTURE_COPY, copyStarted) }
            } catch (_: IllegalArgumentException) {
                stopSession(getString(R.string.status_start_failed))
                return@use false
            }
            PowerMeter.shared.noteMemory(PowerMemoryKind.RGBA_FRAME, frame.pixels.size.toLong())
            val captured = CapturedFrame(frame, metadata.status, arrival, metadata.continuityBroken)
            val snapshot = captureEngine?.process(captured, powerConditions.current) ?: return@use false
            publishSnapshot(snapshot, arrival)
            if (currentState == SessionState.STARTING) {
                currentState = SessionState.RUNNING
                publishState()
            }
            // A fresh session can see the previous round's input screen before observing any presentation.
            preferences.stopsAfterRound && snapshot.hackID > 0 && snapshot.isFrozen &&
                snapshot.state == TrackingState.INPUT
        } ?: false
        if (roundFinished) finishRecognitionRound()
    }

    private fun finishRecognitionRound() {
        currentState = SessionState.RESULT_READY
        sessionGeneration++
        releaseRecognition()
        PowerMeter.shared.end()
        lastRenderKey = null
        updateNotification()
        publishState()
        showOverlay()
    }

    private fun resizeCapture(size: CaptureSize) {
        val display = virtualDisplay ?: return
        val oldReader = reader
        if (oldReader == null) {
            display.resize(size.width, size.height, size.densityDpi)
            captureSize = size
            return
        }
        val newReader = createReader(size)
        display.resize(size.width, size.height, size.densityDpi)
        display.surface = newReader.surface
        reader = newReader
        captureSize = size
        frameAdmission = CaptureFrameAdmission()
        oldReader.close()
    }

    @Suppress("DEPRECATION")
    private fun readCaptureSize(): CaptureSize {
        val metrics = DisplayMetrics()
        displayManager.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
        return CaptureSize.forContent(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }

    private fun publishSnapshot(snapshot: GlyphDisplaySnapshot, sourceArrivalNanos: Long? = null) {
        latestSnapshot = snapshot
        val engine = captureEngine ?: return
        val generation = sessionGeneration
        scheduleOverlayRefresh(snapshot, engine, generation)
        val uptime = SystemClock.elapsedRealtimeNanos() / 1_000_000_000.0
        val geometry = engine.overlayGameGeometry
        val key = OverlayDisplayRefresh.renderKey(snapshot, uptime) + geometry
        if (key == lastRenderKey) return
        lastRenderKey = key
        mainHandler.post {
            if (generation != sessionGeneration || captureEngine !== engine) return@post
            val started = PowerMeter.shared.now()
            // A deadline re-render changes display uptime only, never the source evidence time.
            overlay.updateGameGeometry(geometry)
            overlay.render(snapshot, engine.tableForRendering, uptime)
            sourceArrivalNanos?.let { arrival ->
                PowerMeter.shared.record(
                    PowerMetric.DISPLAY_LATENCY,
                    (SystemClock.elapsedRealtimeNanos() - arrival).coerceAtLeast(0),
                )
            }
            PowerMeter.shared.finish(PowerMetric.DISPLAY_UPDATE, started)
        }
    }

    private fun scheduleOverlayRefresh(
        snapshot: GlyphDisplaySnapshot,
        engine: GlyphCaptureEngine,
        generation: Long,
    ) {
        overlayRefresh?.let(handler::removeCallbacks)
        overlayRefresh = null
        if (currentState != SessionState.STARTING && currentState != SessionState.RUNNING) return
        val uptime = SystemClock.elapsedRealtimeNanos() / 1_000_000_000.0
        val delay = OverlayDisplayRefresh.nextDelayMillis(snapshot, uptime) ?: return
        val refresh = object : Runnable {
            override fun run() {
                if (overlayRefresh === this) overlayRefresh = null
                if (generation != sessionGeneration || captureEngine !== engine) return
                if (currentState != SessionState.STARTING && currentState != SessionState.RUNNING) return
                publishSnapshot(snapshot)
            }
        }
        overlayRefresh = refresh
        handler.postDelayed(refresh, delay)
    }

    private fun hideOverlay() {
        mainHandler.post {
            overlay.hide()
            isOverlayVisible = false
            publishState(getString(
                when (currentState) {
                    SessionState.STANDBY -> R.string.status_standby_hidden
                    SessionState.RESULT_READY -> R.string.status_result_ready_hidden
                    else -> R.string.status_overlay_hidden
                },
            ))
        }
    }

    private fun showOverlay() {
        mainHandler.post {
            if (!isRecognizing && currentState != SessionState.STANDBY && currentState != SessionState.RESULT_READY) return@post
            if (!preferences.showsFloatingWindow) {
                overlay.hide()
                isOverlayVisible = false
                publishState()
                return@post
            }
            try {
                when (currentState) {
                    SessionState.STANDBY -> {
                        overlay.updateGameGeometry(null)
                        overlay.showStandby()
                    }
                    SessionState.RESULT_READY -> {
                        val result = latestSnapshot ?: return@post
                        overlay.showResult(result, tables.shapeTable, result.evidenceArrivalUptime)
                    }
                    else -> overlay.show()
                }
                isOverlayVisible = true
                publishState(
                    if (currentState == SessionState.RUNNING) getString(R.string.status_overlay_shown) else null,
                )
            } catch (_: SecurityException) {
                handler.post { stopSession(getString(R.string.status_overlay_required)) }
            }
        }
    }

    private fun stopSession(message: String) {
        if (currentState == SessionState.STOPPED || currentState == SessionState.STOPPING) return
        currentState = SessionState.STOPPING
        sessionGeneration++
        PowerMeter.shared.end()
        publishState()
        releaseCapture()
        mainHandler.post {
            overlay.hide()
            isOverlayVisible = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            currentState = SessionState.STOPPED
            publishState(message)
            stopSelf()
        }
    }

    private fun releaseCapture() {
        releaseRecognition()
        virtualDisplay?.release()
        virtualDisplay = null
        if (displayListenerRegistered) {
            displayManager.unregisterDisplayListener(displayListener)
            displayListenerRegistered = false
        }
        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
        projection = null
        captureSize = null
    }

    private fun releaseRecognition() {
        overlayRefresh?.let(handler::removeCallbacks)
        overlayRefresh = null
        virtualDisplay?.surface = null
        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        reader = null
        captureEngine?.close()
        captureEngine = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_glyph_lens)
            .setContentTitle(getString(
                when (currentState) {
                    SessionState.STANDBY -> R.string.notification_standby_title
                    SessionState.RESULT_READY -> R.string.notification_result_ready_title
                    else -> R.string.notification_title
                },
            ))
            .setContentText(getString(
                when (currentState) {
                    SessionState.STANDBY -> R.string.notification_standby_text
                    SessionState.RESULT_READY -> R.string.notification_result_ready_text
                    else -> R.string.notification_text
                },
            ))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, getString(R.string.notification_stop), stop).build())
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun publishState(message: String? = null) {
        sendBroadcast(
            Intent(ACTION_STATE_CHANGED).setPackage(packageName).putExtra(EXTRA_MESSAGE, message),
        )
    }

    companion object {
        private const val CHANNEL_ID = "screen-capture"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "org.szres.glyphlens.action.START"
        const val ACTION_STOP = "org.szres.glyphlens.action.STOP"
        const val ACTION_RESUME_RECOGNITION = "org.szres.glyphlens.action.RESUME_RECOGNITION"
        const val ACTION_PAUSE_RECOGNITION = "org.szres.glyphlens.action.PAUSE_RECOGNITION"
        const val ACTION_HIDE_OVERLAY = "org.szres.glyphlens.action.HIDE_OVERLAY"
        const val ACTION_SHOW_OVERLAY = "org.szres.glyphlens.action.SHOW_OVERLAY"
        const val ACTION_STATE_CHANGED = "org.szres.glyphlens.action.STATE_CHANGED"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_MESSAGE = "message"

        @Volatile
        var currentState = SessionState.STOPPED
            private set

        val isRecognizing: Boolean
            get() = currentState == SessionState.STARTING || currentState == SessionState.RUNNING

        @Volatile
        var isOverlayVisible = false
            private set

        @Volatile
        var latestSnapshot: GlyphDisplaySnapshot? = null
            private set

        @Volatile
        var frameCount = 0L
            private set
    }
}
