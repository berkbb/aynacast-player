package com.airplay.tv.settings

import android.content.Context
import android.content.SharedPreferences
import com.airplay.tv.model.AppLanguage
import com.airplay.tv.model.AppThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val language: AppLanguage = AppLanguage.DEFAULT,
    val themeMode: AppThemeMode = AppThemeMode.DEFAULT,
    val keepAliveInBackground: Boolean = DEFAULT_KEEP_ALIVE
) {
    companion object {
        const val DEFAULT_KEEP_ALIVE = true
    }
}

class AppSettingsRepository(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "airplay_app_settings"
        private const val KEY_LANGUAGE = "app_language"
        private const val KEY_THEME = "app_theme_mode"
        private const val KEY_KEEP_ALIVE = "app_keep_alive_background"

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

        return AppSettings(
            language = AppLanguage.fromCode(langCode),
            themeMode = AppThemeMode.fromString(themeStr),
            keepAliveInBackground = keepAlive
        )
    }

    fun setLanguage(language: AppLanguage) {
        prefs.edit().putString(KEY_LANGUAGE, language.code).apply()
        _settings.value = _settings.value.copy(language = language)
    }

    fun setThemeMode(themeMode: AppThemeMode) {
        prefs.edit().putString(KEY_THEME, themeMode.name).apply()
        _settings.value = _settings.value.copy(themeMode = themeMode)
    }

    fun setKeepAliveInBackground(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_ALIVE, enabled).apply()
        _settings.value = _settings.value.copy(keepAliveInBackground = enabled)
    }
}
