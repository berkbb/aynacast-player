package com.babadogan.aynacast.tv.engine

import android.util.Log

object AirPlayNativeBridge {

    private const val TAG = "AirPlayNativeBridge"

    /** False when libairplaycore.so could not be loaded (e.g. unsupported ABI). */
    val isLoaded: Boolean = try {
        System.loadLibrary("airplaycore")
        true
    } catch (e: UnsatisfiedLinkError) {
        Log.e(TAG, "Native AirPlay core could not be loaded", e)
        false
    }

    /**
     * Callbacks are invoked on native server threads, never on the main thread.
     * Implementations must not call back into [AirPlayNativeBridge] (the server may be stopping).
     */
    interface AirPlayNativeListener {
        fun onClientConnected(deviceName: String, ipAddress: String)
        fun onStreamStarted()
        fun onStreamStopped()

        /** The mirrored video stream ended (TEARDOWN of stream 110) while the session continues. */
        fun onVideoStopped()
        fun onSpsPpsReady(sps: ByteArray, pps: ByteArray)
        /** [pts] is the sender timestamp mapped onto System.nanoTime() via NTP, or 0 when unknown. */
        fun onVideoFrame(nalData: ByteArray, pts: Long, isKeyFrame: Boolean)

        /**
         * Audio payload in the format announced by the latest [onAudioConfig];
         * [pts] is its sender timestamp on the System.nanoTime() clock, or 0 when unknown.
         */
        fun onAudioPacket(pcmData: ByteArray, pts: Long)

        /**
         * Announces the audio stream format: [AirPlayAudioFormat.CODEC_PCM_S16] (native-decoded ALAC),
         * [AirPlayAudioFormat.CODEC_AAC_LC], [AirPlayAudioFormat.CODEC_AAC_ELD], or
         * [AirPlayAudioFormat.CODEC_STOPPED] when the sender tears the audio stream down.
         */
        fun onAudioConfig(codec: Int, sampleRate: Int, channels: Int, samplesPerFrame: Int)

        /** Sender volume in AirPlay dB units (-30..0, -144 = mute). */
        fun onVolumeChanged(volumeDb: Float)

        /** Now-playing track information (audio streaming); fields may be empty. */
        fun onNowPlayingMetadata(title: String, artist: String, album: String)

        /** Cover art as JPEG/PNG bytes; empty when the track has no artwork. */
        fun onCoverArt(image: ByteArray)

        /** Playback position and track duration in milliseconds. */
        fun onPlaybackProgress(positionMs: Long, durationMs: Long)

        /** A sender asks for on-screen PIN pairing (/pair-pin-start): show a new code. */
        fun onPinStart()

        /** One /pair-setup-pin step (see PinPairingManager.handleStep); null when it fails. */
        fun onPinSetup(step: Int, user: String, input: Array<ByteArray>): Array<ByteArray>?

        /** Video casting: play [url] (direct URL or the receiver's own HLS master) from [startSeconds]. */
        fun onVideoCastPlay(url: String, startSeconds: Double)
        fun onVideoCastSeek(positionSeconds: Double)
        /** 0 pauses, 1 resumes. */
        fun onVideoCastRate(rate: Float)
        fun onVideoCastStop()
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

    /** DNS-SD TXT records (wire format) answered for GET /info "txtAirPlay" / "txtRAOP" qualifiers. */
    external fun setTxtRecords(airplayTxt: ByteArray, raopTxt: ByteArray)

    /**
     * 32-byte seed of the receiver's Ed25519 identity, applied by every later initNativeServer.
     * Keeping it stable keeps the advertised "pk" and PIN pairings valid across restarts.
     */
    external fun setIdentitySeed(seed: ByteArray)

    /** When true, devices must pair with an on-screen PIN before they can connect (UxPlay -pin). */
    external fun setPinRequired(required: Boolean)

    /** Ed25519 public keys of devices that already paired with the PIN. */
    external fun setPairedClients(keys: Array<ByteArray>)

    /** Video casting player state (state: 0 stopped, 1 loading, 2 playing, 3 paused). */
    external fun setVideoCastPlayback(
        state: Int,
        durationSeconds: Double,
        positionSeconds: Double,
        bufferedSeconds: Double,
        rate: Float,
        readyToPlay: Boolean
    )
}
