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
    // Apple devices give up on the code prompt after a while; do not leave the code on screen.
    const val PIN_LIFETIME_MS = 120_000L
    private const val MAX_ATTEMPTS_PER_PIN = 3
    private const val MAX_CONSECUTIVE_FAILURES = 9
    private const val LOCKOUT_MS = 5 * 60_000L

    private val random = SecureRandom()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val expireRunnable = Runnable { expireIfDue() }
    private var prefs: SharedPreferences? = null
    private var server: PinPairingServer? = null
    private var attemptsLeft = 0
    private var expiresAtMs = 0L
    private var consecutiveFailures = 0
    private var lockedUntilMs = 0L

    private val _pinCode = MutableStateFlow<String?>(null)
    /** PIN to show on screen, or null when no pairing is in progress. */
    val pinCode: StateFlow<String?> = _pinCode.asStateFlow()

    private val _pairedDevices = MutableStateFlow<List<ByteArray>>(emptyList())
    val pairedDevices: StateFlow<List<ByteArray>> = _pairedDevices.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = preferences
        _pairedDevices.value = preferences.getStringSet(KEY_DEVICES, emptySet()).orEmpty()
            .mapNotNull { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
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
                endPairing()
                Log.i(TAG, "Device paired with PIN")
                arrayOf(result.encryptedServerKey, result.authTag, result.clientEd25519Key)
            }
            else -> null
        }
    }

    /** Hides the code (e.g. the sender gave up, or it expired). */
    @Synchronized
    fun cancelPairing() {
        endPairing()
    }

    // The runnable can already be running when a new code replaces the old one: only a code that
    // really reached its deadline is cancelled.
    @Synchronized
    private fun expireIfDue() {
        if (server != null && SystemClock.elapsedRealtime() >= expiresAtMs) {
            Log.i(TAG, "PIN code expired")
            endPairing()
        }
    }

    private fun endPairing() {
        handler.removeCallbacks(expireRunnable)
        server = null
        attemptsLeft = 0
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
            endPairing()
        } else if (attemptsLeft <= 0) {
            endPairing()
        }
    }

    @Synchronized
    fun forgetAllDevices() {
        prefs?.edit { remove(KEY_DEVICES) }
        _pairedDevices.value = emptyList()
    }

    private fun remember(key: ByteArray) {
        if (_pairedDevices.value.any { it.contentEquals(key) }) return
        val updated = _pairedDevices.value + key
        _pairedDevices.value = updated
        prefs?.edit { putStringSet(KEY_DEVICES, updated.map { Base64.encodeToString(it, Base64.NO_WRAP) }.toSet()) }
    }
}
