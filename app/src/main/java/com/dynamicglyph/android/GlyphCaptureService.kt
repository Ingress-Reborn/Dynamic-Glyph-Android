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

        /**
         * The MediaProjection consent Intent, handed over in-process.
         *
         * Passing it as an Intent extra through `startForegroundService()` makes
         * it travel through system_server and back (parcel -> unparcel -> parcel).
         * Since Android 14 the consent token is single-use and the system binds
         * it to the component that consumes it; a round trip like that is a
         * common cause of `createVirtualDisplay()` throwing SecurityException on
         * HyperOS / Android 15. Activity and service are in the same process, so
         * the original Intent object can simply be handed over here.
         */
        @Volatile
        var pendingResultData: Intent? = null

        @Volatile
        var pendingResultCode: Int = 0
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
        // Show the panel *first*, before anything that can fail. Previously the
        // window was only added after a successful start, so any failure left the
        // screen completely blank with no clue about where it stopped.
        captureEngine?.release()
        captureEngine = null
        overlayWindow?.setStatus("正在启动…")
        overlayWindow?.show()

        try {
            startForeground()
        } catch (e: Exception) {
            Log.e(TAG, "Could not start foreground service", e)
            overlayWindow?.setStatus("启动失败：前台服务被系统拒绝\n${e.message ?: ""}")
            return
        }

        // Prefer the in-process Intent. The extra is kept only as a fallback
        // for builds that still pass it the old way.
        val resultCode = GlyphCaptureService.pendingResultCode
            .takeIf { it != 0 } ?: intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val resultData = GlyphCaptureService.pendingResultData
            ?: intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultData == null) {
            reportStatus("启动失败：没有拿到系统截屏授权\n请回 App 重新点「开始截屏」")
            return
        }

        val glyphCsv = intent.getStringExtra(EXTRA_GLYPH_CSV)
        val sequenceCsv = intent.getStringExtra(EXTRA_SEQUENCE_CSV)
        if (glyphCsv == null || sequenceCsv == null) {
            reportStatus("启动失败：缺少两个 CSV 数据文件\n请在 App 里先加载")
            return
        }

        val engine = CaptureEngine(this) { snapshot ->
            overlayWindow?.update(snapshot)
        }

        val table = try {
            engine.loadTables(glyphCsv, sequenceCsv)
            GlyphTable.parse(glyphCsv)
        } catch (e: Exception) {
            Log.e(TAG, "Tables could not be parsed", e)
            reportStatus("启动失败：CSV 内容读不了\n${e.message ?: ""}")
            return
        }

        val failure = engine.start(resultCode, resultData)
        if (failure != null) {
            Log.e(TAG, "Capture could not start: $failure")
            reportStatus("启动失败：$failure")
            // Keep the service alive so the message stays visible. The user can
            // stop it from the app's button.
            return
        }

        captureEngine = engine
        overlayWindow?.setGlyphTable(table)
        reportStatus("截屏已启动\n切到 Ingress 触发一次 Glyph Hack")
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
            createNotification(getString(R.string.capture_notification_text)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
    }

    /**
     * Put the same status in the notification. If HyperOS refuses the overlay
     * window, the notification is the one surface that still shows what happened.
     */
    private fun reportStatus(text: String) {
        overlayWindow?.setStatus(text)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, createNotification(text.replace("\n", " ")))
    }

    private fun createNotification(text: String): Notification {
        val open = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_CAPTURE)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
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
