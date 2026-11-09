package org.szres.glyphlens.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

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
    fun defaultsEnableWindowAndMapHiding() {
        assertTrue(preferences.hidesAfterDrawing)
        assertTrue(preferences.showsFloatingWindow)
        assertFalse(preferences.colorsFloatingWindowByMode)
        assertFalse(preferences.protectsFloatingWindowFromCapture)
        assertEquals(OverlayCanvasConfiguration.standard, preferences.overlayCanvas)
    }

    @Test
    fun windowAndMapHidingPersistIndependently() {
        preferences.showsFloatingWindow = false
        assertTrue(preferences.hidesAfterDrawing)
        preferences.hidesAfterDrawing = false
        val reopened = AppPreferences(context)
        assertFalse(reopened.showsFloatingWindow)
        assertFalse(reopened.hidesAfterDrawing)
        reopened.hidesAfterDrawing = true
        assertFalse(AppPreferences(context).showsFloatingWindow)
        assertTrue(AppPreferences(context).hidesAfterDrawing)
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
}
