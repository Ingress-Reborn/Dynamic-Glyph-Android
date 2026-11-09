package org.szres.glyphlens.settings

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.szres.glyphlens.R
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class LocalizedResourcesInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun englishJapaneseAndBothChineseScriptsResolveRealResources() {
        assertEquals("Settings", localized("en").getString(R.string.settings_title))
        assertEquals("設定", localized("ja").getString(R.string.settings_title))
        assertEquals("设置", localized("zh-CN").getString(R.string.settings_title))
        assertEquals("設定", localized("zh-TW").getString(R.string.settings_title))
    }

    private fun localized(tag: String): Context {
        val locale = Locale.forLanguageTag(tag)
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        return context.createConfigurationContext(configuration)
    }
}
