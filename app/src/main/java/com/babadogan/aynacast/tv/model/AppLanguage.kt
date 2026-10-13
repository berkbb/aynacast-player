package com.babadogan.aynacast.tv.model

enum class AppLanguage(val code: String, val displayName: String) {
    TR("tr", "Türkçe"),
    EN("en", "English"),
    RO("ro", "Română");

    companion object {
        val DEFAULT = TR

        fun fromCode(code: String?): AppLanguage =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: DEFAULT
    }
}
