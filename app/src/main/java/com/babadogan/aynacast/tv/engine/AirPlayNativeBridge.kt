package com.babadogan.aynacast.tv.engine

object AirPlayNativeBridge {

    init {
        try {
            System.loadLibrary("airplaycore")
        } catch (e: UnsatisfiedLinkError) {
            e.printStackTrace()
        }
    }

    interface AirPlayNativeListener {
        fun onClientConnected(deviceName: String, ipAddress: String)
        fun onStreamStarted()
        fun onStreamStopped()
        fun onSpsPpsReady(sps: ByteArray, pps: ByteArray)
        fun onVideoFrame(nalData: ByteArray, pts: Long, isKeyFrame: Boolean)
        fun onAudioPacket(pcmData: ByteArray, pts: Long)
    }

    external fun initNativeServer(
        port: Int,
        deviceName: String,
        macAddress: String,
        listener: AirPlayNativeListener
    ): Boolean
    external fun stopNativeServer()
    external fun getPublicKeyHex(): String
    external fun feedSimulatedNalFrame(nalData: ByteArray, pts: Long, isKeyFrame: Boolean)
    external fun setDisplayResolution(width: Int, height: Int, refreshRate: Float)
}
