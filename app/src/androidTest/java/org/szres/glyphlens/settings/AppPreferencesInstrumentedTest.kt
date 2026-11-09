package org.szres.glyphlens.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.szres.glyphlens.recognition.text.GameLanguage

@RunWith(AndroidJUnit4::class)
class AppPreferencesInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences by lazy { AppPreferences(context) }

    @Before
    fun clear() {
        context.getSharedPreferences(AppPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun cleanup() = clear()

    @Test
    fun defaultsEnableWindowAndAutomaticLanguage() {
        assertEquals(RecognitionMode.REALTIME, preferences.recognitionMode)
        assertFalse(preferences.stopsAfterRound)
        assertTrue(preferences.showsFloatingWindow)
        assertNull(preferences.gameLanguage)
        assertFalse(preferences.colorsFloatingWindowByMode)
        assertFalse(preferences.protectsFloatingWindowFromCapture)
        assertEquals(OverlayCanvasConfiguration.standard, preferences.overlayCanvas)
    }

    @Test
    fun recognitionModePersistsAndUnknownStoredModesUseRealtime() {
        preferences.recognitionMode = RecognitionMode.POWER_SAVING
        assertEquals(RecognitionMode.POWER_SAVING, AppPreferences(context).recognitionMode)
        val store = context.getSharedPreferences(AppPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE)
        assertEquals("powerSaving", store.getString(AppPreferences.RECOGNITION_MODE_KEY, null))

        store.edit().putString(AppPreferences.RECOGNITION_MODE_KEY, "unknown-mode").commit()
        assertEquals(RecognitionMode.REALTIME, AppPreferences(context).recognitionMode)
    }

    @Test
    fun stoppingAfterRoundPersistsIndependentlyOfRecognitionMode() {
        preferences.stopsAfterRound = true
        preferences.recognitionMode = RecognitionMode.POWER_SAVING

        val reopened = AppPreferences(context)
        assertTrue(reopened.stopsAfterRound)
        reopened.recognitionMode = RecognitionMode.REALTIME
        assertTrue(AppPreferences(context).stopsAfterRound)
        reopened.stopsAfterRound = false
        assertFalse(AppPreferences(context).stopsAfterRound)
    }

    @Test
    fun liveWindowAndModeColorPreferencesSurviveReopening() {
        preferences.overlayCanvas = OverlayCanvasConfiguration(512, 128, 3)
        preferences.colorsFloatingWindowByMode = true
        preferences.protectsFloatingWindowFromCapture = true

        val reopened = AppPreferences(context)
        assertEquals(OverlayCanvasConfiguration(512, 128, 3), reopened.overlayCanvas)
        assertTrue(reopened.colorsFloatingWindowByMode)
        assertTrue(reopened.protectsFloatingWindowFromCapture)
    }

    @Test
    fun chosenAndDetectedLanguagesKeepSeparateStoredValues() {
        preferences.gameLanguage = GameLanguage.traditionalChinese
        preferences.detectedGameLanguage = GameLanguage.japanese

        val reopened = AppPreferences(context)
        assertEquals(GameLanguage.traditionalChinese, reopened.gameLanguage)
        assertEquals(GameLanguage.japanese, reopened.detectedGameLanguage)
    }
}
