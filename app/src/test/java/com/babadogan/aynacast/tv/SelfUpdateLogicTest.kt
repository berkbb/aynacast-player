package com.babadogan.aynacast.tv

import com.babadogan.aynacast.tv.update.ReleaseApk
import com.babadogan.aynacast.tv.update.SelfUpdateLogic
import com.babadogan.aynacast.tv.update.UpdateCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfUpdateLogicTest {

    private val assets = listOf("arm64-v8a", "armeabi-v7a", "universal", "x86_64").map {
        ReleaseApk("AynaCastTV-v1.5.2-$it-release.apk", "https://example.com/$it.apk", 13_000_000, null)
    } + ReleaseApk("SHA256SUMS", "https://example.com/sums", 400, null)

    @Test
    fun comparesVersions() {
        assertTrue(SelfUpdateLogic.isNewer("1.5.2", "1.5.1"))
        assertTrue(SelfUpdateLogic.isNewer("v1.10.0", "1.9.9"))
        assertFalse(SelfUpdateLogic.isNewer("1.5.1", "1.5.1"))
        assertFalse(SelfUpdateLogic.isNewer("1.5.0", "1.5.1"))
        assertFalse(SelfUpdateLogic.isNewer("", "1.5.1"))
    }

    @Test
    fun selectsTheApkForTheTvAbi() {
        assertEquals("https://example.com/armeabi-v7a.apk", SelfUpdateLogic.selectApk(assets, arrayOf("armeabi-v7a", "armeabi"))?.url)
        assertEquals("https://example.com/arm64-v8a.apk", SelfUpdateLogic.selectApk(assets, arrayOf("arm64-v8a", "armeabi-v7a"))?.url)
        assertEquals("https://example.com/universal.apk", SelfUpdateLogic.selectApk(assets, arrayOf("x86"))?.url)
        assertNull(SelfUpdateLogic.selectApk(assets.takeLast(1), arrayOf("armeabi-v7a")))
    }

    @Test
    fun buildsTheCheckResult() {
        assertEquals(UpdateCheck.Available("1.5.2", "1.5.1", null), SelfUpdateLogic.checkFor("v1.5.2", "1.5.1", null))
        assertEquals(UpdateCheck.UpToDate("1.5.2"), SelfUpdateLogic.checkFor("v1.5.2", "1.5.2", null))
        assertEquals(UpdateCheck.Unavailable, SelfUpdateLogic.checkFor("", "1.5.2", null))
        val hex = "b".repeat(64)
        assertEquals(hex, SelfUpdateLogic.parseSha256("sha256:$hex"))
        assertNull(SelfUpdateLogic.parseSha256("sha1:abc"))
    }
}
