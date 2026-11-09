package org.szres.glyphlens.ui.settings

import android.content.Context
import android.content.Intent
import android.widget.Button
import android.widget.Switch
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.szres.glyphlens.R
import org.szres.glyphlens.capture.CaptureService
import org.szres.glyphlens.settings.AppPreferences
import org.szres.glyphlens.settings.OverlayCanvasConfiguration

@RunWith(AndroidJUnit4::class)
class SettingsActivityInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var activity: SettingsActivity? = null

    @Before
    fun clear() {
        context.getSharedPreferences(AppPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun cleanup() {
        activity?.let { current -> instrumentation.runOnMainSync { current.finish() } }
        instrumentation.waitForIdleSync()
        clear()
    }

    @Test
    fun windowAndMapHidingAreEnabledByDefaultAndPersistIndependently() {
        val settings = launch()
        assertTrue(settings.findViewById<Switch>(R.id.floatingWindowSwitch).isChecked)
        assertTrue(settings.findViewById<Switch>(R.id.autoHideSwitch).isChecked)
        instrumentation.runOnMainSync {
            settings.findViewById<Switch>(R.id.floatingWindowSwitch).performClick()
            settings.findViewById<Switch>(R.id.autoHideSwitch).performClick()
            settings.finish()
        }
        val reopened = launch()
        assertFalse(reopened.findViewById<Switch>(R.id.floatingWindowSwitch).isChecked)
        assertFalse(reopened.findViewById<Switch>(R.id.autoHideSwitch).isChecked)
        instrumentation.runOnMainSync { reopened.findViewById<Switch>(R.id.autoHideSwitch).performClick() }
        assertTrue(AppPreferences(context).hidesAfterDrawing)
        assertFalse(AppPreferences(context).showsFloatingWindow)
    }

    @Test
    fun windowResetPersistsWithoutRestartingCapture() {
        AppPreferences(context).overlayCanvas = OverlayCanvasConfiguration(512, 128, 3)
        val settings = launch()
        val before = CaptureService.currentState

        instrumentation.runOnMainSync {
            settings.findViewById<Button>(R.id.settingsResetCanvasButton).performClick()
        }
        instrumentation.waitForIdleSync()

        assertEquals(OverlayCanvasConfiguration.standard, AppPreferences(context).overlayCanvas)
        assertEquals(before, CaptureService.currentState)
    }

    @Test
    fun windowColorsAndPrivacyControlsPersist() {
        val settings = launch()
        instrumentation.runOnMainSync {
            settings.findViewById<Switch>(R.id.settingsModeColorsSwitch).performClick()
            settings.findViewById<Switch>(R.id.settingsHideCaptureSwitch).performClick()
        }
        instrumentation.waitForIdleSync()

        assertTrue(AppPreferences(context).colorsFloatingWindowByMode)
        assertTrue(AppPreferences(context).protectsFloatingWindowFromCapture)
    }

    private fun launch(): SettingsActivity {
        val intent = Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return (instrumentation.startActivitySync(intent) as SettingsActivity).also { activity = it }
    }
}
