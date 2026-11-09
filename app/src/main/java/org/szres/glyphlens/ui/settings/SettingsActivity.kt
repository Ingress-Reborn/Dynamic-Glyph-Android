package org.szres.glyphlens.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import org.szres.glyphlens.R
import org.szres.glyphlens.settings.AppPreferences
import org.szres.glyphlens.settings.OverlayCanvasConfiguration

class SettingsActivity : ComponentActivity() {
    private lateinit var preferences: AppPreferences
    private var draftCanvas = OverlayCanvasConfiguration.standard
    private lateinit var widthSeek: SeekBar
    private lateinit var heightSeek: SeekBar
    private lateinit var sharpnessSeek: SeekBar
    private lateinit var applyCanvas: Button
    private lateinit var floatingSwitch: Switch

    private val overlayPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        preferences.showsFloatingWindow = Settings.canDrawOverlays(this)
        floatingSwitch.isChecked = preferences.showsFloatingWindow
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = AppPreferences(this)
        draftCanvas = preferences.overlayCanvas
        setContentView(R.layout.activity_settings)
        widthSeek = findViewById(R.id.settingsCanvasWidthSeek)
        heightSeek = findViewById(R.id.settingsCanvasHeightSeek)
        sharpnessSeek = findViewById(R.id.settingsCanvasSharpnessSeek)
        applyCanvas = findViewById(R.id.settingsApplyCanvasButton)
        floatingSwitch = findViewById(R.id.floatingWindowSwitch)
        connectActions()
        renderCanvas()
    }

    override fun onResume() {
        super.onResume()
        renderCalibration()
    }

    private fun connectActions() {
        findViewById<Button>(R.id.settingsResetCalibrationButton).setOnClickListener {
            preferences.resetDeviceCalibration()
            renderCalibration()
        }
        floatingSwitch.apply {
            isChecked = preferences.showsFloatingWindow
            setOnCheckedChangeListener { _, checked ->
                if (checked && !Settings.canDrawOverlays(this@SettingsActivity)) {
                    isChecked = false
                    overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")))
                } else {
                    preferences.showsFloatingWindow = checked
                }
            }
        }
        findViewById<Switch>(R.id.autoHideSwitch).apply {
            isChecked = preferences.hidesAfterDrawing
            setOnCheckedChangeListener { _, checked -> preferences.hidesAfterDrawing = checked }
        }
        findViewById<Switch>(R.id.settingsHideCaptureSwitch).apply {
            isChecked = preferences.protectsFloatingWindowFromCapture
            setOnCheckedChangeListener { _, checked -> preferences.protectsFloatingWindowFromCapture = checked }
        }
        findViewById<Switch>(R.id.settingsModeColorsSwitch).apply {
            isChecked = preferences.colorsFloatingWindowByMode
            setOnCheckedChangeListener { _, checked -> preferences.colorsFloatingWindowByMode = checked }
        }
        installCanvasSeek(widthSeek) { progress ->
            draftCanvas = draftCanvas.copy(width = OverlayCanvasConfiguration.MIN_WIDTH + progress * OverlayCanvasConfiguration.WIDTH_STEP)
        }
        installCanvasSeek(heightSeek) { progress ->
            draftCanvas = draftCanvas.copy(height = OverlayCanvasConfiguration.MIN_HEIGHT + progress * OverlayCanvasConfiguration.HEIGHT_STEP)
        }
        installCanvasSeek(sharpnessSeek) { progress -> draftCanvas = draftCanvas.copy(resolution = progress + 1) }
        applyCanvas.setOnClickListener {
            preferences.overlayCanvas = draftCanvas
            renderCanvas()
        }
        findViewById<Button>(R.id.settingsResetCanvasButton).setOnClickListener {
            draftCanvas = OverlayCanvasConfiguration.standard
            preferences.overlayCanvas = draftCanvas
            renderCanvas()
        }
    }

    private fun renderCalibration() {
        val hasCalibration = preferences.deviceCalibration != null
        findViewById<TextView>(R.id.settingsCalibrationStatus).setText(
            if (hasCalibration) R.string.settings_calibration_saved else R.string.settings_calibration_pending,
        )
        findViewById<Button>(R.id.settingsResetCalibrationButton).isEnabled = hasCalibration
    }

    private fun installCanvasSeek(seek: SeekBar, update: (Int) -> Unit) {
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    update(progress)
                    renderCanvas()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
    }

    private fun renderCanvas() {
        widthSeek.progress = (draftCanvas.width - OverlayCanvasConfiguration.MIN_WIDTH) / OverlayCanvasConfiguration.WIDTH_STEP
        heightSeek.progress = (draftCanvas.height - OverlayCanvasConfiguration.MIN_HEIGHT) / OverlayCanvasConfiguration.HEIGHT_STEP
        sharpnessSeek.progress = draftCanvas.resolution - 1
        findViewById<TextView>(R.id.settingsCanvasWidthLabel).text = getString(R.string.settings_canvas_width_value, draftCanvas.width)
        findViewById<TextView>(R.id.settingsCanvasHeightLabel).text = getString(R.string.settings_canvas_height_value, draftCanvas.height)
        findViewById<TextView>(R.id.settingsCanvasSharpnessLabel).text = getString(
            R.string.setup_sharpness_value, draftCanvas.resolution, draftCanvas.pixelWidth, draftCanvas.pixelHeight,
        )
        applyCanvas.isEnabled = draftCanvas != preferences.overlayCanvas
    }
}
