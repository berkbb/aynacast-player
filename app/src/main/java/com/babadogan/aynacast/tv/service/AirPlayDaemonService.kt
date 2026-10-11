package com.babadogan.aynacast.tv.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.babadogan.aynacast.tv.R
import com.babadogan.aynacast.tv.engine.AirPlayAudioFormat
import com.babadogan.aynacast.tv.engine.AirPlayFeatures
import com.babadogan.aynacast.tv.engine.AirPlayNativeBridge
import com.babadogan.aynacast.tv.engine.AudioPlaybackEngine
import com.babadogan.aynacast.tv.engine.AvSync
import com.babadogan.aynacast.tv.engine.VideoDecoderEngine
import com.babadogan.aynacast.tv.model.AppLanguage
import com.babadogan.aynacast.tv.pairing.PinPairingManager
import com.babadogan.aynacast.tv.session.AirPlaySessionManager
import com.babadogan.aynacast.tv.pairing.ServerIdentityStore
import com.babadogan.aynacast.tv.session.VideoCastSession
import com.babadogan.aynacast.tv.settings.AppSettingsRepository
import com.babadogan.aynacast.tv.ui.AirPlayPlayerActivity
import com.babadogan.aynacast.tv.ui.AirPlaySettingsActivity
import com.babadogan.aynacast.tv.ui.NowPlayingActivity
import com.babadogan.aynacast.tv.ui.PinCodeActivity
import com.babadogan.aynacast.tv.ui.VideoCastActivity
import com.babadogan.aynacast.tv.ui.localization.AirPlayStrings
import com.babadogan.aynacast.tv.util.DeviceIdentityHelper
import com.babadogan.aynacast.tv.util.DisplayHelper
import com.babadogan.aynacast.tv.util.DnsTxtRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import java.net.Inet4Address
import java.net.NetworkInterface

class AirPlayDaemonService : Service(), AirPlayNativeBridge.AirPlayNativeListener {

    companion object {
        private const val TAG = "AirPlayDaemonService"
        private const val NOTIFICATION_CHANNEL_ID = "airplay_daemon_channel"
        private const val STREAM_CHANNEL_ID = "airplay_stream_channel"
        private const val NOTIFICATION_ID = 1001
        private const val STREAM_NOTIFICATION_ID = 1002
        const val PIN_NOTIFICATION_ID = 1003
        const val VIDEO_CAST_NOTIFICATION_ID = 1004
        private const val RTSP_PORT = 7000
        private const val NETWORK_REFRESH_DELAY_MS = 2_000L
        private const val UNREGISTER_TIMEOUT_MS = 1_500L
        private const val START_RETRY_DELAY_MS = 5_000L
        private const val MAX_START_RETRIES = 5
        private const val MAX_ARTWORK_PX = 1024

        /** Removes the "tap to open mirroring" notification once the player is visible. */
        fun cancelStreamNotification(context: Context) = cancelScreenNotification(context, STREAM_NOTIFICATION_ID)

        /** Removes a "tap to open" fallback notification (see launchScreen) once it is obsolete. */
        fun cancelScreenNotification(context: Context, id: Int) {
            context.getSystemService(NotificationManager::class.java)?.cancel(id)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var nsdPublisher: NsdPublisher? = null

    private var serverStarted = false
    private var startRetries = 0
    private var deviceName = ""
    private var macAddress = ""
    private var publishedAddresses: Set<String> = emptySet()
    // The Now Playing screen pops up once per audio-only session (Back then keeps it closed).
    @Volatile private var nowPlayingShownForSession = false

    private val startRetryRunnable = Runnable { startAirPlayServer() }
    private val networkRefreshRunnable = Runnable { refreshNsdIfNetworkChanged() }

    private val language: AppLanguage
        get() = AppSettingsRepository.getInstance(this).settings.value.language

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "AirPlay Daemon Service creating.")
        AudioPlaybackEngine.init(this)
        PinPairingManager.init(this)
        acquireNetworkLocks()
        createNotificationChannels()

        // On-screen PIN pairing (UxPlay "-pin"): native gate + mDNS "pw"/"sf" flags.
        AppSettingsRepository.getInstance(this).settings
            .map { it.pinRequired }
            .distinctUntilChanged()
            .drop(1)
            .onEach { applyPinSettings(republish = true) }
            .launchIn(serviceScope)
        PinPairingManager.pairedDevices
            .drop(1)
            .onEach { applyPinSettings(republish = false) }
            .launchIn(serviceScope)
        // A code or a cast that is over must not be offered from the notification shade anymore.
        PinPairingManager.pinCode
            .onEach { if (it == null) cancelScreenNotification(this, PIN_NOTIFICATION_ID) }
            .launchIn(serviceScope)
        VideoCastSession.media
            .onEach { if (it == null) cancelScreenNotification(this, VIDEO_CAST_NOTIFICATION_ID) }
            .launchIn(serviceScope)

        // Video casting player state answers /playback-info and tells the sender when playback ends.
        VideoCastSession.playback
            .onEach { info ->
                if (!serverStarted) return@onEach
                AirPlayNativeBridge.setVideoCastPlayback(
                    state = when (info.state) {
                        VideoCastSession.State.STOPPED -> 0
                        VideoCastSession.State.LOADING -> 1
                        VideoCastSession.State.PLAYING -> 2
                        VideoCastSession.State.PAUSED -> 3
                    },
                    durationSeconds = info.durationSec,
                    positionSeconds = info.positionSec,
                    bufferedSeconds = info.bufferedSec,
                    rate = info.rate,
                    readyToPlay = info.readyToPlay
                )
            }
            .launchIn(serviceScope)

        // UxPlay "-vsync" equivalent, chosen in the settings screen.
        AppSettingsRepository.getInstance(this).settings
            .map { it.avSyncEnabled }
            .distinctUntilChanged()
            .onEach { enabled ->
                AvSync.enabled = enabled
                AudioPlaybackEngine.onSyncModeChanged()
                Log.i(TAG, "Timestamp audio/video sync ${if (enabled) "enabled" else "disabled (lowest latency)"}")
            }
            .launchIn(serviceScope)

        // Keep notification texts in the language chosen in the app.
        AppSettingsRepository.getInstance(this).settings
            .map { it.language }
            .distinctUntilChanged()
            .drop(1)
            .onEach {
                createNotificationChannels()
                getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildForegroundNotification())
            }
            .launchIn(serviceScope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService() call must be answered with startForeground().
        enterForeground()
        if (!serverStarted) {
            mainHandler.removeCallbacks(startRetryRunnable)
            startRetries = 0
            startAirPlayServer()
        }
        return START_STICKY
    }

    private fun enterForeground() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildForegroundNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, buildForegroundNotification())
            }
        } catch (e: Exception) {
            // Android 12+ can refuse foreground promotion when restarted from the background.
            Log.e(TAG, "Could not enter foreground state; continuing as a background service", e)
        }
    }

    private fun startAirPlayServer() {
        if (serverStarted) return
        if (!AirPlayNativeBridge.isLoaded) {
            Log.e(TAG, "Native AirPlay core unavailable; receiver cannot start.")
            AirPlaySessionManager.notifyServiceRunning(false)
            return
        }

        deviceName = DeviceIdentityHelper.getAirPlayDeviceName(this)
        macAddress = DeviceIdentityHelper.getDeviceMacAddress(this)

        // Dynamically detect TV's physical display resolution
        val displayInfo = DisplayHelper.getTvDisplayInfo(this)
        Log.i(TAG, "Detected TV display: ${displayInfo.width}x${displayInfo.height} @ ${displayInfo.refreshRate} fps")

        // Same Ed25519 identity on every start, so PIN-paired senders stay paired.
        AirPlayNativeBridge.setIdentitySeed(ServerIdentityStore.seed(this))

        // Start C++ RTSP core server with dynamic device identity
        if (!AirPlayNativeBridge.initNativeServer(RTSP_PORT, deviceName, macAddress, this)) {
            AirPlaySessionManager.notifyServiceRunning(false)
            if (startRetries++ < MAX_START_RETRIES) {
                Log.w(TAG, "AirPlay server failed to start (port $RTSP_PORT busy?); retry $startRetries/$MAX_START_RETRIES")
                mainHandler.postDelayed(startRetryRunnable, START_RETRY_DELAY_MS)
            } else {
                Log.e(TAG, "AirPlay server could not be started; giving up until the service restarts.")
            }
            return
        }
        serverStarted = true

        // Cap AirPlay mirroring display negotiation to 1080p (1920x1080 @ 60fps).
        // Sending 4K forces the sender into 3616x2032 @ 75fps, exceeding the TV's AVC decoder hardware limits.
        val airPlayWidth = minOf(displayInfo.width, 1920)
        val airPlayHeight = minOf(displayInfo.height, 1080)
        val airPlayFps = 60.0f
        AirPlayNativeBridge.setDisplayResolution(airPlayWidth, airPlayHeight, airPlayFps)
        applyPinSettings(republish = false)
        AirPlaySessionManager.setDisplayResolution(airPlayWidth, airPlayHeight, airPlayFps.toInt())
        AirPlaySessionManager.notifyServiceRunning(true)
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildForegroundNotification())

        // Advertise only after the server runs, so the mDNS "pk" matches the server's identity key.
        val nsdManager = getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsdManager != null) {
            nsdPublisher = NsdPublisher(nsdManager)
            publishedAddresses = currentLocalAddresses()
            nsdPublisher?.publish(buildServiceInfos())
        } else {
            Log.e(TAG, "NsdManager unavailable; the receiver will not be discoverable.")
        }
        // The same TXT records answer GET /info "txtAirPlay" / "txtRAOP" queries.
        publishTxtRecords()
        startNetworkMonitoring()
    }

    /** Pushes the PIN requirement and paired devices to the native server (main thread only). */
    private fun applyPinSettings(republish: Boolean) {
        if (!serverStarted) return
        AirPlayNativeBridge.setPinRequired(pinRequired)
        AirPlayNativeBridge.setPairedClients(PinPairingManager.pairedDevices.value.toTypedArray())
        if (republish) {
            nsdPublisher?.publish(buildServiceInfos())
            publishTxtRecords()
        }
    }

    private val pinRequired: Boolean
        get() = AppSettingsRepository.getInstance(this).settings.value.pinRequired

    private fun publishTxtRecords() {
        val services = buildServiceInfos()
        AirPlayNativeBridge.setTxtRecords(
            DnsTxtRecord.encode(services[0].attributes),
            DnsTxtRecord.encode(services[1].attributes)
        )
    }

    private fun startNetworkMonitoring() {
        if (networkCallback != null) return
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        // AirPlay only needs the LAN, so do not require internet access.
        val request = NetworkRequest.Builder()
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = scheduleNsdRefresh()
            override fun onLost(network: Network) = scheduleNsdRefresh()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = scheduleNsdRefresh()
        }

        try {
            connectivityManager?.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "Network callback registration failed", e)
        }
    }

    /** Network callbacks arrive on a binder thread and in bursts; coalesce them on the main thread. */
    private fun scheduleNsdRefresh() {
        mainHandler.removeCallbacks(networkRefreshRunnable)
        mainHandler.postDelayed(networkRefreshRunnable, NETWORK_REFRESH_DELAY_MS)
    }

    private fun refreshNsdIfNetworkChanged() {
        val publisher = nsdPublisher ?: return
        val addresses = currentLocalAddresses()
        if (addresses == publishedAddresses) return
        Log.i(TAG, "Local addresses changed ($publishedAddresses -> $addresses); refreshing mDNS registration.")
        publishedAddresses = addresses
        if (addresses.isNotEmpty()) {
            publisher.publish(buildServiceInfos())
        }
    }

    private fun currentLocalAddresses(): Set<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .toSet()
    }.getOrDefault(emptySet())

    private fun acquireNetworkLocks() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifiManager?.let {
            multicastLock = it.createMulticastLock("AirPlayMulticastLock").apply {
                setReferenceCounted(false)
                acquire()
            }
            val wifiMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = it.createWifiLock(wifiMode, "AirPlayWifiLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun buildServiceInfos(): List<NsdServiceInfo> {
        val raopPrefix = DeviceIdentityHelper.getRaopMacPrefix(macAddress)
        val pkHex = runCatching { AirPlayNativeBridge.getPublicKeyHex() }
            .getOrDefault("b07727d6f6cd6e08b58ede525ec3cdeaa252ad9f683feb212ef8a205246554e7")

        Log.i(TAG, "Broadcasting AirPlay device name: '$deviceName', Device ID: '$macAddress', PK: '$pkHex'")

        // 1. _airplay._tcp
        val airplayServiceInfo = NsdServiceInfo().apply {
            serviceName = deviceName
            serviceType = "_airplay._tcp"
            port = RTSP_PORT
            setAttribute("model", "AppleTV5,3")
            setAttribute("features", AirPlayFeatures.txtValue())
            setAttribute("srcvers", "220.68")
            setAttribute("deviceid", macAddress)
            setAttribute("flags", "0x4")
            setAttribute("vv", "2")
            setAttribute("pk", pkHex)
            setAttribute("pi", "2e388006-13ba-4041-9a67-25dd4a43d536")
            if (pinRequired) setAttribute("pw", "true")
        }

        // 2. _raop._tcp
        val raopServiceInfo = NsdServiceInfo().apply {
            serviceName = "$raopPrefix@$deviceName"
            serviceType = "_raop._tcp"
            port = RTSP_PORT
            setAttribute("ch", "2")
            setAttribute("cn", "0,1,2,3")
            setAttribute("da", "true")
            setAttribute("et", "0,3,5")
            setAttribute("md", "0,1,2")
            setAttribute("pw", if (pinRequired) "true" else "false")
            // UxPlay: sf bit 3 (0x08) means "PIN required".
            if (pinRequired) setAttribute("sf", "0x8c")
            setAttribute("sr", "44100")
            setAttribute("ss", "16")
            setAttribute("tp", "UDP")
            setAttribute("vn", "65537")
            setAttribute("vs", "220.68")
            setAttribute("am", "AppleTV5,3")
            setAttribute("sv", "false")
            setAttribute("pk", pkHex) // Advertised by RPiPlay/UxPlay as well
        }
        return listOf(airplayServiceInfo, raopServiceInfo)
    }

    /**
     * Owns the mDNS registrations. A refresh waits until the previous registrations are gone
     * before registering again; otherwise NsdManager renames the duplicate ("Name (2)").
     * All state is confined to the main thread.
     */
    private inner class NsdPublisher(private val nsdManager: NsdManager) {
        private val registered = mutableListOf<NsdManager.RegistrationListener>()
        private val awaitingUnregistration = mutableSetOf<NsdManager.RegistrationListener>()
        private var queuedServices: List<NsdServiceInfo>? = null
        private val unregisterTimeout = Runnable {
            Log.w(TAG, "mDNS unregistration timed out; registering anyway.")
            awaitingUnregistration.clear()
            flushQueued()
        }

        fun publish(services: List<NsdServiceInfo>) {
            queuedServices = services
            unregisterAll()
            if (awaitingUnregistration.isEmpty()) flushQueued()
        }

        fun shutdown() {
            queuedServices = null
            unregisterAll()
            mainHandler.removeCallbacks(unregisterTimeout)
        }

        private fun unregisterAll() {
            for (listener in registered) {
                try {
                    nsdManager.unregisterService(listener)
                    awaitingUnregistration.add(listener)
                } catch (e: IllegalArgumentException) {
                    // The registration already failed or was removed; nothing to wait for.
                }
            }
            registered.clear()
            if (awaitingUnregistration.isNotEmpty()) {
                mainHandler.removeCallbacks(unregisterTimeout)
                mainHandler.postDelayed(unregisterTimeout, UNREGISTER_TIMEOUT_MS)
            }
        }

        private fun onUnregistered(listener: NsdManager.RegistrationListener) {
            if (awaitingUnregistration.remove(listener) && awaitingUnregistration.isEmpty()) {
                mainHandler.removeCallbacks(unregisterTimeout)
                flushQueued()
            }
        }

        private fun flushQueued() {
            val services = queuedServices ?: return
            queuedServices = null
            services.forEach(::register)
        }

        private fun register(info: NsdServiceInfo) {
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(registeredInfo: NsdServiceInfo?) {
                    Log.i(TAG, "${info.serviceType} registered successfully: ${registeredInfo?.serviceName}")
                }

                override fun onRegistrationFailed(failedInfo: NsdServiceInfo?, errorCode: Int) {
                    Log.e(TAG, "Failed to register ${info.serviceType}, error: $errorCode")
                    mainHandler.post { registered.remove(this) }
                }

                override fun onServiceUnregistered(unregisteredInfo: NsdServiceInfo?) {
                    mainHandler.post { onUnregistered(this) }
                }

                override fun onUnregistrationFailed(failedInfo: NsdServiceInfo?, errorCode: Int) {
                    Log.w(TAG, "Failed to unregister ${info.serviceType}, error: $errorCode")
                    mainHandler.post { onUnregistered(this) }
                }
            }

            try {
                nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
                registered.add(listener)
            } catch (e: Exception) {
                Log.e(TAG, "Exception during ${info.serviceType} registration", e)
            }
        }
    }

    // --- Native Callbacks (invoked on native server threads) ---

    override fun onClientConnected(deviceName: String, ipAddress: String) {
        Log.i(TAG, "AirPlay client connected: $deviceName ($ipAddress)")
        AirPlaySessionManager.notifyClientConnected(deviceName, ipAddress)
    }

    override fun onStreamStarted() {
        Log.i(TAG, "AirPlay session started (RECORD).")
        nowPlayingShownForSession = false
        AirPlaySessionManager.notifyStreamStarted()
        // The player opens once mirrored video arrives (audio-only AirPlay keeps the current TV app).
        mainHandler.post { launchPlayer() }
    }

    override fun onStreamStopped() {
        Log.i(TAG, "AirPlay stream stopped (TEARDOWN).")
        AirPlaySessionManager.notifyStreamStopped()
        VideoDecoderEngine.stopCodec()
        AudioPlaybackEngine.stop()
        AvSync.reset()
        cancelStreamNotification(this)
    }

    override fun onVideoStopped() {
        Log.i(TAG, "Mirrored video stopped; AirPlay session continues.")
        AirPlaySessionManager.notifyVideoStopped()
        VideoDecoderEngine.stopCodec()
        AvSync.reset()
        cancelStreamNotification(this)
    }

    override fun onSpsPpsReady(sps: ByteArray, pps: ByteArray) {
        if (!AirPlaySessionManager.isVideoActive.value) {
            Log.i(TAG, "Mirrored video started. Launching AirPlayPlayerActivity on-demand.")
            AirPlaySessionManager.notifyVideoStarted()
            mainHandler.post { launchPlayer() }
        }
        VideoDecoderEngine.onSpsPpsReady(sps, pps)
    }

    override fun onVideoFrame(nalData: ByteArray, pts: Long, isKeyFrame: Boolean) {
        VideoDecoderEngine.feedNalUnit(nalData, pts, isKeyFrame)
    }

    override fun onAudioPacket(pcmData: ByteArray, pts: Long) {
        AudioPlaybackEngine.writePacket(pcmData, pts)
        AirPlaySessionManager.notifyAudioPacket()
    }

    override fun onAudioConfig(codec: Int, sampleRate: Int, channels: Int, samplesPerFrame: Int) {
        AudioPlaybackEngine.configure(codec, sampleRate, channels, samplesPerFrame)
        if (codec == AirPlayAudioFormat.CODEC_STOPPED) {
            AirPlaySessionManager.clearNowPlaying()
        }
    }

    override fun onNowPlayingMetadata(title: String, artist: String, album: String) {
        AirPlaySessionManager.updateNowPlayingMetadata(title, artist, album)
        mainHandler.post { showNowPlaying() }
    }

    override fun onCoverArt(image: ByteArray) {
        AirPlaySessionManager.updateNowPlayingArtwork(decodeArtwork(image))
        mainHandler.post { showNowPlaying() }
    }

    override fun onPlaybackProgress(positionMs: Long, durationMs: Long) {
        AirPlaySessionManager.updateNowPlayingProgress(positionMs, durationMs)
    }

    override fun onPinStart() {
        val pin = PinPairingManager.startPairing() ?: return
        mainHandler.post {
            launchScreen(
                PinCodeActivity.createIntent(this),
                PIN_NOTIFICATION_ID,
                AirPlayStrings.pinTitle(language),
                AirPlayStrings.notificationPinText(pin, language)
            )
        }
    }

    override fun onPinSetup(step: Int, user: String, input: Array<ByteArray>): Array<ByteArray>? {
        // A sender that skips /pair-pin-start (or retries after the code expired) still gets a
        // fresh code on screen before the exchange starts.
        if (step == 1 && !PinPairingManager.isPairing()) onPinStart()
        return PinPairingManager.handleStep(step, user, input)
    }

    override fun onVideoCastPlay(url: String, startSeconds: Double) {
        Log.i(TAG, "Video casting started")
        VideoCastSession.play(url, startSeconds)
        mainHandler.post {
            launchScreen(
                VideoCastActivity.createIntent(this),
                VIDEO_CAST_NOTIFICATION_ID,
                AirPlayStrings.notificationVideoCastTitle(language),
                AirPlayStrings.notificationVideoCastText(language)
            )
        }
    }

    override fun onVideoCastSeek(positionSeconds: Double) = VideoCastSession.seek(positionSeconds)

    override fun onVideoCastRate(rate: Float) = VideoCastSession.setRate(rate)

    override fun onVideoCastStop() {
        Log.i(TAG, "Video casting stopped by the sender")
        VideoCastSession.stop()
    }

    /** Decodes cover art, downsampled so a large JPEG does not cost tens of MB of heap. */
    private fun decodeArtwork(image: ByteArray): Bitmap? {
        if (image.isEmpty()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= MAX_ARTWORK_PX || bounds.outHeight / (sample * 2) >= MAX_ARTWORK_PX) {
                sample *= 2
            }
            BitmapFactory.decodeByteArray(image, 0, image.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: Exception) {
            Log.w(TAG, "Could not decode cover art", e)
            null
        }
    }

    /** Apple TV-like Now Playing screen for audio-only AirPlay (music); never during mirroring. */
    private fun showNowPlaying() {
        if (nowPlayingShownForSession) return
        if (!AirPlaySessionManager.isStreaming.value || AirPlaySessionManager.isVideoActive.value) return
        if (!AirPlaySessionManager.nowPlaying.value.hasContent) return
        nowPlayingShownForSession = true
        try {
            startActivity(NowPlayingActivity.createIntent(this))
        } catch (e: Exception) {
            Log.w(TAG, "Could not open the Now Playing screen", e)
        }
    }

    override fun onVolumeChanged(volumeDb: Float) {
        AudioPlaybackEngine.setVolumeDb(volumeDb)
    }

    private fun launchPlayer() {
        if (!AirPlaySessionManager.isStreaming.value || !AirPlaySessionManager.isVideoActive.value) return
        val clientName = AirPlaySessionManager.clientMetadata.value.deviceName
        launchScreen(
            AirPlayPlayerActivity.createIntent(this),
            STREAM_NOTIFICATION_ID,
            AirPlayStrings.notificationStreamTitle(clientName, language),
            AirPlayStrings.notificationStreamText(language)
        )
    }

    /**
     * Opens one of the receiver's screens from the background. Android 10+ silently blocks that
     * unless the app may draw over other apps, so a notification then lets the user open it.
     */
    private fun launchScreen(intent: Intent, notificationId: Int, title: String, text: String) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Could not open ${intent.component?.shortClassName}", e)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !Settings.canDrawOverlays(this)) {
            showScreenNotification(intent, notificationId, title, text)
        }
    }

    private fun showScreenNotification(intent: Intent, notificationId: Int, title: String, text: String) {
        val pendingIntent = PendingIntent.getActivity(
            this,
            notificationId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, STREAM_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        try {
            getSystemService(NotificationManager::class.java)?.notify(notificationId, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission not granted; cannot show the fallback notification", e)
        }
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val serviceChannel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            AirPlayStrings.notificationServiceChannel(language),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = AirPlayStrings.notificationServiceChannelDesc(language)
            setShowBadge(false)
        }
        val streamChannel = NotificationChannel(
            STREAM_CHANNEL_ID,
            AirPlayStrings.notificationStreamChannel(language),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            setShowBadge(false)
        }
        manager.createNotificationChannels(listOf(serviceChannel, streamChannel))
    }

    private fun buildForegroundNotification(): Notification {
        val settingsIntent = Intent(this, AirPlaySettingsActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            settingsIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val name = deviceName.ifEmpty { DeviceIdentityHelper.getAirPlayDeviceName(this) }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(AirPlayStrings.notificationServiceTitle(language))
            .setContentText(AirPlayStrings.notificationServiceText(name, language))
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onDestroy() {
        Log.i(TAG, "AirPlay Daemon Service destroying.")
        // Stop the native server first: its threads can still post work (e.g. open the PIN screen)
        // until they are joined. An active session is ended through onStreamStopped().
        if (serverStarted) {
            AirPlayNativeBridge.stopNativeServer()
            serverStarted = false
        }
        mainHandler.removeCallbacksAndMessages(null)
        serviceScope.cancel()
        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        networkCallback = null
        nsdPublisher?.shutdown()
        nsdPublisher = null

        // The native server is gone: nothing can complete a pairing or feed a cast anymore.
        PinPairingManager.cancelPairing()
        VideoCastSession.stop()
        AirPlaySessionManager.notifyServiceRunning(false)
        cancelStreamNotification(this)
        cancelScreenNotification(this, PIN_NOTIFICATION_ID)
        cancelScreenNotification(this, VIDEO_CAST_NOTIFICATION_ID)

        multicastLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
