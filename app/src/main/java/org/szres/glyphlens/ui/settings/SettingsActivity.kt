package org.szres.glyphlens.ui.settings

import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import org.szres.glyphlens.R
import org.szres.glyphlens.overlay.OverlayLeavePresenter
import org.szres.glyphlens.settings.AppPreferences
import org.szres.glyphlens.settings.OverlayCanvasConfiguration

class SettingsActivity : ComponentActivity() {
    private lateinit var preferences: AppPreferences
    private var draftCanvas = OverlayCanvasConfiguration.standard
    private lateinit var widthSeek: SeekBar
    private lateinit var heightSeek: SeekBar
    private lateinit var sharpnessSeek: SeekBar
    private lateinit var applyCanvas: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = AppPreferences(this)
        draftCanvas = preferences.overlayCanvas
        setContentView(R.layout.activity_settings)
        widthSeek = findViewById(R.id.settingsCanvasWidthSeek)
        heightSeek = findViewById(R.id.settingsCanvasHeightSeek)
        sharpnessSeek = findViewById(R.id.settingsCanvasSharpnessSeek)
        applyCanvas = findViewById(R.id.settingsApplyCanvasButton)
        connectActions()
        renderCanvas()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        OverlayLeavePresenter.onUserLeave(this)
    }

    private fun connectActions() {
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
