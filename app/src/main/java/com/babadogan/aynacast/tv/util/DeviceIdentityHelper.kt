package com.babadogan.aynacast.tv.util

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale

object DeviceIdentityHelper {

    private const val PREFS_NAME = "airplay_device_prefs"
    private const val KEY_CUSTOM_DEVICE_NAME = "custom_device_name"

    /**
     * Resolves the real human-readable device name of this Android TV / device.
     * Order of resolution:
     * 1. User-customized name in app settings.
     * 2. System Global device_name (e.g. "Salon TV", "Living Room TV", "Arçelik TV").
     * 3. Bluetooth Adapter device name.
     * 4. Build Model & Manufacturer.
     */
    fun getAirPlayDeviceName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val customName = prefs.getString(KEY_CUSTOM_DEVICE_NAME, null)
        if (!customName.isNullOrBlank()) {
            return customName.trim()
        }

        // Try Settings.Global.device_name (standard for Android 7.0+ & Android TV)
        try {
            val globalName = Settings.Global.getString(context.contentResolver, "device_name")
            if (!globalName.isNullOrBlank()) {
                return globalName.trim()
            }
        } catch (_: Exception) {}

        // Try Settings.Secure.bluetooth_name
        try {
            val secureName = Settings.Secure.getString(context.contentResolver, "bluetooth_name")
            if (!secureName.isNullOrBlank()) {
                return secureName.trim()
            }
        } catch (_: Exception) {}

        // Try BluetoothAdapter.name
        try {
            val btAdapter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
            } else {
                @Suppress("DEPRECATION")
                BluetoothAdapter.getDefaultAdapter()
            }
            val btName = btAdapter?.name
            if (!btName.isNullOrBlank()) {
                return btName.trim()
            }
        } catch (_: Exception) {}

        // Fallback: Device Manufacturer and Model
        val model = Build.MODEL ?: "Android TV"
        val manufacturer = Build.MANUFACTURER ?: ""
        return if (manufacturer.isNotBlank() && !model.startsWith(manufacturer, ignoreCase = true)) {
            "$manufacturer $model"
        } else {
            model
        }
    }

    /**
     * Saves user-customized device name.
     */
    fun setCustomDeviceName(context: Context, name: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CUSTOM_DEVICE_NAME, name.trim()).apply()
    }

    /**
     * Retrieves or generates a consistent MAC address string for AirPlay Device ID (e.g. "00:11:22:33:44:55").
     */
    fun getDeviceMacAddress(context: Context): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.name.equals("wlan0", ignoreCase = true) || intf.name.equals("eth0", ignoreCase = true)) {
                    val mac = intf.hardwareAddress ?: continue
                    val sb = StringBuilder()
                    for (b in mac) {
                        sb.append(String.format("%02X:", b))
                    }
                    if (sb.isNotEmpty()) sb.deleteCharAt(sb.length - 1)
                    return sb.toString().uppercase(Locale.ROOT)
                }
            }
        } catch (_: Exception) {}

        // Fallback deterministic MAC derived from ANDROID_ID
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull() ?: "0123456789ABCDEF"

        val hex = androidId.padEnd(12, '0').take(12).uppercase(Locale.ROOT)
        return hex.chunked(2).joinToString(":")
    }

    /**
     * Formats MAC without colons for RAOP service prefix (e.g. "001122334455").
     */
    fun getRaopMacPrefix(mac: String): String {
        return mac.replace(":", "").replace("-", "").uppercase(Locale.ROOT)
    }
}
