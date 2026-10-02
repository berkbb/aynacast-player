package com.babadogan.aynacast.tv.util

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

@Suppress("DEPRECATION")
internal fun Context.isTelevisionDevice(): Boolean = usesTelevisionUi(
    uiMode = resources.configuration.uiMode,
    hasLeanback = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK),
    hasTelevision = packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
)

internal fun usesTelevisionUi(uiMode: Int, hasLeanback: Boolean, hasTelevision: Boolean): Boolean =
    uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION ||
        hasLeanback || hasTelevision
