package com.babadogan.aynacast.tv.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.babadogan.aynacast.tv.model.AppLanguage
import com.babadogan.aynacast.tv.model.AppThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val language: AppLanguage = AppLanguage.DEFAULT,
    val themeMode: AppThemeMode = AppThemeMode.DEFAULT,
    val keepAliveInBackground: Boolean = DEFAULT_KEEP_ALIVE,
    val avSyncEnabled: Boolean = DEFAULT_AV_SYNC,
    val pinRequired: Boolean = DEFAULT_PIN_REQUIRED,
    /** Kill switch: false keeps the receiver off (not advertised, no connections) until turned on again. */
    val receiverEnabled: Boolean = DEFAULT_RECEIVER_ENABLED
) {
    companion object {
        const val DEFAULT_KEEP_ALIVE = true
        // UxPlay recommends no timestamp sync for hardware-decoding receivers and macOS mirroring.
        const val DEFAULT_AV_SYNC = false
        // Opt-in like UxPlay "-pin": a broken pairing would otherwise block every connection.
        const val DEFAULT_PIN_REQUIRED = false
        const val DEFAULT_RECEIVER_ENABLED = true
    }
}

class AppSettingsRepository(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "airplay_app_settings"
        private const val KEY_LANGUAGE = "app_language"
        private const val KEY_THEME = "app_theme_mode"
        private const val KEY_KEEP_ALIVE = "app_keep_alive_background"
        private const val KEY_AV_SYNC = "app_av_sync_timestamps"
        private const val KEY_PIN_REQUIRED = "app_pin_required"
        private const val KEY_RECEIVER_ENABLED = "app_receiver_enabled"

        @Volatile
        private var instance: AppSettingsRepository? = null

        fun getInstance(context: Context): AppSettingsRepository =
            instance ?: synchronized(this) {
                instance ?: AppSettingsRepository(context.applicationContext).also { instance = it }
            }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun loadSettings(): AppSettings {
        val langCode = prefs.getString(KEY_LANGUAGE, AppLanguage.DEFAULT.code)
        val themeStr = prefs.getString(KEY_THEME, AppThemeMode.DEFAULT.name)
        val keepAlive = prefs.getBoolean(KEY_KEEP_ALIVE, AppSettings.DEFAULT_KEEP_ALIVE)
        val avSync = prefs.getBoolean(KEY_AV_SYNC, AppSettings.DEFAULT_AV_SYNC)
        val pinRequired = prefs.getBoolean(KEY_PIN_REQUIRED, AppSettings.DEFAULT_PIN_REQUIRED)

        return AppSettings(
            language = AppLanguage.fromCode(langCode),
            themeMode = AppThemeMode.fromString(themeStr),
            keepAliveInBackground = keepAlive,
            avSyncEnabled = avSync,
            pinRequired = pinRequired,
            receiverEnabled = prefs.getBoolean(KEY_RECEIVER_ENABLED, AppSettings.DEFAULT_RECEIVER_ENABLED)
        )
    }

    fun setLanguage(language: AppLanguage) {
        prefs.edit { putString(KEY_LANGUAGE, language.code) }
        _settings.value = _settings.value.copy(language = language)
    }

    fun setThemeMode(themeMode: AppThemeMode) {
        prefs.edit { putString(KEY_THEME, themeMode.name) }
        _settings.value = _settings.value.copy(themeMode = themeMode)
    }

    fun setKeepAliveInBackground(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_KEEP_ALIVE, enabled) }
        _settings.value = _settings.value.copy(keepAliveInBackground = enabled)
    }

    fun setPinRequired(required: Boolean) {
        prefs.edit { putBoolean(KEY_PIN_REQUIRED, required) }
        _settings.value = _settings.value.copy(pinRequired = required)
    }

    fun setReceiverEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_RECEIVER_ENABLED, enabled) }
        _settings.value = _settings.value.copy(receiverEnabled = enabled)
    }

    fun setAvSyncEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_AV_SYNC, enabled) }
        _settings.value = _settings.value.copy(avSyncEnabled = enabled)
    }
}
