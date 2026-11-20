package com.babadogan.aynacast.tv.util

import android.os.Environment
import android.os.StatFs
import java.util.Locale
import kotlin.math.roundToInt

/** Available and total internal data storage on the TV (no permissions required). */
data class StorageSummary(val freeBytes: Long, val totalBytes: Long)

object StorageFormat {
    fun formatBytes(bytes: Long, locale: Locale = Locale.ROOT): String {
        val safe = bytes.coerceAtLeast(0L)
        return if (safe < 1_000_000_000L) "${safe / 1_000_000L} MB"
        else String.format(locale, "%.1f GB", safe / 1_000_000_000.0)
    }

    fun usedPercent(freeBytes: Long, totalBytes: Long): Int {
        if (totalBytes <= 0L) return 0
        val used = (totalBytes - freeBytes).coerceIn(0L, totalBytes)
        return ((used * 100.0) / totalBytes).roundToInt().coerceIn(0, 100)
    }
}

fun readDeviceStorage(): StorageSummary? = runCatching {
    val stat = StatFs(Environment.getDataDirectory().path)
    StorageSummary(freeBytes = stat.availableBytes, totalBytes = stat.totalBytes)
}.getOrNull()
