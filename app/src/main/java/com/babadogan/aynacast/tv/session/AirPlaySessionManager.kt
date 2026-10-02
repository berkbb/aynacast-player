package com.babadogan.aynacast.tv.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

object AirPlaySessionManager {

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.IDLE)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _clientMetadata = MutableStateFlow(ClientMetadata())
    val clientMetadata: StateFlow<ClientMetadata> = _clientMetadata.asStateFlow()

    private val _currentFps = MutableStateFlow(0)
    val currentFps: StateFlow<Int> = _currentFps.asStateFlow()

    fun notifyClientConnected(deviceName: String, ipAddress: String) {
        _clientMetadata.value = _clientMetadata.value.copy(
            deviceName = deviceName.ifBlank { "iPhone / Mac" },
            ipAddress = ipAddress
        )
        _connectionStatus.value = ConnectionStatus.WAITING_FOR_STREAM
    }

    fun notifyStreamStarted() {
        _isStreaming.value = true
        _connectionStatus.value = ConnectionStatus.STREAMING
    }

    fun notifyStreamStopped() {
        _isStreaming.value = false
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
    }

    fun updateMetrics(fps: Int, bitrateMbps: Float) {
        _currentFps.value = fps
        _clientMetadata.value = _clientMetadata.value.copy(
            fps = fps,
            bitrateMbps = bitrateMbps
        )
    }

    fun resetToIdle() {
        _isStreaming.value = false
        _connectionStatus.value = ConnectionStatus.IDLE
    }
}
