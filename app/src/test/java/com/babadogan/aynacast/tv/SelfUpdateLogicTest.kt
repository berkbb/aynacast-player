package com.babadogan.aynacast.tv

import com.babadogan.aynacast.tv.update.ReleaseApk
import com.babadogan.aynacast.tv.update.SELF_RELEASE_ASSET_API_PREFIX
import com.babadogan.aynacast.tv.update.SelfUpdateLogic
import com.babadogan.aynacast.tv.update.UpdateCheck
import org.json.JSONObject
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

    @Test
    fun parsesBothAssetUrlsFromTheReleaseJson() {
        val hex = "c".repeat(64)
        val json = JSONObject(
            """{"tag_name":"v1.5.5","assets":[{"name":"AynaCastTV-v1.5.5-arm64-v8a-release.apk",
            |"url":"${SELF_RELEASE_ASSET_API_PREFIX}123456","browser_download_url":"https://github.com/berkbb/aynacast-player/releases/download/v1.5.5/AynaCastTV-v1.5.5-arm64-v8a-release.apk",
            |"size":13000000,"digest":"sha256:$hex"},{"name":"SHA256SUMS","browser_download_url":"https://github.com/berkbb/aynacast-player/releases/download/v1.5.5/SHA256SUMS","size":400,"digest":null}]}""".trimMargin()
        )
        val apks = SelfUpdateLogic.parseAssets(json)
        assertEquals(
            ReleaseApk(
                "AynaCastTV-v1.5.5-arm64-v8a-release.apk",
                "https://github.com/berkbb/aynacast-player/releases/download/v1.5.5/AynaCastTV-v1.5.5-arm64-v8a-release.apk",
                13_000_000, hex, "${SELF_RELEASE_ASSET_API_PREFIX}123456"
            ),
            apks[0]
        )
        assertEquals(ReleaseApk("SHA256SUMS", "https://github.com/berkbb/aynacast-player/releases/download/v1.5.5/SHA256SUMS", 400, null, null), apks[1])
        assertEquals(apks[0], SelfUpdateLogic.selectApk(apks, arrayOf("arm64-v8a")))
    }

    @Test
    fun dropsAssetApiUrlsOfOtherRepositoriesAndHosts() {
        assertEquals("${SELF_RELEASE_ASSET_API_PREFIX}1", SelfUpdateLogic.assetApiUrl("${SELF_RELEASE_ASSET_API_PREFIX}1"))
        assertNull(SelfUpdateLogic.assetApiUrl("https://api.github.com/repos/berkbb/other-app/releases/assets/1"))
        assertNull(SelfUpdateLogic.assetApiUrl("https://api.github.com/repos/berkbb/aynacast-player-fork/releases/assets/1"))
        assertNull(SelfUpdateLogic.assetApiUrl("http://api.github.com/repos/berkbb/aynacast-player/releases/assets/1"))
        assertNull(SelfUpdateLogic.assetApiUrl("https://api.example.com/repos/berkbb/aynacast-player/releases/assets/1"))
        assertNull(SelfUpdateLogic.assetApiUrl(SELF_RELEASE_ASSET_API_PREFIX))
        assertNull(SelfUpdateLogic.assetApiUrl(""))
        assertNull(SelfUpdateLogic.assetApiUrl(null))
        val foreign = JSONObject("""{"assets":[{"name":"a-universal-release.apk","url":"https://api.github.com/repos/someone/else/releases/assets/9","browser_download_url":"https://github.com/berkbb/aynacast-player/releases/download/v1/a-universal-release.apk","size":5}]}""")
        assertNull(SelfUpdateLogic.parseAssets(foreign).single().apiUrl)
    }
}
