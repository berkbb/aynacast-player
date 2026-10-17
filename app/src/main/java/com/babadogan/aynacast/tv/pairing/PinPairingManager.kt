package com.babadogan.aynacast.tv.pairing

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * On-screen PIN pairing (UxPlay "-pin"): shows a 4-digit code when a sender asks for it, runs the
 * SRP exchange and remembers the Ed25519 keys of devices that entered the right code, so they are
 * only asked once.
 *
 * Pairing only works against a code that is on screen: it expires after [PIN_LIFETIME_MS], allows
 * [MAX_ATTEMPTS_PER_PIN] wrong entries, and repeated failures lock pairing for [LOCKOUT_MS], so
 * the 10,000 codes cannot be tried from the network.
 */
object PinPairingManager {

    private const val TAG = "PinPairingManager"
    private const val PREFS_NAME = "airplay_paired_devices"
    private const val KEY_DEVICES = "paired_ed25519_keys"
    // Per device (suffixed with its Base64 key): the name it connected with and when it paired.
    private const val KEY_NAME_PREFIX = "name:"
    private const val KEY_MODEL_PREFIX = "model:"
    private const val KEY_PAIRED_AT_PREFIX = "paired_at:"
    // A sender reports its name only when it starts streaming, right after pairing.
    private const val NAME_AFTER_PAIRING_MS = 30_000L
    // Apple devices give up on the code prompt after a while; do not leave the code on screen.
    const val PIN_LIFETIME_MS = 120_000L
    private const val MAX_ATTEMPTS_PER_PIN = 3
    private const val MAX_CONSECUTIVE_FAILURES = 9
    private const val LOCKOUT_MS = 5 * 60_000L
    // Paired-device key of a classic AirPlay sender: this prefix + its DACP-ID (not an Ed25519 key).
    private const val CLASSIC_KEY_PREFIX = "classic-dacp:"

    private val random = SecureRandom()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val expireRunnable = Runnable { expireIfDue() }
    private var prefs: SharedPreferences? = null
    private var server: PinPairingServer? = null
    private var attemptsLeft = 0
    private var expiresAtMs = 0L
    private var consecutiveFailures = 0
    private var lockedUntilMs = 0L

    /** Why the last code left the screen. */
    enum class Outcome { PAIRED, EXPIRED, WRONG_CODE, LOCKED, CANCELLED }

    private val _outcome = MutableStateFlow<Outcome?>(null)
    /** Set when a code leaves the screen, cleared when the next one is shown. */
    val outcome: StateFlow<Outcome?> = _outcome.asStateFlow()

    private val _pinCode = MutableStateFlow<String?>(null)
    /** PIN to show on screen, or null when no pairing is in progress. */
    val pinCode: StateFlow<String?> = _pinCode.asStateFlow()

    private val _pairedDevices = MutableStateFlow<List<ByteArray>>(emptyList())
    val pairedDevices: StateFlow<List<ByteArray>> = _pairedDevices.asStateFlow()

    /** A paired device as listed in the settings. [name] is null for devices paired before names were kept. */
    class PairedDevice(val key: ByteArray, val name: String?, val pairedAtMs: Long, val model: String? = null)

    private val _pairedDeviceDetails = MutableStateFlow<List<PairedDevice>>(emptyList())
    val pairedDeviceDetails: StateFlow<List<PairedDevice>> = _pairedDeviceDetails.asStateFlow()

    private var lastPairedKey: ByteArray? = null
    private var lastPairedAtElapsedMs = 0L

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = preferences
        _pairedDevices.value = preferences.getStringSet(KEY_DEVICES, emptySet()).orEmpty()
            .mapNotNull { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
        refreshDetails()
    }

    /** /pair-pin-start: a new random code (0001-9999) for this attempt, or null while locked out. */
    @Synchronized
    fun startPairing(): String? {
        if (SystemClock.elapsedRealtime() < lockedUntilMs) {
            Log.w(TAG, "PIN pairing refused: too many wrong codes, locked for a while")
            return null
        }
        val pin = "%04d".format(random.nextInt(9999) + 1)
        server = PinPairingServer(pin)
        attemptsLeft = MAX_ATTEMPTS_PER_PIN
        expiresAtMs = SystemClock.elapsedRealtime() + PIN_LIFETIME_MS
        _outcome.value = null
        _pinCode.value = pin
        handler.removeCallbacks(expireRunnable)
        handler.postDelayed(expireRunnable, PIN_LIFETIME_MS)
        Log.i(TAG, "PIN pairing started")
        return pin
    }

    /** True while a code is on screen and can be entered. */
    @Synchronized
    fun isPairing(): Boolean = server != null

    /**
     * Runs one /pair-setup-pin step. Step 1: [] -> [B, salt]; step 2: [A, M1] -> [M2];
     * step 3: [epk, authTag, serverPk] -> [epk, authTag, clientPk]. Returns null when the step fails.
     */
    @Synchronized
    fun handleStep(step: Int, user: String, input: Array<ByteArray>): Array<ByteArray>? {
        return when (step) {
            1 -> {
                // Never pair against a code nobody can see (UxPlay's "0000" fallback would let any
                // LAN host pair silently).
                val pairing = server ?: return null
                val result = pairing.start(user)
                arrayOf(result.serverPublicKey, result.salt)
            }
            2 -> {
                val pairing = server ?: return null
                val proof = input.takeIf { it.size >= 2 }?.let { pairing.verify(it[0], it[1]) }
                if (proof == null) {
                    registerFailure()
                    null
                } else {
                    consecutiveFailures = 0
                    arrayOf(proof)
                }
            }
            3 -> {
                val result = input.takeIf { it.size >= 3 }?.let { server?.exchangeKeys(it[0], it[1], it[2]) }
                    ?: return null
                remember(result.clientEd25519Key)
                endPairing(Outcome.PAIRED)
                Log.i(TAG, "Device paired with PIN")
                arrayOf(result.encryptedServerKey, result.authTag, result.clientEd25519Key)
            }
            else -> null
        }
    }

    /**
     * Classic AirPlay senders (the macOS Music app) cannot run the SRP exchange; they answer an HTTP
     * Digest challenge with the code typed into their password prompt instead. A sender that once
     * entered the code is remembered by [senderId] (its DACP-ID) and listed with the paired devices.
     * Weaker than SRP pairing: the DACP-ID proves nothing, a LAN host could reuse a seen one.
     */
    @Synchronized
    fun checkClassicDigest(
        senderId: String, username: String, realm: String, nonce: String,
        method: String, uri: String, response: String
    ): Boolean {
        val key = if (senderId.isNotEmpty()) (CLASSIC_KEY_PREFIX + senderId).toByteArray() else null
        if (key != null && _pairedDevices.value.any { it.contentEquals(key) }) return true
        val pin = _pinCode.value
        if (response.isEmpty() || pin == null || server == null) return false
        val ha1 = md5Hex("$username:$realm:$pin")
        val ha2 = md5Hex("$method:$uri")
        if (md5Hex("$ha1:$nonce:$ha2") != response.lowercase()) {
            registerFailure()
            return false
        }
        consecutiveFailures = 0
        if (key != null) remember(key)
        endPairing(Outcome.PAIRED)
        Log.i(TAG, "Classic AirPlay sender paired with PIN")
        return true
    }

    private fun md5Hex(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Hides the code (e.g. the sender gave up, or it expired). */
    @Synchronized
    fun cancelPairing() {
        endPairing(Outcome.CANCELLED)
    }

    // The runnable can already be running when a new code replaces the old one: only a code that
    // really reached its deadline is cancelled.
    @Synchronized
    private fun expireIfDue() {
        if (server != null && SystemClock.elapsedRealtime() >= expiresAtMs) {
            Log.i(TAG, "PIN code expired")
            endPairing(Outcome.EXPIRED)
        }
    }

    private fun endPairing(outcome: Outcome) {
        handler.removeCallbacks(expireRunnable)
        server = null
        attemptsLeft = 0
        // Only a code that was on screen has an outcome to show.
        if (_pinCode.value != null) _outcome.value = outcome
        _pinCode.value = null
    }

    private fun registerFailure() {
        consecutiveFailures++
        attemptsLeft--
        Log.w(TAG, "Wrong PIN entered on the sender ($attemptsLeft attempts left for this code)")
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            consecutiveFailures = 0
            lockedUntilMs = SystemClock.elapsedRealtime() + LOCKOUT_MS
            Log.w(TAG, "Too many wrong codes; PIN pairing locked for ${LOCKOUT_MS / 1000} s")
            endPairing(Outcome.LOCKED)
        } else if (attemptsLeft <= 0) {
            endPairing(Outcome.WRONG_CODE)
        }
    }

    /**
     * A sender started streaming: names the device that paired just before, if any, and records
     * the model of whichever paired device connected, so devices paired earlier learn theirs too.
     */
    @Synchronized
    fun onClientConnected(deviceName: String, model: String, clientKey: ByteArray?) {
        val justPaired = lastPairedKey
            ?.takeIf { SystemClock.elapsedRealtime() - lastPairedAtElapsedMs <= NAME_AFTER_PAIRING_MS }
        lastPairedKey = null
        val known = clientKey?.let { key -> _pairedDevices.value.firstOrNull { it.contentEquals(key) } }
        val nameKey = justPaired?.takeIf { deviceName.isNotBlank() }
        val modelKey = (known ?: justPaired)?.takeIf { model.isNotBlank() }
        if (nameKey == null && modelKey == null) return
        prefs?.edit {
            if (nameKey != null) putString(KEY_NAME_PREFIX + encode(nameKey), deviceName)
            if (modelKey != null) putString(KEY_MODEL_PREFIX + encode(modelKey), model)
        }
        refreshDetails()
    }

    @Synchronized
    fun forgetAllDevices() {
        prefs?.edit {
            remove(KEY_DEVICES)
            _pairedDevices.value.forEach { removeDetails(encode(it)) }
        }
        _pairedDevices.value = emptyList()
        refreshDetails()
    }

    /** That device has to enter a code again the next time it connects. */
    @Synchronized
    fun forgetDevice(key: ByteArray) {
        val updated = _pairedDevices.value.filterNot { it.contentEquals(key) }
        if (updated.size == _pairedDevices.value.size) return
        _pairedDevices.value = updated
        prefs?.edit {
            putStringSet(KEY_DEVICES, updated.map { encode(it) }.toSet())
            removeDetails(encode(key))
        }
        refreshDetails()
    }

    private fun remember(key: ByteArray) {
        lastPairedKey = key
        lastPairedAtElapsedMs = SystemClock.elapsedRealtime()
        if (_pairedDevices.value.any { it.contentEquals(key) }) return
        val updated = _pairedDevices.value + key
        _pairedDevices.value = updated
        prefs?.edit {
            putStringSet(KEY_DEVICES, updated.map { encode(it) }.toSet())
            putLong(KEY_PAIRED_AT_PREFIX + encode(key), System.currentTimeMillis())
        }
        refreshDetails()
    }

    private fun SharedPreferences.Editor.removeDetails(encodedKey: String) {
        remove(KEY_NAME_PREFIX + encodedKey)
        remove(KEY_MODEL_PREFIX + encodedKey)
        remove(KEY_PAIRED_AT_PREFIX + encodedKey)
    }

    private fun refreshDetails() {
        val preferences = prefs
        _pairedDeviceDetails.value = _pairedDevices.value.map { key ->
            val encoded = encode(key)
            PairedDevice(
                key = key,
                name = preferences?.getString(KEY_NAME_PREFIX + encoded, null),
                pairedAtMs = preferences?.getLong(KEY_PAIRED_AT_PREFIX + encoded, 0L) ?: 0L,
                model = preferences?.getString(KEY_MODEL_PREFIX + encoded, null)
            )
        }.sortedByDescending { it.pairedAtMs }
    }

    private fun encode(key: ByteArray): String = Base64.encodeToString(key, Base64.NO_WRAP)
}
