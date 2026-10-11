package com.babadogan.aynacast.tv

import com.babadogan.aynacast.tv.model.AppLanguage
import com.babadogan.aynacast.tv.model.AppThemeMode
import com.babadogan.aynacast.tv.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsTest {

    @Test
    fun testDefaultLanguageIsTurkish() {
        assertEquals(AppLanguage.TR, AppLanguage.DEFAULT)
        val defaultSettings = AppSettings()
        assertEquals(AppLanguage.TR, defaultSettings.language)
    }

    @Test
    fun testDefaultThemeIsSystem() {
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.DEFAULT)
        val defaultSettings = AppSettings()
        assertEquals(AppThemeMode.SYSTEM, defaultSettings.themeMode)
    }

    @Test
    fun testLanguageFromCodeParsing() {
        assertEquals(AppLanguage.TR, AppLanguage.fromCode("tr"))
        assertEquals(AppLanguage.TR, AppLanguage.fromCode("TR"))
        assertEquals(AppLanguage.EN, AppLanguage.fromCode("en"))
        assertEquals(AppLanguage.EN, AppLanguage.fromCode("EN"))
        // Fallback to Turkish
        assertEquals(AppLanguage.TR, AppLanguage.fromCode("unknown"))
        assertEquals(AppLanguage.TR, AppLanguage.fromCode(null))
    }

    @Test
    fun testThemeFromStringParsing() {
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromString("SYSTEM"))
        assertEquals(AppThemeMode.DARK, AppThemeMode.fromString("DARK"))
        assertEquals(AppThemeMode.LIGHT, AppThemeMode.fromString("LIGHT"))
        // Fallback to System
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromString("invalid"))
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromString(null))
    }

    @Test
    fun testDefaultKeepAliveInBackgroundIsTrue() {
        assertEquals(true, AppSettings.DEFAULT_KEEP_ALIVE)
        val defaultSettings = AppSettings()
        assertEquals(true, defaultSettings.keepAliveInBackground)
        val modifiedSettings = defaultSettings.copy(keepAliveInBackground = false)
        assertEquals(false, modifiedSettings.keepAliveInBackground)
    }

    @Test
    fun testTimestampSyncIsOffByDefault() {
        // UxPlay recommends "-vsync no" for hardware-decoding receivers and macOS mirroring.
        assertEquals(false, AppSettings.DEFAULT_AV_SYNC)
        assertEquals(false, AppSettings().avSyncEnabled)
        assertEquals(true, AppSettings().copy(avSyncEnabled = true).avSyncEnabled)
    }
}
