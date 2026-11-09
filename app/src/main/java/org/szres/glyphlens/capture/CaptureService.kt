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
import org.szres.glyphlens.settings.AppPreferences
import org.szres.glyphlens.tables.TableController
import org.szres.glyphlens.ui.MainActivity

class CaptureService : Service() {
    enum class SessionState { STOPPED, STARTING, RUNNING, STOPPING }

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
    private val overlayVisibility = RealtimeOverlayVisibility()
    private var hidesAfterDrawing = true

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
        hidesAfterDrawing = preferences.hidesAfterDrawing
        PowerMeter.sharedAndroidGauges.attach(this)
        tables = TableController(this).apply { loadStored() }
        powerConditions = PowerConditions(this)
        overlay = OverlayController(
            this,
            onClose = { handler.post { hideOverlay() } },
        )
        preferenceStore = getSharedPreferences("glyph-lens", MODE_PRIVATE)
        preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "display.floatingWindow") {
                handler.post {
                    if (preferences.showsFloatingWindow) showOverlay() else hideOverlay()
                }
            } else if (key == AppPreferences.AUTO_HIDE_KEY) {
                handler.post {
                    hidesAfterDrawing = preferences.hidesAfterDrawing
                    latestSnapshot?.let(::publishSnapshot)
                }
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
            ACTION_HIDE_OVERLAY -> handler.post { hideOverlay() }
            ACTION_SHOW_OVERLAY -> handler.post { showOverlay() }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        preferenceStore.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        overlayVisibility.hide(null)
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
        hidesAfterDrawing = preferences.hidesAfterDrawing
        currentState = SessionState.STARTING
        PowerMeter.shared.reset()
        frameCount = 0
        latestSnapshot = null
        lastRenderKey = null
        overlayVisibility.reset()
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
            val engine = GlyphCaptureEngine(
                tables = tables,
                sessionID = CaptureSessionID(SystemClock.elapsedRealtimeNanos().toULong()),
                meter = PowerMeter.shared,
            )
            captureEngine = engine
            reader = createReader(size)
            virtualDisplay = projection?.createVirtualDisplay(
                "glyph-lens", size.width, size.height, size.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader!!.surface, null, handler,
            )
            displayManager.registerDisplayListener(displayListener, handler)
            displayListenerRegistered = true
            updateNotification()
            publishSnapshot(engine.latest)
            publishState()
            hideOverlay()
        } catch (_: SecurityException) {
            stopSession(getString(R.string.status_start_failed))
        } catch (_: IllegalStateException) {
            stopSession(getString(R.string.status_start_failed))
        }
    }

    private fun createReader(size: CaptureSize): ImageReader =
        ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener(::onImageAvailable, handler)
        }

    private fun onImageAvailable(source: ImageReader) {
        if (source !== reader || !isRecognizing) return
        source.acquireLatestImage()?.use { image ->
            val arrival = SystemClock.elapsedRealtimeNanos()
            val metadata = sourceMonitor.observe(image.timestamp, arrival)
            frameCount++
            // Accessing Image.planes maps the hardware buffer, so reject frames before this call.
            if (!frameAdmission.accept(image.timestamp, metadata)) {
                PowerMeter.shared.count(PowerMetric.FRAMES_DELIVERED)
                PowerMeter.shared.finish(PowerMetric.SKIPPED_FRAME, arrival)
                return@use
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
                return@use
            }
            PowerMeter.shared.noteMemory(PowerMemoryKind.RGBA_FRAME, frame.pixels.size.toLong())
            val captured = CapturedFrame(frame, metadata.status, arrival, metadata.continuityBroken)
            val snapshot = captureEngine?.process(captured, powerConditions.current) ?: return@use
            publishSnapshot(snapshot, arrival)
            if (currentState == SessionState.STARTING) {
                currentState = SessionState.RUNNING
                publishState()
            }
        }
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
        val uptime = SystemClock.elapsedRealtimeNanos() / 1_000_000_000.0
        if (preferences.showsFloatingWindow &&
            overlayVisibility.observe(snapshot, uptime, hidesAfterDrawing)
        ) {
            if (overlayVisibility.wantsWindow) showOverlay(automatically = true) else removeOverlay()
            return
        }
        if (!overlayVisibility.wantsWindow) return
        val generation = sessionGeneration
        scheduleOverlayRefresh(snapshot, engine, generation)
        val geometry = engine.overlayGameGeometry
        val key = OverlayDisplayRefresh.renderKey(snapshot, uptime) + geometry
        if (key == lastRenderKey) return
        lastRenderKey = key
        mainHandler.post {
            if (generation != sessionGeneration || captureEngine !== engine || !overlayVisibility.wantsWindow) return@post
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
        if (!overlayVisibility.wantsWindow || !isRecognizing) return
        val uptime = SystemClock.elapsedRealtimeNanos() / 1_000_000_000.0
        val delay = OverlayDisplayRefresh.nextDelayMillis(snapshot, uptime) ?: return
        val refresh = object : Runnable {
            override fun run() {
                if (overlayRefresh === this) overlayRefresh = null
                if (generation != sessionGeneration || captureEngine !== engine) return
                if (!overlayVisibility.wantsWindow || !isRecognizing) return
                publishSnapshot(snapshot)
            }
        }
        overlayRefresh = refresh
        handler.postDelayed(refresh, delay)
    }

    private fun hideOverlay() {
        overlayVisibility.hide(latestSnapshot)
        removeOverlay()
    }

    private fun removeOverlay() {
        overlayRefresh?.let(handler::removeCallbacks)
        overlayRefresh = null
        lastRenderKey = null
        mainHandler.post {
            if (overlayVisibility.wantsWindow) return@post
            overlay.hide()
            isOverlayVisible = false
            updateNotification()
            publishState(getString(
                when (currentState) {
                    SessionState.RUNNING, SessionState.STARTING ->
                        if (preferences.showsFloatingWindow) {
                            R.string.status_running_hidden
                        } else R.string.status_overlay_hidden
                    else -> R.string.status_overlay_hidden
                },
            ))
        }
    }

    private fun showOverlay(automatically: Boolean = false) {
        if (!isRecognizing) return
        if (!preferences.showsFloatingWindow) {
            hideOverlay()
            return
        }
        overlayVisibility.show()
        lastRenderKey = null
        val generation = sessionGeneration
        val snapshot = latestSnapshot
        val engine = captureEngine
        val geometry = engine?.overlayGameGeometry
        if (engine != null && snapshot != null) scheduleOverlayRefresh(snapshot, engine, generation)
        mainHandler.post {
            if (generation != sessionGeneration || !overlayVisibility.wantsWindow) return@post
            if (!isRecognizing) return@post
            try {
                if (automatically) overlay.resetAutomaticPlacement()
                overlay.updateGameGeometry(geometry)
                snapshot?.let {
                    overlay.render(it, tables.shapeTable, SystemClock.elapsedRealtimeNanos() / 1_000_000_000.0)
                }
                overlay.show()
                isOverlayVisible = true
                updateNotification()
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
        overlayVisibility.hide(null)
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
        val show = PendingIntent.getService(
            this, 2, Intent(this, CaptureService::class.java).setAction(ACTION_SHOW_OVERLAY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_glyph_lens)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(
                if (preferences.showsFloatingWindow && !overlayVisibility.wantsWindow) {
                    R.string.notification_hidden_text
                } else R.string.notification_text,
            ))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
        if (preferences.showsFloatingWindow) {
            notification.addAction(Notification.Action.Builder(null, getString(R.string.notification_show_overlay), show).build())
        }
        return notification
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
