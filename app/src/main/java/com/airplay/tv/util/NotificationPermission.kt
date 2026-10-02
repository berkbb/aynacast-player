package com.airplay.tv.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit

fun Activity.requestNotificationPermissionOnce() {
    if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    ) return

    val preferences = getSharedPreferences("airplay_permissions", Context.MODE_PRIVATE)
    if (preferences.getBoolean("notifications_requested", false)) return
    preferences.edit { putBoolean("notifications_requested", true) }
    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
}
