package com.babadogan.aynacast.tv.engine

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
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
    private var useSoftwareDecoder = false

    private var spsBytes: ByteArray? = null
    private var ppsBytes: ByteArray? = null
    private var streamWidth = 1920
    private var streamHeight = 1080

    private var frameCount = 0
    private var lastFpsCalculationTime = 0L

    @Synchronized
    fun bindOutputSurface(surface: Surface) {
        Log.i(TAG, "Binding new output Surface to decoder.")
        boundSurface = surface
        nextFramePtsUs = 0L
        awaitingKeyframe = true
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

        // Parse stream resolution directly from the SPS NAL unit
        val parsedRes = parseSpsResolution(sps)
        if (parsedRes != null && parsedRes.first in 64..4096 && parsedRes.second in 64..4096) {
            streamWidth = parsedRes.first
            streamHeight = parsedRes.second
            Log.i(TAG, "Parsed stream resolution from SPS: ${streamWidth}x${streamHeight}")
        } else {
            Log.w(TAG, "Could not parse SPS resolution, using default ${streamWidth}x${streamHeight}")
        }

        if (boundSurface != null) {
            configureAndStartCodec(sps, pps)
        }
    }

    private fun ensureAnnexBPrefix(data: ByteArray): ByteArray {
        if (data.size >= 4 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 0.toByte() && data[3] == 1.toByte()) {
            return data
        }
        if (data.size >= 3 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 1.toByte()) {
            return byteArrayOf(0) + data
        }
        return byteArrayOf(0, 0, 0, 1) + data
    }

    private fun createCodec(): MediaCodec {
        return if (useSoftwareDecoder) {
            Log.i(TAG, "Creating Google software AVC decoder: OMX.google.h264.decoder")
            MediaCodec.createByCodecName("OMX.google.h264.decoder")
        } else {
            try {
                Log.i(TAG, "Creating hardware AVC decoder by type: $MIME_TYPE")
                MediaCodec.createDecoderByType(MIME_TYPE)
            } catch (e: Exception) {
                Log.w(TAG, "Hardware decoder creation failed, falling back to OMX.google.h264.decoder", e)
                useSoftwareDecoder = true
                MediaCodec.createByCodecName("OMX.google.h264.decoder")
            }
        }
    }

    private fun configureAndStartCodec(sps: ByteArray, pps: ByteArray) {
        if (isCodecRunning.get()) {
            try {
                isCodecRunning.set(false)
                mediaCodec?.stop()
                mediaCodec?.release()
                mediaCodec = null
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping previous codec instance", e)
            }
        }

        val surface = boundSurface ?: return
        try {
            val spsPrefix = ensureAnnexBPrefix(sps)
            val ppsPrefix = ensureAnnexBPrefix(pps)

            val codec = createCodec()
            val format = MediaFormat.createVideoFormat(MIME_TYPE, streamWidth, streamHeight).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(spsPrefix))
                setByteBuffer("csd-1", ByteBuffer.wrap(ppsPrefix))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }

            codec.configure(format, surface, null, 0)
            codec.start()

            mediaCodec = codec
            isCodecRunning.set(true)
            frameCount = 0
            lastFpsCalculationTime = System.currentTimeMillis()
            AirPlaySessionManager.setDisplayResolution(streamWidth, streamHeight, 60)
            Log.i(TAG, "MediaCodec started: ${codec.name} at ${streamWidth}x${streamHeight} (software=$useSoftwareDecoder). Pending frames: ${pendingFrames.size}")

            while (!pendingFrames.isEmpty()) {
                val frame = pendingFrames.removeFirst()
                queueFrameToCodec(codec, frame.data, frame.ptsUs, frame.isKeyFrame)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MediaCodec decoder", e)
            if (!useSoftwareDecoder) {
                Log.w(TAG, "Retrying with software decoder fallback...")
                useSoftwareDecoder = true
                configureAndStartCodec(sps, pps)
            }
        }
    }

    private data class PendingFrame(val data: ByteArray, val ptsUs: Long, val isKeyFrame: Boolean)
    private val pendingFrames = java.util.ArrayDeque<PendingFrame>()
    @Volatile private var awaitingKeyframe = true
    private var nextFramePtsUs = 0L

    @Synchronized
    fun feedNalUnit(nalData: ByteArray, ptsUs: Long, isKeyFrame: Boolean) {
        val codec = mediaCodec
        if (codec == null || !isCodecRunning.get()) {
            if (pendingFrames.size > 90) {
                pendingFrames.removeFirst()
                awaitingKeyframe = true
            }
            pendingFrames.addLast(PendingFrame(nalData, ptsUs, isKeyFrame))
            return
        }

        while (!pendingFrames.isEmpty()) {
            val frame = pendingFrames.removeFirst()
            queueFrameToCodec(codec, frame.data, frame.ptsUs, frame.isKeyFrame)
        }

        // Drop predicted frames until a fresh IDR keyframe arrives to avoid decoding artifacts/smearing
        if (awaitingKeyframe) {
            if (!isKeyFrame) {
                return
            }
            awaitingKeyframe = false
            Log.i(TAG, "Resynced decoder on fresh IDR keyframe.")
        }

        queueFrameToCodec(codec, nalData, ptsUs, isKeyFrame)
    }

    private fun queueFrameToCodec(codec: MediaCodec, nalData: ByteArray, ptsUs: Long, isKeyFrame: Boolean) {
        try {
            // Drain output FIRST to ensure the codec has free input buffer slots
            drainOutput(codec)

            val inIndex = codec.dequeueInputBuffer(100_000L) // 100ms timeout
            if (inIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inIndex)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    inputBuffer.put(nalData)
                    if (nextFramePtsUs == 0L) {
                        nextFramePtsUs = System.nanoTime() / 1000L
                    }
                    val presentationTimeUs = nextFramePtsUs
                    nextFramePtsUs += 16_666L // monotonic ~60fps PTS interval
                    // Flags must be 0 for decoders (BUFFER_FLAG_KEY_FRAME is for encoders)
                    codec.queueInputBuffer(inIndex, 0, nalData.size, presentationTimeUs, 0)
                }
            } else {
                Log.v(TAG, "No input buffer available after timeout, dropping NAL unit to preserve latency")
            }

            drainOutput(codec)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "MediaCodec fatal error during decoding — initiating fallback", e)
            handleCodecFatalError()
        } catch (e: Exception) {
            Log.e(TAG, "Error processing NAL frame in MediaCodec", e)
        }
    }

    private fun handleCodecFatalError() {
        if (!useSoftwareDecoder) {
            Log.w(TAG, "Switching to software decoder fallback after hardware decoder crash")
            useSoftwareDecoder = true
            stopCodec()
            if (boundSurface != null && spsBytes != null && ppsBytes != null) {
                configureAndStartCodec(spsBytes!!, ppsBytes!!)
            }
        } else {
            Log.e(TAG, "Software decoder also encountered fatal error")
            stopCodec()
        }
    }

    private fun drainOutput(codec: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()
        var outIndex = codec.dequeueOutputBuffer(bufferInfo, 0L)
        while (outIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = codec.outputFormat
                    var w = newFormat.getInteger(MediaFormat.KEY_WIDTH)
                    var h = newFormat.getInteger(MediaFormat.KEY_HEIGHT)
                    if (newFormat.containsKey("crop-left") && newFormat.containsKey("crop-right")) {
                        w = newFormat.getInteger("crop-right") - newFormat.getInteger("crop-left") + 1
                        h = newFormat.getInteger("crop-bottom") - newFormat.getInteger("crop-top") + 1
                    }
                    if (w in 64..4096 && h in 64..4096) {
                        streamWidth = w
                        streamHeight = h
                        AirPlaySessionManager.setDisplayResolution(w, h, 60)
                        Log.i(TAG, "Decoder output format changed: authoritative resolution = ${w}x${h}")
                    }
                }
                outIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {}
                outIndex >= 0 -> {
                    // Render frame to Surface immediately
                    codec.releaseOutputBuffer(outIndex, true)
                    frameCount++
                    val now = System.currentTimeMillis()
                    if (now - lastFpsCalculationTime >= 1000L) {
                        val fps = (frameCount * 1000L / (now - lastFpsCalculationTime)).toInt()
                        AirPlaySessionManager.updateMetrics(fps, 12.5f)
                        Log.i(TAG, "Rendering video successfully at $fps fps (decoder=${codec.name}, res=${streamWidth}x${streamHeight})")
                        frameCount = 0
                        lastFpsCalculationTime = now
                    }
                }
            }
            outIndex = codec.dequeueOutputBuffer(bufferInfo, 0L)
        }
    }

    @Synchronized
    fun stopCodec() {
        pendingFrames.clear()
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

    // ─── SPS Parser (Sequence Parameter Set resolution extraction) ───

    private fun parseSpsResolution(sps: ByteArray): Pair<Int, Int>? {
        try {
            // Find start of NAL (skip Annex-B 00 00 00 01 if present)
            var offset = 0
            while (offset + 3 < sps.size) {
                if (sps[offset].toInt() == 0 && sps[offset + 1].toInt() == 0 &&
                    (sps[offset + 2].toInt() == 1 || (sps[offset + 2].toInt() == 0 && sps[offset + 3].toInt() == 1))) {
                    offset = if (sps[offset + 2].toInt() == 1) offset + 3 else offset + 4
                    break
                }
                offset++
            }
            if (offset >= sps.size) offset = 0
            if (sps.size - offset < 4) return null

            val reader = SpsBitReader(sps, offset + 1) // skip NAL type byte (0x67 or 0x27)
            val profileIdc = reader.readBits(8)
            reader.readBits(8) // constraint flags
            reader.readBits(8) // level_idc
            reader.readUe()    // seq_parameter_set_id

            var chromaFormatIdc = 1
            var separateColorPlaneFlag = 0

            val highProfiles = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)
            if (profileIdc in highProfiles) {
                chromaFormatIdc = reader.readUe()
                if (chromaFormatIdc == 3) {
                    separateColorPlaneFlag = reader.readBits(1)
                }
                reader.readUe() // bit_depth_luma_minus8
                reader.readUe() // bit_depth_chroma_minus8
                reader.readBits(1) // qpprime_y_zero_transform_bypass_flag
                if (reader.readBits(1) == 1) { // seq_scaling_matrix_present_flag
                    val count = if (chromaFormatIdc != 3) 8 else 12
                    repeat(count) {
                        if (reader.readBits(1) == 1) {
                            reader.skipScalingList(if (it < 6) 16 else 64)
                        }
                    }
                }
            }

            reader.readUe() // log2_max_frame_num_minus4
            val picOrderCntType = reader.readUe()
            when (picOrderCntType) {
                0 -> reader.readUe()
                1 -> {
                    reader.readBits(1)
                    reader.readSe()
                    reader.readSe()
                    repeat(reader.readUe()) { reader.readSe() }
                }
            }

            reader.readUe() // max_num_ref_frames
            reader.readBits(1) // gaps_in_frame_num_value_allowed_flag

            val picWidthInMbsMinus1 = reader.readUe()
            val picHeightInMapUnitsMinus1 = reader.readUe()
            val frameMbsOnlyFlag = reader.readBits(1)
            if (frameMbsOnlyFlag == 0) {
                reader.readBits(1)
            }
            reader.readBits(1) // direct_8x8_inference_flag

            var cropLeft = 0
            var cropRight = 0
            var cropTop = 0
            var cropBottom = 0
            if (reader.readBits(1) == 1) { // frame_cropping_flag
                cropLeft = reader.readUe()
                cropRight = reader.readUe()
                cropTop = reader.readUe()
                cropBottom = reader.readUe()
            }

            val codedWidth = (picWidthInMbsMinus1 + 1) * 16
            val codedHeight = (picHeightInMapUnitsMinus1 + 1) * 16 * (2 - frameMbsOnlyFlag)

            val chromaArrayType = if (separateColorPlaneFlag == 1) 0 else chromaFormatIdc
            val subWidthC = if (chromaArrayType in 1..2) 2 else 1
            val subHeightC = if (chromaArrayType == 1) 2 else 1

            val cropUnitX = if (chromaArrayType == 0) 1 else subWidthC
            val cropUnitY = if (chromaArrayType == 0) (2 - frameMbsOnlyFlag) else subHeightC * (2 - frameMbsOnlyFlag)

            val width = codedWidth - (cropLeft + cropRight) * cropUnitX
            val height = codedHeight - (cropTop + cropBottom) * cropUnitY

            return if (width > 0 && height > 0) Pair(width, height) else null
        } catch (e: Exception) {
            Log.w(TAG, "SPS resolution parsing failed: ${e.message}")
            return null
        }
    }

    private class SpsBitReader(private val data: ByteArray, startOffset: Int) {
        private var bytePos = startOffset
        private var bitPos = 7

        fun readBit(): Int {
            if (bytePos >= data.size) throw IndexOutOfBoundsException("SPS RBSP underflow")
            val bit = (data[bytePos].toInt() ushr bitPos) and 1
            if (--bitPos < 0) { bitPos = 7; bytePos++ }
            return bit
        }

        fun readBits(n: Int): Int {
            var result = 0
            repeat(n) { result = (result shl 1) or readBit() }
            return result
        }

        fun readUe(): Int {
            var leadingZeros = 0
            while (readBit() == 0) {
                if (++leadingZeros > 31) throw ArithmeticException("ue(v) overflow")
            }
            return if (leadingZeros == 0) 0
                   else (1 shl leadingZeros) - 1 + readBits(leadingZeros)
        }

        fun readSe(): Int {
            val k = readUe()
            return if (k == 0) 0 else if (k % 2 == 1) (k + 1) / 2 else -(k / 2)
        }

        fun skipScalingList(size: Int) {
            var lastScale = 8
            var nextScale = 8
            repeat(size) {
                if (nextScale != 0) {
                    val deltaScale = readSe()
                    nextScale = (lastScale + deltaScale + 256) % 256
                }
                lastScale = if (nextScale == 0) lastScale else nextScale
            }
        }
    }
}
