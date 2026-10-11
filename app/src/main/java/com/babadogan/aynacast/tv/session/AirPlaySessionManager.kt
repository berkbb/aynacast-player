package com.babadogan.aynacast.tv.session

import android.graphics.Bitmap
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ConnectionStatus {
    IDLE,
    WAITING_FOR_STREAM,
    STREAMING,
    DISCONNECTED
}

data class ClientMetadata(
    val deviceName: String = "Apple Device",
    val ipAddress: String = "",
    val resolutionWidth: Int = 1920,
    val resolutionHeight: Int = 1080,
    val fps: Int = 60,
    val bitrateMbps: Float = 0.0f
)

/** Track the sender is streaming (audio-only AirPlay), shown on the Now Playing screen. */
data class NowPlaying(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artwork: Bitmap? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    /** SystemClock.elapsedRealtime() when [positionMs] was reported. */
    val positionUpdatedAtMs: Long = 0L
) {
    val hasContent: Boolean get() = title.isNotBlank() || artist.isNotBlank() || artwork != null
}

object AirPlaySessionManager {

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.IDLE)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    /** True while mirrored video is being received (audio-only AirPlay sessions keep this false). */
    private val _isVideoActive = MutableStateFlow(false)
    val isVideoActive: StateFlow<Boolean> = _isVideoActive.asStateFlow()

    private val _clientMetadata = MutableStateFlow(ClientMetadata())
    val clientMetadata: StateFlow<ClientMetadata> = _clientMetadata.asStateFlow()

    private val _currentFps = MutableStateFlow(0)
    val currentFps: StateFlow<Int> = _currentFps.asStateFlow()

    /** True while the daemon service is running and its AirPlay server is listening. */
    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    private val _nowPlaying = MutableStateFlow(NowPlaying())
    val nowPlaying: StateFlow<NowPlaying> = _nowPlaying.asStateFlow()

    /** elapsedRealtime of the last audio packet; tells the UI whether music is advancing or paused. */
    @Volatile
    var lastAudioPacketAtMs: Long = 0L
        private set

    fun notifyAudioPacket() {
        lastAudioPacketAtMs = SystemClock.elapsedRealtime()
    }

    fun updateNowPlayingMetadata(title: String, artist: String, album: String) {
        _nowPlaying.update { it.copy(title = title, artist = artist, album = album) }
    }

    fun updateNowPlayingArtwork(artwork: Bitmap?) {
        _nowPlaying.update { it.copy(artwork = artwork) }
    }

    fun updateNowPlayingProgress(positionMs: Long, durationMs: Long) {
        _nowPlaying.update {
            it.copy(positionMs = positionMs, durationMs = durationMs, positionUpdatedAtMs = SystemClock.elapsedRealtime())
        }
    }

    fun clearNowPlaying() {
        _nowPlaying.value = NowPlaying()
    }

    fun notifyServiceRunning(running: Boolean) {
        _isServiceRunning.value = running
    }

    fun notifyClientConnected(deviceName: String, ipAddress: String) {
        _clientMetadata.update { it.copy(
            deviceName = deviceName.ifBlank { "iPhone / Mac" },
            ipAddress = ipAddress
        ) }
        _connectionStatus.value = ConnectionStatus.WAITING_FOR_STREAM
    }

    fun notifyStreamStarted() {
        _isStreaming.value = true
        _connectionStatus.value = ConnectionStatus.STREAMING
    }

    fun notifyVideoStarted() {
        _isVideoActive.value = true
    }

    fun notifyVideoStopped() {
        _isVideoActive.value = false
        _currentFps.value = 0
    }

    fun notifyStreamStopped() {
        _isStreaming.value = false
        _isVideoActive.value = false
        _currentFps.value = 0
        _nowPlaying.value = NowPlaying()
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
    }

    fun updateMetrics(fps: Int, bitrateMbps: Float) {
        _currentFps.value = fps
        _clientMetadata.update { it.copy(
            fps = fps,
            bitrateMbps = bitrateMbps
        ) }
    }

    fun setDisplayResolution(width: Int, height: Int, fps: Int) {
        _clientMetadata.update { it.copy(
            resolutionWidth = width,
            resolutionHeight = height,
            fps = fps
        ) }
    }

    fun resetToIdle() {
        _isStreaming.value = false
        _isVideoActive.value = false
        _connectionStatus.value = ConnectionStatus.IDLE
    }
}
