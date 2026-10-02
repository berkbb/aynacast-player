package com.babadogan.aynacast.tv.util

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.WindowManager

data class TvDisplayInfo(
    val width: Int,
    val height: Int,
    val refreshRate: Float
)

object DisplayHelper {

    private const val TAG = "DisplayHelper"

    /**
     * Dynamically queries the TV's true physical display resolution and refresh rate.
     *
     * Priority order:
     * 1. sys.display-size system property (Philips / Amlogic TVs expose native 4K panel here even
     *    when Android UI runs at 1080p).
     * 2. Display.supportedModes max physical mode (API 23+) — catches panels with multiple mode entries.
     * 3. Display.mode current physical size (API 23+).
     * 4. Display.getRealSize fallback.
     * 5. 1920×1080 safe default.
     *
     * RefreshRate is always taken from the Android display API (not from sys.display-size).
     */
    fun getTvDisplayInfo(context: Context): TvDisplayInfo {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { context.display }.getOrNull() ?: windowManager?.defaultDisplay
        } else {
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay
        }

        var width = 0
        var height = 0
        var fps = 0f

        // --- Priority 1: sys.display-size (Philips/Amlogic native 4K panel property) ---
        try {
            val sysProp = readSystemProperty("sys.display-size")
            if (!sysProp.isNullOrBlank()) {
                val parts = sysProp.trim().split("x", ignoreCase = true)
                if (parts.size == 2) {
                    val w = parts[0].toIntOrNull() ?: 0
                    val h = parts[1].toIntOrNull() ?: 0
                    if (w > 0 && h > 0) {
                        width = w
                        height = h
                        Log.i(TAG, "sys.display-size panel resolution: ${width}x${height}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read sys.display-size", e)
        }

        // --- Priority 2 & 3: Display.Mode API ---
        if (display != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                // Max supported physical mode (may reveal 4K even if active mode is 1080p)
                val supported = display.supportedModes
                val maxMode = supported?.maxByOrNull { it.physicalWidth * it.physicalHeight }
                val currentMode = display.mode

                // Always get fps from current active mode (most accurate for actual refresh rate)
                fps = currentMode?.refreshRate ?: 0f

                if (maxMode != null && maxMode.physicalWidth > 0 && maxMode.physicalHeight > 0) {
                    Log.i(TAG, "supportedModes max: ${maxMode.physicalWidth}x${maxMode.physicalHeight} @ ${maxMode.refreshRate}Hz")
                    // Use supportedModes max resolution only if sys.display-size didn't give us a larger value
                    if (maxMode.physicalWidth * maxMode.physicalHeight > width * height) {
                        width = maxMode.physicalWidth
                        height = maxMode.physicalHeight
                        if (fps <= 0f) fps = maxMode.refreshRate
                    }
                } else if (currentMode != null && currentMode.physicalWidth > 0 && currentMode.physicalHeight > 0) {
                    Log.i(TAG, "Display.mode current: ${currentMode.physicalWidth}x${currentMode.physicalHeight} @ ${currentMode.refreshRate}Hz")
                    if (currentMode.physicalWidth * currentMode.physicalHeight > width * height) {
                        width = currentMode.physicalWidth
                        height = currentMode.physicalHeight
                        if (fps <= 0f) fps = currentMode.refreshRate
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error querying Display.Mode", e)
            }
        }

        // --- Priority 4: getRealSize fallback ---
        if ((width <= 0 || height <= 0) && display != null) {
            try {
                val point = android.graphics.Point()
                @Suppress("DEPRECATION")
                display.getRealSize(point)
                if (point.x > 0 && point.y > 0) {
                    width = point.x
                    height = point.y
                    Log.i(TAG, "getRealSize fallback: ${width}x${height}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error getting display real size", e)
            }
        }

        // Get fps from display if still missing
        if (fps <= 0f && display != null) {
            @Suppress("DEPRECATION")
            fps = display.refreshRate
        }

        // --- Priority 5: Safe defaults ---
        if (width <= 0) width = 1920
        if (height <= 0) height = 1080
        if (fps <= 0f) fps = 50.0f

        Log.i(TAG, "Final TV display resolution: ${width}x${height} @ ${fps} fps")
        return TvDisplayInfo(width, height, fps)
    }

    /**
     * Reads an Android system property via reflection (equivalent to getprop on shell).
     * Works without root — these are accessible via SystemProperties on Android.
     */
    private fun readSystemProperty(key: String): String? {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java, String::class.java)
            method.invoke(null, key, null) as? String
        } catch (e: Exception) {
            null
        }
    }
}
