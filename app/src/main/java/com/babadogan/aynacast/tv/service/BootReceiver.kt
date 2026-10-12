package com.babadogan.aynacast.tv.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "Received broadcast action: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            val settings = com.babadogan.aynacast.tv.settings.AppSettingsRepository.getInstance(context).settings.value
            if (!settings.receiverEnabled) {
                Log.i(TAG, "AirPlay receiver is switched off. Skipping service autostart on boot.")
                return
            }
            if (!settings.keepAliveInBackground) {
                Log.i(TAG, "7/24 background mode is disabled in settings. Skipping service autostart on boot.")
                return
            }
            Log.i(TAG, "Starting AirPlayDaemonService automatically after boot.")
            val serviceIntent = Intent(context, AirPlayDaemonService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}
