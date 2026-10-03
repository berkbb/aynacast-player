package com.babadogan.aynacast.tv.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.babadogan.aynacast.tv.engine.AirPlayNativeBridge
import com.babadogan.aynacast.tv.engine.AudioPlaybackEngine
import com.babadogan.aynacast.tv.engine.VideoDecoderEngine
import com.babadogan.aynacast.tv.session.AirPlaySessionManager
import com.babadogan.aynacast.tv.ui.AirPlayPlayerActivity
import com.babadogan.aynacast.tv.ui.AirPlaySettingsActivity

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.babadogan.aynacast.tv.util.DeviceIdentityHelper
import com.babadogan.aynacast.tv.util.DisplayHelper

class AirPlayDaemonService : Service(), AirPlayNativeBridge.AirPlayNativeListener {

    companion object {
        private const val TAG = "AirPlayDaemonService"
        private const val NOTIFICATION_CHANNEL_ID = "airplay_daemon_channel"
        private const val NOTIFICATION_ID = 1001
        private const val RTSP_PORT = 7000
    }

    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var nsdManager: NsdManager? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var airplayRegistrationListener: NsdManager.RegistrationListener? = null
    private var raopRegistrationListener: NsdManager.RegistrationListener? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "AirPlay Daemon Service creating.")
        acquireNetworkLocks()
        createNotificationChannel()
        startNetworkMonitoring()
    }

    private fun startNetworkMonitoring() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Network became available, refreshing mDNS registration.")
                unregisterNsdServices()
                registerNsdServices()
            }
        }

        try {
            connectivityManager?.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            Log.w(TAG, "Network callback registration failed", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildForegroundNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildForegroundNotification())
        }

        val deviceName = DeviceIdentityHelper.getAirPlayDeviceName(this)
        val macAddress = DeviceIdentityHelper.getDeviceMacAddress(this)

        // Dynamically detect TV's physical display resolution
        val displayInfo = DisplayHelper.getTvDisplayInfo(this)
        Log.i(TAG, "Detected TV display: ${displayInfo.width}x${displayInfo.height} @ ${displayInfo.refreshRate} fps")

        // Start C++ RTSP core server with dynamic device identity
        AirPlayNativeBridge.initNativeServer(RTSP_PORT, deviceName, macAddress, this)

        // Cap AirPlay mirroring display negotiation to 1080p (1920x1080 @ 60fps).
        // Sending 4K forces the sender into 3616x2032 @ 75fps, exceeding the TV's AVC decoder hardware limits.
        val airPlayWidth = minOf(displayInfo.width, 1920)
        val airPlayHeight = minOf(displayInfo.height, 1080)
        val airPlayFps = 60.0f
        AirPlayNativeBridge.setDisplayResolution(airPlayWidth, airPlayHeight, airPlayFps)
        AirPlaySessionManager.setDisplayResolution(airPlayWidth, airPlayHeight, airPlayFps.toInt())

        // Register mDNS / Bonjour services via Android NsdManager
        registerNsdServices()

        return START_STICKY
    }

    private fun acquireNetworkLocks() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifiManager?.let {
            multicastLock = it.createMulticastLock("AirPlayMulticastLock").apply {
                setReferenceCounted(true)
                acquire()
            }
            val wifiMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = it.createWifiLock(wifiMode, "AirPlayWifiLock").apply {
                setReferenceCounted(true)
                acquire()
            }
        }
    }

    private fun registerNsdServices() {
        nsdManager = getSystemService(Context.NSD_SERVICE) as? NsdManager
        val deviceName = DeviceIdentityHelper.getAirPlayDeviceName(this)
        val macAddress = DeviceIdentityHelper.getDeviceMacAddress(this)
        val raopPrefix = DeviceIdentityHelper.getRaopMacPrefix(macAddress)
        val pkHex = runCatching { AirPlayNativeBridge.getPublicKeyHex() }.getOrDefault("b07727d6f6cd6e08b58ede525ec3cdeaa252ad9f683feb212ef8a205246554e7")

        Log.i(TAG, "Broadcasting AirPlay device name: '$deviceName', Device ID: '$macAddress', PK: '$pkHex'")

        // 1. Register _airplay._tcp
        val airplayServiceInfo = NsdServiceInfo().apply {
            serviceName = deviceName
            serviceType = "_airplay._tcp"
            port = RTSP_PORT
            setAttribute("model", "AppleTV5,3")
            setAttribute("features", "0x5A7FFFF7,0x1E")
            setAttribute("srcvers", "220.68")
            setAttribute("deviceid", macAddress)
            setAttribute("flags", "0x4")
            setAttribute("vv", "2")
            setAttribute("pk", pkHex)
            setAttribute("pi", "2e388006-13ba-4041-9a67-25dd4a43d536")
        }

        airplayRegistrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo?) {
                Log.i(TAG, "_airplay._tcp registered successfully: ${info?.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo?, errorCode: Int) {
                Log.e(TAG, "Failed to register _airplay._tcp, error: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo?) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo?, errorCode: Int) {}
        }

        try {
            nsdManager?.registerService(
                airplayServiceInfo,
                NsdManager.PROTOCOL_DNS_SD,
                airplayRegistrationListener
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception during _airplay._tcp registration", e)
        }

        // 2. Register _raop._tcp
        val raopServiceInfo = NsdServiceInfo().apply {
            serviceName = "$raopPrefix@$deviceName"
            serviceType = "_raop._tcp"
            port = RTSP_PORT
            setAttribute("ch", "2")
            setAttribute("cn", "0,1,2,3")
            setAttribute("da", "true")
            setAttribute("et", "0,3,5")
            setAttribute("md", "0,1,2")
            setAttribute("pw", "false")
            setAttribute("sr", "44100")
            setAttribute("ss", "16")
            setAttribute("tp", "UDP")
            setAttribute("vn", "65537")
            setAttribute("vs", "220.68")
            setAttribute("am", "AppleTV5,3")
            setAttribute("sv", "false")
        }

        raopRegistrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo?) {
                Log.i(TAG, "_raop._tcp registered successfully: ${info?.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo?, errorCode: Int) {
                Log.e(TAG, "Failed to register _raop._tcp, error: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo?) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo?, errorCode: Int) {}
        }

        try {
            nsdManager?.registerService(
                raopServiceInfo,
                NsdManager.PROTOCOL_DNS_SD,
                raopRegistrationListener
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception during _raop._tcp registration", e)
        }
    }

    private fun unregisterNsdServices() {
        try {
            airplayRegistrationListener?.let { nsdManager?.unregisterService(it) }
            raopRegistrationListener?.let { nsdManager?.unregisterService(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering NSD services", e)
        }
    }

    // --- Native Callbacks ---

    override fun onClientConnected(deviceName: String, ipAddress: String) {
        Log.i(TAG, "AirPlay client connected: $deviceName ($ipAddress)")
        AirPlaySessionManager.notifyClientConnected(deviceName, ipAddress)
    }

    override fun onStreamStarted() {
        Log.i(TAG, "AirPlay stream started! Launching AirPlayPlayerActivity on-demand.")
        AirPlaySessionManager.notifyStreamStarted()

        // Launch full-screen Compose TV activity on-demand
        val uiIntent = Intent(this, AirPlayPlayerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(uiIntent)
    }

    override fun onStreamStopped() {
        Log.i(TAG, "AirPlay stream stopped (TEARDOWN).")
        AirPlaySessionManager.notifyStreamStopped()
        VideoDecoderEngine.stopCodec()
        AudioPlaybackEngine.stop()
    }

    override fun onSpsPpsReady(sps: ByteArray, pps: ByteArray) {
        VideoDecoderEngine.onSpsPpsReady(sps, pps)
    }

    override fun onVideoFrame(nalData: ByteArray, pts: Long, isKeyFrame: Boolean) {
        VideoDecoderEngine.feedNalUnit(nalData, pts, isKeyFrame)
    }

    override fun onAudioPacket(pcmData: ByteArray, pts: Long) {
        AudioPlaybackEngine.writePcmData(pcmData, this)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "AirPlay Receiver Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Runs background listener for incoming AirPlay connections."
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val settingsIntent = Intent(this, AirPlaySettingsActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            settingsIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("AirPlay Alıcısı Aktif")
            .setContentText("Cihaz ağda yayınlanıyor (Apple cihazlarından yansıtabilirsiniz)")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onDestroy() {
        Log.i(TAG, "AirPlay Daemon Service destroying.")
        AirPlayNativeBridge.stopNativeServer()
        unregisterNsdServices()
        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        multicastLock?.release()
        wifiLock?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
