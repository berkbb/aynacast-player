package com.babadogan.aynacast.tv.util

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale

object DeviceIdentityHelper {

    private const val PREFS_NAME = "airplay_device_prefs"
    private const val KEY_CUSTOM_DEVICE_NAME = "custom_device_name"

    /** DNS-SD instance names are a single DNS label (max 63 bytes). The RAOP name adds "<12 hex>@". */
    internal const val MAX_DEVICE_NAME_BYTES = 63 - 13

    /** MAC returned by Android 6+ to apps that may not read the real hardware address. */
    private val PLACEHOLDER_MAC = byteArrayOf(0x02, 0, 0, 0, 0, 0)

    /**
     * Resolves the real human-readable device name of this Android TV / device.
     * Order of resolution:
     * 1. User-customized name in app settings.
     * 2. System Global device_name (e.g. "Salon TV", "Living Room TV", "Arçelik TV").
     * 3. Bluetooth Adapter device name.
     * 4. Build Model & Manufacturer.
     * The result always fits the mDNS service-name limits.
     */
    fun getAirPlayDeviceName(context: Context): String = sanitizeDeviceName(resolveDeviceName(context))

    private fun resolveDeviceName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val customName = prefs.getString(KEY_CUSTOM_DEVICE_NAME, null)
        if (!customName.isNullOrBlank()) {
            return customName
        }

        // Try Settings.Global.device_name (standard for Android 7.0+ & Android TV)
        try {
            val globalName = Settings.Global.getString(context.contentResolver, "device_name")
            if (!globalName.isNullOrBlank()) {
                return globalName
            }
        } catch (_: Exception) {}

        // Try Settings.Secure.bluetooth_name
        try {
            val secureName = Settings.Secure.getString(context.contentResolver, "bluetooth_name")
            if (!secureName.isNullOrBlank()) {
                return secureName
            }
        } catch (_: Exception) {}

        readBluetoothAdapterName(context)?.let { return it }

        // Fallback: Device Manufacturer and Model
        val model = Build.MODEL ?: "Android TV"
        val manufacturer = Build.MANUFACTURER ?: ""
        return if (manufacturer.isNotBlank() && !model.startsWith(manufacturer, ignoreCase = true)) {
            "$manufacturer $model"
        } else {
            model
        }
    }

    // BluetoothAdapter.getName needs BLUETOOTH_CONNECT on Android 12+; it is checked explicitly below.
    @SuppressLint("MissingPermission")
    private fun readBluetoothAdapterName(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        return try {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            adapter?.name?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Saves user-customized device name.
     */
    fun setCustomDeviceName(context: Context, name: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { putString(KEY_CUSTOM_DEVICE_NAME, name.trim()) }
    }

    /**
     * Retrieves or generates a consistent MAC address string for AirPlay Device ID (e.g. "00:11:22:33:44:55").
     */
    @SuppressLint("HardwareIds")
    fun getDeviceMacAddress(context: Context): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.name.equals("wlan0", ignoreCase = true) || intf.name.equals("eth0", ignoreCase = true)) {
                    val mac = intf.hardwareAddress
                    if (isUsableMac(mac)) {
                        return formatMac(mac!!)
                    }
                }
            }
        } catch (_: Exception) {}

        // Android 11+ hides hardware MACs (and 6-10 may report 02:00:00:00:00:00), so derive a stable ID.
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull() ?: "0123456789ABCDEF"
        return deriveMacFromId(androidId)
    }

    /**
     * Formats MAC without colons for RAOP service prefix (e.g. "001122334455").
     */
    fun getRaopMacPrefix(mac: String): String {
        return mac.replace(":", "").replace("-", "").uppercase(Locale.ROOT)
    }

    /**
     * MAC prefix of the classic AirPlay (RSA) record: the receiver's MAC with the lowest bit of the
     * last byte flipped, so macOS lists it as a separate speaker (with the same MAC it merges it into
     * the main receiver and uses FairPlay 2). raop_rtsp_server.cpp signs Apple-Challenge with it.
     */
    fun getClassicRaopMacPrefix(mac: String): String {
        val prefix = getRaopMacPrefix(mac)
        if (prefix.length != 12) return prefix
        val last = prefix.substring(10).toInt(16) xor 0x01
        return prefix.substring(0, 10) + String.format(Locale.ROOT, "%02X", last)
    }

    internal fun isUsableMac(mac: ByteArray?): Boolean =
        mac != null && mac.size == 6 && mac.any { it != 0.toByte() } && !mac.contentEquals(PLACEHOLDER_MAC)

    internal fun formatMac(mac: ByteArray): String =
        mac.joinToString(":") { String.format(Locale.ROOT, "%02X", it.toInt() and 0xFF) }

    /** Deterministic, locally administered unicast MAC derived from a device identifier. */
    internal fun deriveMacFromId(id: String): String {
        val hex = id.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }.padEnd(12, '0').take(12)
        val bytes = ByteArray(6) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        // Clear the multicast bit and set the locally-administered bit.
        bytes[0] = ((bytes[0].toInt() and 0xFE) or 0x02).toByte()
        return formatMac(bytes)
    }

    /** Trims, strips control characters and truncates to [MAX_DEVICE_NAME_BYTES] UTF-8 bytes. */
    internal fun sanitizeDeviceName(name: String): String {
        val cleaned = name.filter { !it.isISOControl() }.trim()
        if (cleaned.isEmpty()) return "Android TV"
        if (cleaned.toByteArray(Charsets.UTF_8).size <= MAX_DEVICE_NAME_BYTES) return cleaned

        val builder = StringBuilder()
        var bytes = 0
        var index = 0
        while (index < cleaned.length) {
            val codePoint = cleaned.codePointAt(index)
            val chunk = String(Character.toChars(codePoint))
            val chunkBytes = chunk.toByteArray(Charsets.UTF_8).size
            if (bytes + chunkBytes > MAX_DEVICE_NAME_BYTES) break
            builder.append(chunk)
            bytes += chunkBytes
            index += Character.charCount(codePoint)
        }
        return builder.toString().trim()
    }
}
