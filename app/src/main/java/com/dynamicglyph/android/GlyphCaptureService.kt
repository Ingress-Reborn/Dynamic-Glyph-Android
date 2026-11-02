package com.dynamicglyph.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.dynamicglyph.capture.CaptureEngine
import com.dynamicglyph.display.GlyphOverlayWindow
import com.dynamicglyph.recognition.GlyphTable

/**
 * Foreground service that owns screen capture, recognition and the overlay.
 *
 * Replaces the iOS capture session plus its Picture-in-Picture and Live Activity
 * surfaces. Android requires the projection to be driven from a foreground
 * service with type `mediaProjection`, so the whole pipeline lives here rather
 * than in the activity.
 */
class GlyphCaptureService : Service() {

    companion object {
        private const val TAG = "GlyphCaptureService"
        private const val CHANNEL_CAPTURE = "capture"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START = "com.dynamicglyph.android.START_CAPTURE"
        const val ACTION_STOP = "com.dynamicglyph.android.STOP_CAPTURE"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_GLYPH_CSV = "glyph_csv"
        const val EXTRA_SEQUENCE_CSV = "sequence_csv"

        /** Set by the service so the activity can tell whether it is running. */
        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private var captureEngine: CaptureEngine? = null
    private var overlayWindow: GlyphOverlayWindow? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        overlayWindow = GlyphOverlayWindow(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Smart cast needs a non-null local: `intent?.action` does not narrow the
        // parameter for the calls below.
        if (intent == null) return START_NOT_STICKY

        when (intent.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultData == null) {
            Log.e(TAG, "Start intent carried no projection data")
            stopSelf()
            return
        }

        val glyphCsv = intent.getStringExtra(EXTRA_GLYPH_CSV)
        val sequenceCsv = intent.getStringExtra(EXTRA_SEQUENCE_CSV)
        if (glyphCsv == null || sequenceCsv == null) {
            // The reference tables are not redistributed with this project, so
            // there is nothing to fall back to. Say so and stop, rather than
            // capture frames nothing can read.
            Log.e(TAG, "Start intent carried no tables")
            stopSelf()
            return
        }

        val engine = CaptureEngine(this) { snapshot ->
            overlayWindow?.update(snapshot)
        }

        try {
            engine.loadTables(glyphCsv, sequenceCsv)
        } catch (e: Exception) {
            Log.e(TAG, "Tables could not be parsed", e)
            stopSelf()
            return
        }

        startForeground()

        if (!engine.start(resultCode, resultData)) {
            Log.e(TAG, "Capture could not start")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        captureEngine = engine
        overlayWindow?.setGlyphTable(GlyphTable.parse(glyphCsv))
        overlayWindow?.show()
        isRunning = true
    }

    private fun stopCapture() {
        captureEngine?.release()
        captureEngine = null
        overlayWindow?.hide()
        isRunning = false
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCapture()
        overlayWindow = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForeground() {
        startForeground(
            NOTIFICATION_ID,
            createNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
    }

    private fun createNotification(): Notification {
        val open = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_CAPTURE)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CAPTURE,
                getString(R.string.capture_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.capture_channel_desc)
            }
        )
    }
}
