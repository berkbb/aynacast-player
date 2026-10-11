package com.babadogan.aynacast.tv.pairing

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import java.security.SecureRandom

/**
 * Keeps the receiver's Ed25519 identity across restarts (UxPlay "-key"). Apple senders remember
 * the key a receiver presented while pairing with the PIN; a new key on every start would make
 * them pair again (or fail) and change the advertised "pk".
 */
object ServerIdentityStore {

    private const val PREFS_NAME = "airplay_identity"
    private const val KEY_SEED = "ed25519_seed"
    private const val SEED_BYTES = 32

    /** The stored 32-byte seed, created on first use. */
    @Synchronized
    fun seed(context: Context): ByteArray {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_SEED, null)
            ?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
            ?.takeIf { it.size == SEED_BYTES }
            ?.let { return it }

        val seed = ByteArray(SEED_BYTES).also { SecureRandom().nextBytes(it) }
        prefs.edit(commit = true) { putString(KEY_SEED, Base64.encodeToString(seed, Base64.NO_WRAP)) }
        return seed
    }
}
