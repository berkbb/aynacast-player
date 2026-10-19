package com.babadogan.aynacast.tv.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.babadogan.aynacast.tv.ui.AirPlaySettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

const val SELF_RELEASE_API_URL = "https://api.github.com/repos/berkbb/aynacast-player/releases/latest"
const val SELF_RELEASES_PAGE = "github.com/berkbb/aynacast-player/releases"

data class ReleaseApk(val name: String, val url: String, val size: Long, val sha256: String?)

/** Result of checking AynaCast's own GitHub releases. */
sealed interface UpdateCheck {
    data object Checking : UpdateCheck
    data class UpToDate(val current: String) : UpdateCheck
    /** [apk] is the release APK for this TV, or null when the release has none to install. */
    data class Available(val latest: String, val current: String, val apk: ReleaseApk?) : UpdateCheck
    /** No published release, or the repository is not public (GitHub answers 404). */
    data object Unavailable : UpdateCheck
    data object Failed : UpdateCheck
}

/** Downloading and installing a newer AynaCast over itself. */
sealed interface UpdateInstall {
    data object Idle : UpdateInstall
    data class Downloading(val percent: Int) : UpdateInstall
    /** The system installer was started; the TV shows its confirmation prompt. */
    data object WaitingForConfirmation : UpdateInstall
    data object PermissionNeeded : UpdateInstall
    data object DownloadFailed : UpdateInstall
    /** The installed app is signed with a different key: it must be uninstalled first. */
    data object SignatureConflict : UpdateInstall
    data class InstallFailed(val reason: String) : UpdateInstall
}

/** Pure parts of the check, unit-tested. */
object SelfUpdateLogic {

    fun parseVersion(raw: String?): List<Int> {
        val match = Regex("^[vV]?(\\d+(?:\\.\\d+)*)").find(raw?.trim().orEmpty()) ?: return emptyList()
        return match.groupValues[1].split('.').map { it.toIntOrNull() ?: 0 }
    }

    fun isNewer(latest: String, current: String): Boolean {
        val a = parseVersion(latest)
        val b = parseVersion(current)
        if (a.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val diff = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (diff != 0) return diff > 0
        }
        return false
    }

    /** Release APKs are named AynaCastTV-v<version>-<abi>-release.apk; universal is the fallback. */
    fun selectApk(assets: List<ReleaseApk>, supportedAbis: Array<String>): ReleaseApk? {
        val apks = assets.filter { it.name.endsWith("-release.apk") && it.url.isNotBlank() }
        return supportedAbis.asSequence()
            .mapNotNull { abi -> apks.firstOrNull { it.name.endsWith("-$abi-release.apk") } }
            .firstOrNull()
            ?: apks.firstOrNull { it.name.endsWith("-universal-release.apk") }
    }

    /** "sha256:abc…" (GitHub's asset digest) as lowercase hex, or null. */
    fun parseSha256(digest: String?): String? {
        val value = digest?.trim()?.lowercase() ?: return null
        val hex = value.removePrefix("sha256:")
        return if (value.startsWith("sha256:") && hex.length == 64 && hex.all { it in "0123456789abcdef" }) hex else null
    }

    fun checkFor(tag: String, current: String, apk: ReleaseApk?): UpdateCheck {
        val latest = parseVersion(tag)
        if (latest.isEmpty()) return UpdateCheck.Unavailable
        val latestName = latest.joinToString(".")
        return if (isNewer(latestName, current)) UpdateCheck.Available(latestName, current, apk) else UpdateCheck.UpToDate(current)
    }
}

/**
 * Checks GitHub for a newer AynaCast and installs it over the app with the system installer
 * (the TV asks for confirmation); the app reopens after the update.
 */
object SelfUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _check = MutableStateFlow<UpdateCheck?>(null)
    /** Null until a check from About; set back to null when its window closes. */
    val check: StateFlow<UpdateCheck?> = _check.asStateFlow()

    private val _install = MutableStateFlow<UpdateInstall>(UpdateInstall.Idle)
    val install: StateFlow<UpdateInstall> = _install.asStateFlow()

    fun startCheck(currentVersion: String) {
        if (_check.value is UpdateCheck.Checking) return
        _check.value = UpdateCheck.Checking
        _install.value = UpdateInstall.Idle
        scope.launch { _check.value = runCatching { fetch(currentVersion) }.getOrElse { UpdateCheck.Failed } }
    }

    fun dismiss() {
        if (_install.value is UpdateInstall.Downloading) return
        _check.value = null
        _install.value = UpdateInstall.Idle
    }

    /** Back from the "Install unknown apps" setting with the permission: drop the stale warning. */
    fun onResume(context: Context) {
        if (_install.value is UpdateInstall.PermissionNeeded && context.packageManager.canRequestPackageInstalls()) {
            _install.value = UpdateInstall.Idle
        }
    }

    fun installUpdate(context: Context) {
        val update = _check.value as? UpdateCheck.Available ?: return
        val apk = update.apk ?: return
        val app = context.applicationContext
        if (!app.packageManager.canRequestPackageInstalls()) {
            _install.value = UpdateInstall.PermissionNeeded
            runCatching {
                app.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return
        }
        if (job?.isActive == true) return
        job = scope.launch {
            _install.value = UpdateInstall.Downloading(0)
            val target = File(app.cacheDir, apk.name)
            val downloaded = runCatching { download(apk, target) }.getOrDefault(false)
            if (!downloaded) {
                _install.value = UpdateInstall.DownloadFailed
                return@launch
            }
            _install.value = if (installApk(app, target)) UpdateInstall.WaitingForConfirmation
            else UpdateInstall.InstallFailed("")
        }
    }

    internal fun onInstallResult(status: Int, message: String?) {
        _install.value = when (status) {
            PackageInstaller.STATUS_SUCCESS -> UpdateInstall.Idle
            PackageInstaller.STATUS_FAILURE_CONFLICT -> UpdateInstall.SignatureConflict
            else -> if (message?.contains("UPDATE_INCOMPATIBLE") == true || message?.contains("signatures do not match", true) == true) {
                UpdateInstall.SignatureConflict
            } else {
                UpdateInstall.InstallFailed(message.orEmpty())
            }
        }
    }

    private fun fetch(currentVersion: String): UpdateCheck {
        val connection = (URL(SELF_RELEASE_API_URL).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", "AynaCastTV/$currentVersion")
            setRequestProperty("Accept", "application/vnd.github+json")
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        try {
            return when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    val assets = json.optJSONArray("assets")
                    val apks = (0 until (assets?.length() ?: 0)).map { i ->
                        val asset = assets!!.getJSONObject(i)
                        ReleaseApk(
                            name = asset.optString("name", ""),
                            url = asset.optString("browser_download_url", ""),
                            size = asset.optLong("size", 0L),
                            sha256 = SelfUpdateLogic.parseSha256(if (asset.isNull("digest")) null else asset.optString("digest", ""))
                        )
                    }
                    SelfUpdateLogic.checkFor(
                        json.optString("tag_name", ""), currentVersion,
                        SelfUpdateLogic.selectApk(apks, Build.SUPPORTED_ABIS)
                    )
                }
                HttpURLConnection.HTTP_NOT_FOUND -> UpdateCheck.Unavailable
                else -> UpdateCheck.Failed
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Streams into a .part file and keeps it only when its SHA-256 (or size) matches the release. */
    private fun download(apk: ReleaseApk, target: File): Boolean {
        val part = File(target.parentFile, target.name + ".part")
        val connection = (URL(apk.url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return false
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: apk.size
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var last = -1
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        done += read
                        val percent = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
                        if (percent != last) {
                            last = percent
                            _install.value = UpdateInstall.Downloading(percent)
                        }
                    }
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            val valid = if (apk.sha256 != null) sha == apk.sha256 else apk.size > 0 && done == apk.size
            if (!valid) return false
            target.delete()
            return part.renameTo(target)
        } finally {
            connection.disconnect()
            if (part.exists()) part.delete()
        }
    }

    private fun installApk(context: Context, apk: File): Boolean {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        var session: PackageInstaller.Session? = null
        return try {
            val sessionId = installer.createSession(params)
            session = installer.openSession(sessionId)
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            RestartAfterUpdateReceiver.arm(context)
            val pendingIntent = PendingIntent.getBroadcast(
                context, sessionId, Intent(context, InstallResultReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            )
            session.commit(pendingIntent.intentSender)
            true
        } catch (e: Exception) {
            runCatching { session?.abandon() }
            RestartAfterUpdateReceiver.disarm(context)
            false
        } finally {
            runCatching { session?.close() }
        }
    }
}

/** Not exported: only the app's own PendingIntent (handed to the system installer) reaches it. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it) }
            return
        }
        if (status != PackageInstaller.STATUS_SUCCESS) RestartAfterUpdateReceiver.disarm(context)
        SelfUpdater.onInstallResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}

/** Reopens the app after it updated itself; only when the update was started in the app. */
class RestartAfterUpdateReceiver : BroadcastReceiver() {
    companion object {
        private const val PREFS = "self_update"
        private const val KEY_PENDING = "restart_after_update"

        fun arm(context: Context) = prefs(context).edit().putBoolean(KEY_PENDING, true).apply()
        fun disarm(context: Context) = prefs(context).edit().remove(KEY_PENDING).apply()
        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!prefs(context).getBoolean(KEY_PENDING, false)) return
        disarm(context)
        runCatching {
            context.startActivity(Intent(context, AirPlaySettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
