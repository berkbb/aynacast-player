package com.babadogan.aynacast.tv.engine

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.babadogan.aynacast.tv.session.AirPlaySessionManager
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Dedicated hardware/software video decoding pipeline for AirPlay mirroring.
 *
 * Runs decoding operations on an isolated worker thread so:
 * 1. UI thread is NEVER blocked (SurfaceView draws smoothly).
 * 2. Network thread is NEVER blocked (AirPlay socket is drained in real-time).
 * 3. MediaCodec is touched exclusively by a single thread, eliminating concurrency deadlocks.
 */
object VideoDecoderEngine {

    private const val TAG = "VideoDecoderEngine"
    private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val QUEUE_CAPACITY = 200 // ample jitter buffer for 60fps streaming

    private sealed class Task {
        class BindSurface(val surface: Surface) : Task()
        object UnbindSurface : Task()
        class Config(val sps: ByteArray, val pps: ByteArray) : Task()
        class Frame(val data: ByteArray, val isKeyFrame: Boolean) : Task()
    }

    private val queue = ArrayBlockingQueue<Task>(QUEUE_CAPACITY)
    private var workerThread: Thread? = null
    @Volatile private var isRunning = false

    private var mediaCodec: MediaCodec? = null
    private var boundSurface: Surface? = null
    private var spsBytes: ByteArray? = null
    private var ppsBytes: ByteArray? = null
    private var useSoftwareDecoder = false

    private var streamWidth = 1920
    private var streamHeight = 1080
    private var nextFramePtsUs = 0L
    @Volatile private var cachedKeyframe: ByteArray? = null

    private var frameCount = 0
    private var lastFpsCalculationTime = 0L

    init {
        startWorker()
    }

    @Synchronized
    private fun startWorker() {
        if (isRunning && workerThread?.isAlive == true) return
        isRunning = true
        workerThread = Thread({ runDecoderLoop() }, "AirPlay-VideoDecoder").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun bindOutputSurface(surface: Surface) {
        startWorker()
        Log.i(TAG, "Enqueuing BindSurface task")
        queue.offer(Task.BindSurface(surface))
    }

    fun unbindSurface() {
        Log.i(TAG, "Enqueuing UnbindSurface task")
        queue.offer(Task.UnbindSurface)
    }

    fun onSpsPpsReady(sps: ByteArray, pps: ByteArray) {
        startWorker()
        Log.i(TAG, "Enqueuing Config task: SPS=${sps.size}B PPS=${pps.size}B")
        queue.offer(Task.Config(sps, pps))
    }

    fun feedNalUnit(nalData: ByteArray, ptsUs: Long, isKeyFrame: Boolean) {
        startWorker()
        if (isKeyFrame) {
            cachedKeyframe = nalData.clone()
        }
        if (!queue.offer(Task.Frame(nalData, isKeyFrame))) {
            // Queue full: drop oldest frame to preserve real-time responsiveness
            queue.poll()
            queue.offer(Task.Frame(nalData, isKeyFrame))
        }
    }

    fun stopCodec() {
        queue.clear()
        cachedKeyframe = null
        queue.offer(Task.UnbindSurface)
    }

    // ─── Worker Loop (Runs exclusively on AirPlay-VideoDecoder thread) ───

    private fun runDecoderLoop() {
        Log.i(TAG, "AirPlay-VideoDecoder worker thread started")
        while (isRunning) {
            try {
                val task = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                when (task) {
                    is Task.BindSurface -> {
                        boundSurface = task.surface
                        nextFramePtsUs = 0L
                        if (spsBytes != null && ppsBytes != null) {
                            rebuildCodec()
                        }
                    }
                    is Task.UnbindSurface -> {
                        boundSurface = null
                        releaseCodec()
                    }
                    is Task.Config -> {
                        spsBytes = task.sps
                        ppsBytes = task.pps

                        val parsedRes = parseSpsResolution(task.sps)
                        if (parsedRes != null && parsedRes.first in 64..4096 && parsedRes.second in 64..4096) {
                            streamWidth = parsedRes.first
                            streamHeight = parsedRes.second
                            Log.i(TAG, "Parsed stream resolution from SPS: ${streamWidth}x${streamHeight}")
                        }

                        if (boundSurface != null) {
                            rebuildCodec()
                        }
                    }
                    is Task.Frame -> {
                        decodeFrame(task.data, task.isKeyFrame)
                    }
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.e(TAG, "Exception in video decoder loop", e)
            }
        }
        releaseCodec()
        Log.i(TAG, "AirPlay-VideoDecoder worker thread stopped")
    }

    private fun rebuildCodec() {
        releaseCodec()
        val surface = boundSurface ?: return
        val sps = spsBytes ?: return
        val pps = ppsBytes ?: return

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
            frameCount = 0
            lastFpsCalculationTime = System.currentTimeMillis()
            nextFramePtsUs = 0L
            AirPlaySessionManager.setDisplayResolution(streamWidth, streamHeight, 60)
            Log.i(TAG, "MediaCodec started on worker thread: ${codec.name} (${streamWidth}x${streamHeight}, software=$useSoftwareDecoder)")

            val keyframe = cachedKeyframe
            if (keyframe != null) {
                Log.i(TAG, "Replaying cached initial IDR keyframe (${keyframe.size}B) to newly started MediaCodec")
                decodeFrame(keyframe, isKeyFrame = true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure and start MediaCodec", e)
            if (!useSoftwareDecoder) {
                Log.w(TAG, "Attempting software decoder fallback...")
                useSoftwareDecoder = true
                rebuildCodec()
            }
        }
    }

    private fun decodeFrame(nalData: ByteArray, isKeyFrame: Boolean) {
        val frameToSend: ByteArray
        if (isKeyFrame) {
            cachedKeyframe = nalData.clone()
            // Guarantee in-band SPS/PPS parameter set initialization before IDR keyframe
            // (standard UxPlay / RPiPlay technique for hardware AVC decoders)
            val sps = spsBytes
            val pps = ppsBytes
            frameToSend = if (sps != null && pps != null) {
                val spsPrefix = ensureAnnexBPrefix(sps)
                val ppsPrefix = ensureAnnexBPrefix(pps)
                spsPrefix + ppsPrefix + nalData
            } else {
                nalData
            }
        } else {
            frameToSend = nalData
        }

        val codec = mediaCodec ?: return
        if (boundSurface == null) return

        try {
            drainOutput(codec)

            // For IDR keyframes, allow multiple retry attempts with drainOutput to ensure
            // the decoder accepts the foundational reference frame!
            var inIndex = codec.dequeueInputBuffer(10_000L)
            var retries = 0
            while (inIndex < 0 && isKeyFrame && retries < 5) {
                drainOutput(codec)
                inIndex = codec.dequeueInputBuffer(20_000L)
                retries++
            }
            if (inIndex < 0 && !isKeyFrame) {
                drainOutput(codec)
                inIndex = codec.dequeueInputBuffer(10_000L)
            }

            if (inIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inIndex)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    inputBuffer.put(frameToSend)
                    if (nextFramePtsUs == 0L) {
                        nextFramePtsUs = System.nanoTime() / 1000L
                    }
                    val presentationTimeUs = nextFramePtsUs
                    nextFramePtsUs += 16_666L // ~60fps monotonic microsecond step
                    codec.queueInputBuffer(inIndex, 0, frameToSend.size, presentationTimeUs, 0)
                    if (isKeyFrame) {
                        Log.i(TAG, "Successfully queued IDR keyframe (${frameToSend.size}B) into MediaCodec input buffer $inIndex")
                    }
                }
            } else if (isKeyFrame) {
                Log.w(TAG, "Failed to obtain input buffer for IDR keyframe after retries!")
            }

            drainOutput(codec)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "MediaCodec fatal error during decoding — initiating fallback", e)
            handleCodecFatalError()
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding frame", e)
        }
    }

    private fun handleCodecFatalError() {
        if (!useSoftwareDecoder) {
            Log.w(TAG, "Switching to software decoder fallback after hardware decoder error")
            useSoftwareDecoder = true
            rebuildCodec()
        } else {
            Log.e(TAG, "Software decoder also encountered error — resetting decoder state")
            releaseCodec()
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
                        Log.i(TAG, "Authoritative decoder resolution: ${w}x${h}")
                    }
                }
                outIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {}
                outIndex >= 0 -> {
                    // Render frame directly to Surface
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

    private fun releaseCodec() {
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing MediaCodec", e)
        } finally {
            mediaCodec = null
        }
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

    private fun ensureAnnexBPrefix(data: ByteArray): ByteArray {
        if (data.size >= 4 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 0.toByte() && data[3] == 1.toByte()) {
            return data
        }
        if (data.size >= 3 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 1.toByte()) {
            return byteArrayOf(0) + data
        }
        return byteArrayOf(0, 0, 0, 1) + data
    }

    // ─── SPS Parser (Sequence Parameter Set resolution extraction) ───

    private fun parseSpsResolution(sps: ByteArray): Pair<Int, Int>? {
        try {
            var offset = 0
            var foundStartCode = false
            while (offset + 3 < sps.size) {
                if (sps[offset].toInt() == 0 && sps[offset + 1].toInt() == 0 &&
                    (sps[offset + 2].toInt() == 1 || (sps[offset + 2].toInt() == 0 && sps[offset + 3].toInt() == 1))) {
                    offset = if (sps[offset + 2].toInt() == 1) offset + 3 else offset + 4
                    foundStartCode = true
                    break
                }
                offset++
            }
            if (!foundStartCode) offset = 0
            if (sps.size - offset < 4) return null

            val reader = SpsBitReader(sps, offset + 1)
            val profileIdc = reader.readBits(8)
            reader.readBits(8)
            reader.readBits(8)
            reader.readUe()

            var chromaFormatIdc = 1
            var separateColorPlaneFlag = 0

            val highProfiles = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)
            if (profileIdc in highProfiles) {
                chromaFormatIdc = reader.readUe()
                if (chromaFormatIdc == 3) {
                    separateColorPlaneFlag = reader.readBits(1)
                }
                reader.readUe()
                reader.readUe()
                reader.readBits(1)
                if (reader.readBits(1) == 1) {
                    val count = if (chromaFormatIdc != 3) 8 else 12
                    repeat(count) {
                        if (reader.readBits(1) == 1) {
                            reader.skipScalingList(if (it < 6) 16 else 64)
                        }
                    }
                }
            }

            reader.readUe()
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

            reader.readUe()
            reader.readBits(1)

            val picWidthInMbsMinus1 = reader.readUe()
            val picHeightInMapUnitsMinus1 = reader.readUe()
            val frameMbsOnlyFlag = reader.readBits(1)
            if (frameMbsOnlyFlag == 0) {
                reader.readBits(1)
            }
            reader.readBits(1)

            var cropLeft = 0
            var cropRight = 0
            var cropTop = 0
            var cropBottom = 0
            if (reader.readBits(1) == 1) {
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
