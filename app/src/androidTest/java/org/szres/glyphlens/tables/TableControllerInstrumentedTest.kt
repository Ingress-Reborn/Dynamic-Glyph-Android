package org.szres.glyphlens.tables

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TableControllerInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clearTablesAndPreferences() {
        context.getSharedPreferences("glyph-lens", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "tables").deleteRecursively()
    }

    @After
    fun cleanup() {
        clearTablesAndPreferences()
    }

    @Test
    fun freshInstallLoadsBundledTablesAndEnablesGlyphAssistance() {
        val controller = TableController(context)

        controller.loadStored()

        assertEquals(142, controller.shapeTable?.count)
        assertEquals(540, controller.sequenceTable?.count)
        assertTrue(controller.assistsGlyphs)
    }

    @Test
    fun legacyGearOnlyPreferenceCannotDisableGlyphRecognition() {
        context.getSharedPreferences("glyph-lens", Context.MODE_PRIVATE)
            .edit().putBoolean("capture.assistsGlyphs", false).commit()
        val controller = TableController(context)

        controller.loadStored()

        assertEquals(142, controller.shapeTable?.count)
        assertEquals(540, controller.sequenceTable?.count)
        assertTrue(controller.assistsGlyphs)
    }
}
