package com.airplay.tv.engine

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

    external fun initNativeServer(port: Int, listener: AirPlayNativeListener): Boolean
    external fun stopNativeServer()
    external fun feedSimulatedNalFrame(nalData: ByteArray, pts: Long, isKeyFrame: Boolean)
}
