package com.babadogan.aynacast.tv

import com.babadogan.aynacast.tv.update.ApkDownloader
import com.babadogan.aynacast.tv.update.ReleaseApk
import com.babadogan.aynacast.tv.update.SELF_RELEASE_ASSET_API_PREFIX
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** The download without the network: a fake connection per URL, no real GitHub access. */
class SelfUpdateDownloadTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val browserUrl = "https://github.com/berkbb/aynacast-player/releases/download/v1.5.5/AynaCastTV-v1.5.5-universal-release.apk"
    private val apiUrl = "${SELF_RELEASE_ASSET_API_PREFIX}123456"
    private val bytes = ByteArray(200_000) { (it % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val apk = ReleaseApk("AynaCastTV-v1.5.5-universal-release.apk", browserUrl, bytes.size.toLong(), sha, apiUrl)

    /** One canned answer: an HTTP status with a body, or an I/O failure before any status. */
    private class Answer(val status: Int = 200, val body: ByteArray = ByteArray(0), val failure: IOException? = null)

    private open class FakeConnection(url: URL, private val answer: Answer) : HttpURLConnection(url) {
        var accept: String? = null
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode(): Int {
            accept = getRequestProperty("Accept")
            answer.failure?.let { throw it }
            return answer.status
        }
        override fun getInputStream(): InputStream = ByteArrayInputStream(answer.body)
    }

    private val opened = mutableListOf<FakeConnection>()

    private fun downloader(vararg answers: Pair<String, Answer>): ApkDownloader {
        val byUrl = answers.toMap()
        return ApkDownloader { url ->
            val connection = FakeConnection(url, byUrl[url.toString()] ?: error("unexpected URL $url"))
            opened += connection
            connection
        }
    }

    private fun target(): File = File(folder.root, apk.name)
    private fun part(): File = File(folder.root, apk.name + ".part")

    @Test
    fun downloadsFromTheBrowserUrlWhenItWorks() {
        val downloader = downloader(browserUrl to Answer(200, bytes))
        val percents = mutableListOf<Int>()
        assertTrue(downloader.download(apk, target()) { percents += it })
        assertTrue(bytes.contentEquals(target().readBytes()))
        assertFalse(part().exists())
        assertEquals(listOf(browserUrl), opened.map { it.url.toString() })
        assertNull(opened.single().accept)
        assertEquals(100, percents.last())
    }

    @Test
    fun fallsBackToTheApiUrlAfterAServerError() {
        val downloader = downloader(browserUrl to Answer(504), apiUrl to Answer(200, bytes))
        assertTrue(downloader.download(apk, target()))
        assertTrue(bytes.contentEquals(target().readBytes()))
        assertFalse(part().exists())
        assertEquals(listOf(browserUrl, apiUrl), opened.map { it.url.toString() })
        assertNull(opened[0].accept)
        assertEquals("application/octet-stream", opened[1].accept)
    }

    @Test
    fun fallsBackToTheApiUrlAfterAnIoError() {
        val downloader = downloader(browserUrl to Answer(failure = IOException("connection reset")), apiUrl to Answer(200, bytes))
        assertTrue(downloader.download(apk, target()))
        assertTrue(bytes.contentEquals(target().readBytes()))
        assertEquals(listOf(browserUrl, apiUrl), opened.map { it.url.toString() })
    }

    @Test
    fun aPartialFirstAttemptDoesNotLeakIntoTheFallback() {
        val truncated = object : InputStream() {
            private val inner = ByteArrayInputStream(bytes)
            private var served = 0
            override fun read(): Int = throw IOException("cut")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (served >= 70_000) throw IOException("cut")
                val n = inner.read(b, off, minOf(len, 70_000 - served))
                served += n
                return n
            }
        }
        val byUrl = mapOf(browserUrl to Answer(200), apiUrl to Answer(200, bytes))
        val downloader = ApkDownloader { url ->
            val answer = byUrl.getValue(url.toString())
            if (url.toString() == browserUrl) {
                object : FakeConnection(url, answer) {
                    override fun getInputStream(): InputStream = truncated
                }
            } else {
                FakeConnection(url, answer)
            }
        }
        assertTrue(downloader.download(apk, target()))
        assertEquals(bytes.size.toLong(), target().length())
        assertTrue(bytes.contentEquals(target().readBytes()))
    }

    @Test
    fun doesNotRetryAfterNotFound() {
        val downloader = downloader(browserUrl to Answer(404), apiUrl to Answer(200, bytes))
        assertFalse(downloader.download(apk, target()))
        assertFalse(target().exists())
        assertFalse(part().exists())
        assertEquals(listOf(browserUrl), opened.map { it.url.toString() })
    }

    @Test
    fun doesNotRetryWithoutAnApiUrl() {
        val downloader = downloader(browserUrl to Answer(503))
        assertFalse(downloader.download(apk.copy(apiUrl = null), target()))
        assertFalse(target().exists())
        assertEquals(listOf(browserUrl), opened.map { it.url.toString() })
    }

    @Test
    fun theDigestCheckStillRejectsWrongBytesFromTheApi() {
        val wrong = bytes.copyOf().also { it[10] = (it[10] + 1).toByte() }
        val downloader = downloader(browserUrl to Answer(502), apiUrl to Answer(200, wrong))
        assertFalse(downloader.download(apk, target()))
        assertFalse(target().exists())
        assertFalse(part().exists())
        assertEquals(listOf(browserUrl, apiUrl), opened.map { it.url.toString() })
    }

    @Test
    fun theSizeCheckAppliesWithoutADigest() {
        val noDigest = apk.copy(sha256 = null)
        assertTrue(downloader(browserUrl to Answer(502), apiUrl to Answer(200, bytes)).download(noDigest, target()))
        opened.clear()
        assertFalse(downloader(browserUrl to Answer(502), apiUrl to Answer(200, bytes.copyOf(bytes.size - 1))).download(noDigest, File(folder.root, "short.apk")))
        assertFalse(File(folder.root, "short.apk").exists())
    }
}
