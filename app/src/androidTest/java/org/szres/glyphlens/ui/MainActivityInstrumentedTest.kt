package org.szres.glyphlens.ui

import android.content.Context
import android.content.Intent
import android.widget.Button
import android.widget.RadioButton
import android.widget.Switch
import android.widget.TextView
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
import org.szres.glyphlens.settings.RecognitionMode
import org.szres.glyphlens.tables.TableController

@RunWith(AndroidJUnit4::class)
class MainActivityInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var activity: MainActivity? = null

    @Before
    fun clear() {
        context.getSharedPreferences(AppPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun cleanup() {
        activity?.let { instrumentation.runOnMainSync { it.finish() } }
        instrumentation.waitForIdleSync()
        clear()
    }

    @Test
    fun freshInstallCanStartGlyphRecognitionWithBundledTables() {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val home = (instrumentation.startActivitySync(intent) as MainActivity).also { activity = it }
        instrumentation.waitForIdleSync()
        val tables = TableController(context).also { it.loadStored() }

        assertTrue(tables.assistsGlyphs)
        assertTrue(home.findViewById<Button>(R.id.primaryButton).isEnabled)
        assertEquals(context.getString(R.string.start_capture), home.findViewById<Button>(R.id.primaryButton).text)
        assertEquals(tables.shapeStatus, home.findViewById<TextView>(R.id.shapeTableStatus).text)
        assertEquals(tables.sequenceStatus, home.findViewById<TextView>(R.id.sequenceTableStatus).text)
        assertEquals(context.getString(R.string.home_live_glyph_title), home.findViewById<TextView>(R.id.homeLiveTitle).text)
        assertTrue(home.findViewById<RadioButton>(R.id.realtimeModeButton).isChecked)
        assertFalse(home.findViewById<Switch>(R.id.autoStopSwitch).isChecked)
    }

    @Test
    fun powerSavingSelectionPersistsAndKeepsItsFloatingLauncherAvailable() {
        val preferences = AppPreferences(context).apply { showsFloatingWindow = false }
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val home = (instrumentation.startActivitySync(intent) as MainActivity).also { activity = it }
        instrumentation.waitForIdleSync()

        assertFalse(home.findViewById<Switch>(R.id.floatingWindowSwitch).isChecked)
        instrumentation.runOnMainSync {
            home.findViewById<RadioButton>(R.id.powerSavingModeButton).performClick()
        }
        assertEquals(RecognitionMode.POWER_SAVING, AppPreferences(context).recognitionMode)
        assertTrue(preferences.showsFloatingWindow)
        assertTrue(home.findViewById<Switch>(R.id.floatingWindowSwitch).isChecked)
        assertFalse(home.findViewById<Switch>(R.id.floatingWindowSwitch).isEnabled)
        assertEquals(CaptureService.SessionState.STOPPED, CaptureService.currentState)
        assertEquals(
            context.getString(R.string.recognition_mode_power_saving_hint),
            home.findViewById<TextView>(R.id.recognitionModeHint).text,
        )

        instrumentation.runOnMainSync { home.finish() }
        val reopened = (instrumentation.startActivitySync(intent) as MainActivity).also { activity = it }
        instrumentation.waitForIdleSync()
        assertTrue(reopened.findViewById<RadioButton>(R.id.powerSavingModeButton).isChecked)
        assertFalse(reopened.findViewById<Switch>(R.id.floatingWindowSwitch).isEnabled)
    }

    @Test
    fun automaticRoundStopPersistsAndKeepsTheResultWindowAvailable() {
        val preferences = AppPreferences(context).apply { showsFloatingWindow = false }
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val home = (instrumentation.startActivitySync(intent) as MainActivity).also { activity = it }
        instrumentation.waitForIdleSync()

        instrumentation.runOnMainSync { home.findViewById<Switch>(R.id.autoStopSwitch).performClick() }
        assertTrue(AppPreferences(context).stopsAfterRound)
        assertEquals(RecognitionMode.REALTIME, preferences.recognitionMode)
        assertTrue(preferences.showsFloatingWindow)
        assertTrue(home.findViewById<Switch>(R.id.floatingWindowSwitch).isChecked)
        assertFalse(home.findViewById<Switch>(R.id.floatingWindowSwitch).isEnabled)
        assertEquals(CaptureService.SessionState.STOPPED, CaptureService.currentState)

        instrumentation.runOnMainSync { home.finish() }
        val reopened = (instrumentation.startActivitySync(intent) as MainActivity).also { activity = it }
        instrumentation.waitForIdleSync()
        assertTrue(reopened.findViewById<Switch>(R.id.autoStopSwitch).isChecked)
        assertFalse(reopened.findViewById<Switch>(R.id.floatingWindowSwitch).isEnabled)

        instrumentation.runOnMainSync { reopened.findViewById<Switch>(R.id.autoStopSwitch).performClick() }
        assertFalse(AppPreferences(context).stopsAfterRound)
        assertTrue(reopened.findViewById<Switch>(R.id.floatingWindowSwitch).isEnabled)
        assertTrue(preferences.showsFloatingWindow)
    }
}
