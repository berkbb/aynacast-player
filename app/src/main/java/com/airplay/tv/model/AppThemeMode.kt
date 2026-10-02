package com.airplay.tv.model

enum class AppThemeMode {
    SYSTEM,
    DARK,
    LIGHT;

    companion object {
        val DEFAULT = SYSTEM

        fun fromString(value: String?): AppThemeMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: DEFAULT
    }
}
