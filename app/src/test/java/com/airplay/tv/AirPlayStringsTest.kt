package com.airplay.tv

import com.airplay.tv.model.AppLanguage
import com.airplay.tv.model.AppThemeMode
import com.airplay.tv.ui.localization.AirPlayStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class AirPlayStringsTest {

    @Test
    fun testAllStringsNonBlankAndBilingual() {
        val testDevice = "Test TV"
        val testClient = "iPhone 16"
        val testVersion = "1.0.0"

        for (lang in AppLanguage.entries) {
            assertTrue(AirPlayStrings.appName(lang).isNotBlank())
            assertTrue(AirPlayStrings.appSubtitle(lang).isNotBlank())
            assertTrue(AirPlayStrings.tabSettings(lang).isNotBlank())
            assertTrue(AirPlayStrings.tabAbout(lang).isNotBlank())
            assertTrue(AirPlayStrings.serviceStatusActive(lang).isNotBlank())
            assertTrue(AirPlayStrings.serviceStatusStopped(lang).isNotBlank())
            assertTrue(AirPlayStrings.statusIdle(testDevice, lang).isNotBlank())
            assertTrue(AirPlayStrings.statusConnecting(testClient, lang).isNotBlank())
            assertTrue(AirPlayStrings.statusStreaming(testClient, lang).isNotBlank())
            assertTrue(AirPlayStrings.statusDisconnected(lang).isNotBlank())
            assertTrue(AirPlayStrings.howToConnectTitle(lang).isNotBlank())
            assertTrue(AirPlayStrings.howToConnectInstructions(testDevice, lang).isNotBlank())
            assertTrue(AirPlayStrings.restartService(lang).isNotBlank())
            assertTrue(AirPlayStrings.settingsSectionAppearance(lang).isNotBlank())
            assertTrue(AirPlayStrings.settingsSectionLanguage(lang).isNotBlank())
            assertTrue(AirPlayStrings.settingKeepAliveTitle(lang).isNotBlank())
            assertTrue(AirPlayStrings.settingKeepAliveDesc(lang).isNotBlank())
            assertTrue(AirPlayStrings.keepAliveEnabled(lang).isNotBlank())
            assertTrue(AirPlayStrings.keepAliveDisabled(lang).isNotBlank())
            assertTrue(AirPlayStrings.airplayActiveBadge(lang).isNotBlank())
            assertTrue(AirPlayStrings.aboutVersion(testVersion, lang).isNotBlank())
            assertTrue(AirPlayStrings.aboutArchitectureTitle(lang).isNotBlank())
            assertTrue(AirPlayStrings.aboutArchitectureDesc(lang).isNotBlank())
            assertTrue(AirPlayStrings.aboutProtocolsTitle(lang).isNotBlank())
            assertTrue(AirPlayStrings.aboutProtocolsDesc(lang).isNotBlank())
            assertTrue(AirPlayStrings.aboutDeveloperTitle(lang).isNotBlank())
            assertTrue(AirPlayStrings.aboutDeveloperDesc(lang).isNotBlank())
            assertTrue(AirPlayStrings.aboutLicenseTitle(lang).isNotBlank())
            assertTrue(AirPlayStrings.aboutLicenseDesc(lang).isNotBlank())
            assertTrue(AirPlayStrings.aboutDisclaimerTitle(lang).isNotBlank())
            assertTrue(AirPlayStrings.aboutDisclaimerDesc(lang).isNotBlank())

            for (theme in AppThemeMode.entries) {
                assertTrue(AirPlayStrings.themeName(theme, lang).isNotBlank())
            }

            for (targetLang in AppLanguage.entries) {
                assertTrue(AirPlayStrings.languageName(targetLang, lang).isNotBlank())
            }
        }
    }

    @Test
    fun testTurkishDefaultLanguageDistinction() {
        // Verify Turkish and English translations are distinct for key UI terms
        assertNotEquals(AirPlayStrings.appName(AppLanguage.TR), AirPlayStrings.appName(AppLanguage.EN))
        assertNotEquals(AirPlayStrings.tabSettings(AppLanguage.TR), AirPlayStrings.tabSettings(AppLanguage.EN))
        assertNotEquals(AirPlayStrings.tabAbout(AppLanguage.TR), AirPlayStrings.tabAbout(AppLanguage.EN))
        assertNotEquals(AirPlayStrings.restartService(AppLanguage.TR), AirPlayStrings.restartService(AppLanguage.EN))
        assertNotEquals(AirPlayStrings.airplayActiveBadge(AppLanguage.TR), AirPlayStrings.airplayActiveBadge(AppLanguage.EN))
        assertNotEquals(AirPlayStrings.settingsSectionAppearance(AppLanguage.TR), AirPlayStrings.settingsSectionAppearance(AppLanguage.EN))
        assertNotEquals(AirPlayStrings.settingsSectionLanguage(AppLanguage.TR), AirPlayStrings.settingsSectionLanguage(AppLanguage.EN))
        assertNotEquals(AirPlayStrings.settingKeepAliveTitle(AppLanguage.TR), AirPlayStrings.settingKeepAliveTitle(AppLanguage.EN))
        assertNotEquals(AirPlayStrings.settingKeepAliveDesc(AppLanguage.TR), AirPlayStrings.settingKeepAliveDesc(AppLanguage.EN))
        assertNotEquals(AirPlayStrings.keepAliveEnabled(AppLanguage.TR), AirPlayStrings.keepAliveEnabled(AppLanguage.EN))
        assertNotEquals(AirPlayStrings.keepAliveDisabled(AppLanguage.TR), AirPlayStrings.keepAliveDisabled(AppLanguage.EN))
        assertNotEquals(AirPlayStrings.themeName(AppThemeMode.DARK, AppLanguage.TR), AirPlayStrings.themeName(AppThemeMode.DARK, AppLanguage.EN))
        assertNotEquals(AirPlayStrings.languageName(AppLanguage.TR, AppLanguage.TR), AirPlayStrings.languageName(AppLanguage.TR, AppLanguage.EN))
    }

    @Test
    fun testXmlStringResourcesOneToOneMatching() {
        // Parse values/strings.xml and values-en/strings.xml to ensure 100% 1:1 key parity
        val baseDir = File("src/main/res")
        val trStringsFile = File(baseDir, "values/strings.xml")
        val enStringsFile = File(baseDir, "values-en/strings.xml")

        if (!trStringsFile.exists() || !enStringsFile.exists()) {
            return // Skip if running from different working dir
        }

        fun extractKeys(file: File): Set<String> {
            val db = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            val doc = db.parse(file)
            val nodes = doc.getElementsByTagName("string")
            val keys = mutableSetOf<String>()
            for (i in 0 until nodes.length) {
                val node = nodes.item(i)
                val key = node.attributes.getNamedItem("name")?.nodeValue
                if (key != null) keys.add(key)
            }
            return keys
        }

        val trKeys = extractKeys(trStringsFile)
        val enKeys = extractKeys(enStringsFile)

        assertFalse("Turkish strings should not be empty", trKeys.isEmpty())
        assertFalse("English strings should not be empty", enKeys.isEmpty())
        assertEquals("values/strings.xml and values-en/strings.xml must have exactly matching 1:1 keys", trKeys, enKeys)
    }
}
