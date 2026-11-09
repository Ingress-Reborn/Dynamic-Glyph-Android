package org.szres.glyphlens.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.szres.glyphlens.R
import org.szres.glyphlens.capture.CaptureService
import org.szres.glyphlens.overlay.OverlayPreviewView
import org.szres.glyphlens.settings.AppPreferences
import org.szres.glyphlens.tables.TableController
import org.szres.glyphlens.ui.settings.SettingsActivity

class MainActivity : ComponentActivity() {
    private lateinit var preferences: AppPreferences
    private lateinit var tables: TableController
    private lateinit var primaryButton: Button
    private lateinit var overlayButton: Button
    private lateinit var statusText: TextView
    private lateinit var liveStatus: TextView
    private lateinit var livePreview: OverlayPreviewView
    private var awaitingStart = false
    private var resumed = false
    private var lastMessage: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val liveTick = object : Runnable {
        override fun run() {
            renderLiveSnapshot()
            if (resumed && CaptureService.currentState == CaptureService.SessionState.RUNNING) {
                mainHandler.postDelayed(this, 500L)
            }
        }
    }

    private val overlayPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (awaitingStart) {
            if (Settings.canDrawOverlays(this)) startCapture()
            else {
                awaitingStart = false
                lastMessage = getString(R.string.status_overlay_required)
                render()
            }
        }
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (awaitingStart) requestProjection()
    }
    private val projectionPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (awaitingStart) {
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                startCaptureService(result.resultCode, data)
            } else {
                awaitingStart = false
                lastMessage = getString(R.string.status_permission_cancelled)
                render()
            }
        }
    }
    private val shapePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && canChooseTables) tables.importShapes(uri)
        render()
    }
    private val sequencePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && canChooseTables) tables.importSequences(uri)
        render()
    }
    private val captureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (CaptureService.currentState == CaptureService.SessionState.RUNNING ||
                CaptureService.currentState == CaptureService.SessionState.STOPPED
            ) awaitingStart = false
            lastMessage = intent.getStringExtra(CaptureService.EXTRA_MESSAGE)
            render()
            mainHandler.removeCallbacks(liveTick)
            if (resumed) mainHandler.post(liveTick)
        }
    }
    private val canChooseTables: Boolean
        get() = CaptureService.currentState == CaptureService.SessionState.STOPPED && !awaitingStart

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = AppPreferences(this)
        tables = TableController(this).also { it.loadStored() }
        awaitingStart = savedInstanceState?.getBoolean("awaitingStart") ?: false
        setContentView(R.layout.activity_main)
        primaryButton = findViewById(R.id.primaryButton)
        overlayButton = findViewById(R.id.overlayButton)
        statusText = findViewById(R.id.statusText)
        liveStatus = findViewById(R.id.homeLiveStatus)
        livePreview = findViewById(R.id.homeLivePreview)
        livePreview.statusView.visibility = View.GONE
        primaryButton.setOnClickListener {
            when (CaptureService.currentState) {
                CaptureService.SessionState.STOPPED -> if (awaitingStart) {
                    awaitingStart = false
                    lastMessage = null
                    render()
                } else startCapture()
                CaptureService.SessionState.STARTING, CaptureService.SessionState.RUNNING ->
                    sendServiceAction(CaptureService.ACTION_STOP)
                CaptureService.SessionState.STOPPING -> Unit
            }
        }
        overlayButton.setOnClickListener {
            sendServiceAction(
                if (CaptureService.isOverlayVisible) CaptureService.ACTION_HIDE_OVERLAY
                else CaptureService.ACTION_SHOW_OVERLAY,
            )
        }
        findViewById<Button>(R.id.openIngressButton).setOnClickListener { openIngress() }
        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.importShapesButton).setOnClickListener { shapePicker.launch(arrayOf("*/*")) }
        findViewById<Button>(R.id.importSequencesButton).setOnClickListener { sequencePicker.launch(arrayOf("*/*")) }
        render()
        if (savedInstanceState == null && CaptureService.currentState == CaptureService.SessionState.STOPPED &&
            tables.assistsGlyphs
        ) startCapture()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this, captureReceiver, IntentFilter(CaptureService.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (CaptureService.currentState != CaptureService.SessionState.STOPPED) lastMessage = null
        render()
        mainHandler.post(liveTick)
    }

    override fun onPause() {
        resumed = false
        mainHandler.removeCallbacks(liveTick)
        super.onPause()
    }

    override fun onStop() {
        unregisterReceiver(captureReceiver)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("awaitingStart", awaitingStart)
        super.onSaveInstanceState(outState)
    }

    private fun startCapture() {
        awaitingStart = true
        lastMessage = null
        render()
        when {
            preferences.showsFloatingWindow && !Settings.canDrawOverlays(this) ->
                overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else -> requestProjection()
        }
    }

    private fun requestProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionPermission.launch(manager.createScreenCaptureIntent())
    }

    private fun startCaptureService(resultCode: Int, data: Intent) {
        val intent = Intent(this, CaptureService::class.java)
            .setAction(CaptureService.ACTION_START)
            .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(CaptureService.EXTRA_RESULT_DATA, data)
        ContextCompat.startForegroundService(this, intent)
        render()
    }

    private fun sendServiceAction(action: String) {
        startService(Intent(this, CaptureService::class.java).setAction(action))
    }

    private fun openIngress() {
        val intent = packageManager.getLaunchIntentForPackage("com.nianticproject.ingress")
        if (intent == null) {
            lastMessage = getString(R.string.ingress_unavailable)
            render()
            return
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            lastMessage = getString(R.string.ingress_unavailable)
            render()
        }
    }

    private fun render() {
        val state = CaptureService.currentState
        primaryButton.isEnabled = state != CaptureService.SessionState.STOPPING &&
            (tables.assistsGlyphs || state != CaptureService.SessionState.STOPPED)
        primaryButton.setText(when {
            state == CaptureService.SessionState.RUNNING -> R.string.stop_capture
            state == CaptureService.SessionState.STARTING || awaitingStart -> R.string.cancel_capture
            state == CaptureService.SessionState.STOPPING -> R.string.stopping_capture
            else -> R.string.start_capture
        })
        overlayButton.visibility = if (state == CaptureService.SessionState.RUNNING && preferences.showsFloatingWindow) {
            View.VISIBLE
        } else View.GONE
        overlayButton.setText(if (CaptureService.isOverlayVisible) R.string.hide_overlay else R.string.show_overlay)
        val hiddenStatus = when {
            state == CaptureService.SessionState.RUNNING && !CaptureService.isOverlayVisible &&
                preferences.showsFloatingWindow ->
                R.string.status_running_hidden
            else -> null
        }
        statusText.text = hiddenStatus?.let(::getString) ?: lastMessage ?: getString(when {
            state == CaptureService.SessionState.RUNNING -> R.string.status_running
            state == CaptureService.SessionState.STARTING -> R.string.status_starting
            state == CaptureService.SessionState.STOPPING -> R.string.status_stopping
            awaitingStart -> R.string.status_waiting
            else -> R.string.status_ready
        })
        findViewById<Button>(R.id.importShapesButton).isEnabled = canChooseTables
        findViewById<Button>(R.id.importSequencesButton).isEnabled = canChooseTables
        findViewById<TextView>(R.id.shapeTableStatus).text = tables.shapeStatus
        findViewById<TextView>(R.id.sequenceTableStatus).text = tables.sequenceStatus
        renderLiveSnapshot()
    }

    private fun renderLiveSnapshot() {
        val snapshot = CaptureService.latestSnapshot
        if (snapshot == null) {
            liveStatus.setText(R.string.home_live_waiting)
            livePreview.visibility = View.GONE
            return
        }
        val uptime = SystemClock.elapsedRealtimeNanos() / 1_000_000_000.0
        liveStatus.text = snapshot.statusLine(uptime)
        livePreview.visibility = View.VISIBLE
        livePreview.render(snapshot, tables.shapeTable, uptime, preferences.overlayCanvas)
    }
}
