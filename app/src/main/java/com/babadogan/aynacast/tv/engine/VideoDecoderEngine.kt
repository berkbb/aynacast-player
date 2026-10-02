package com.babadogan.aynacast.tv.engine

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.babadogan.aynacast.tv.session.AirPlaySessionManager
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

object VideoDecoderEngine {

    private const val TAG = "VideoDecoderEngine"
    private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC

    private var mediaCodec: MediaCodec? = null
    private var boundSurface: Surface? = null
    private val isCodecRunning = AtomicBoolean(false)

    private var spsBytes: ByteArray? = null
    private var ppsBytes: ByteArray? = null

    private var frameCount = 0
    private var lastFpsCalculationTime = 0L

    @Synchronized
    fun bindOutputSurface(surface: Surface) {
        Log.i(TAG, "Binding new output Surface to decoder.")
        boundSurface = surface
        if (spsBytes != null && ppsBytes != null && !isCodecRunning.get()) {
            configureAndStartCodec(spsBytes!!, ppsBytes!!)
        }
    }

    @Synchronized
    fun unbindSurface() {
        Log.i(TAG, "Unbinding output Surface.")
        stopCodec()
        boundSurface = null
    }

    @Synchronized
    fun onSpsPpsReady(sps: ByteArray, pps: ByteArray) {
        Log.i(TAG, "SPS (${sps.size} bytes) & PPS (${pps.size} bytes) received.")
        spsBytes = sps
        ppsBytes = pps

        if (boundSurface != null) {
            configureAndStartCodec(sps, pps)
        }
    }

    private fun configureAndStartCodec(sps: ByteArray, pps: ByteArray) {
        stopCodec()

        val surface = boundSurface ?: return
        try {
            // AirPlay mirroring from iPhone/Mac sends 1080p H.264 at most.
            // We use 1920x1080 as the MediaFormat hint — the actual stream dimensions
            // are encoded in csd-0 (SPS) which MediaCodec reads internally.
            // The hardware decoder will adjust to the real SPS dimensions automatically.
            val codec = MediaCodec.createDecoderByType(MIME_TYPE)
            val format = MediaFormat.createVideoFormat(MIME_TYPE, 1920, 1080).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                setByteBuffer("csd-1", ByteBuffer.wrap(pps))
                // Low-latency configuration for real-time mirroring
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }

            codec.configure(format, surface, null, 0)
            codec.start()

            mediaCodec = codec
            isCodecRunning.set(true)
            frameCount = 0
            lastFpsCalculationTime = System.currentTimeMillis()
            Log.i(TAG, "Hardware MediaCodec AVC decoder started (hint: 1920x1080, actual from SPS).")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MediaCodec decoder", e)
        }
    }

    fun feedNalUnit(nalData: ByteArray, ptsUs: Long, isKeyFrame: Boolean) {
        val codec = mediaCodec ?: return
        if (!isCodecRunning.get()) return

        try {
            val inIndex = codec.dequeueInputBuffer(10_000L) // 10ms timeout
            if (inIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inIndex)
                inputBuffer?.clear()
                inputBuffer?.put(nalData)
                val flags = if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                codec.queueInputBuffer(inIndex, 0, nalData.size, ptsUs, flags)
            }

            // Drain output frames
            val bufferInfo = MediaCodec.BufferInfo()
            var outIndex = codec.dequeueOutputBuffer(bufferInfo, 0L)
            while (outIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // Codec settled on real stream resolution — log it
                        val newFormat = codec.outputFormat
                        val w = newFormat.getInteger(MediaFormat.KEY_WIDTH)
                        val h = newFormat.getInteger(MediaFormat.KEY_HEIGHT)
                        Log.i(TAG, "Output format changed: actual stream resolution = ${w}x${h}")
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                        // Ignored — legacy callback, not needed for Surface output
                    }
                    outIndex >= 0 -> {
                        // Render frame directly to Surface
                        val doRender = (bufferInfo.size > 0)
                        codec.releaseOutputBuffer(outIndex, doRender)
                        if (doRender) {
                            frameCount++
                            val now = System.currentTimeMillis()
                            if (now - lastFpsCalculationTime >= 1000L) {
                                val fps = (frameCount * 1000L / (now - lastFpsCalculationTime)).toInt()
                                AirPlaySessionManager.updateMetrics(fps, 12.5f)
                                Log.d(TAG, "Rendering at $fps fps")
                                frameCount = 0
                                lastFpsCalculationTime = now
                            }
                        }
                    }
                }
                outIndex = codec.dequeueOutputBuffer(bufferInfo, 0L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing NAL frame in MediaCodec", e)
        }
    }

    @Synchronized
    fun stopCodec() {
        if (!isCodecRunning.get()) return
        try {
            isCodecRunning.set(false)
            mediaCodec?.stop()
            mediaCodec?.release()
            mediaCodec = null
            Log.i(TAG, "MediaCodec stopped and released.")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaCodec", e)
        }
    }
}
