package com.babadogan.aynacast.tv.engine

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.babadogan.aynacast.tv.session.AirPlaySessionManager
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Dedicated hardware/software video decoding pipeline for AirPlay mirroring.
 *
 * Runs decoding operations on an isolated worker thread so:
 * 1. UI thread is NEVER blocked (SurfaceView draws smoothly).
 * 2. Network thread is NEVER blocked (AirPlay socket is drained in real-time).
 * 3. MediaCodec is touched exclusively by a single thread, eliminating concurrency deadlocks.
 *
 * Control tasks (surface bind/unbind, codec config, reset) are never dropped; only queued
 * non-key frames are discarded when the decoder falls more than [MAX_PENDING_FRAMES] behind.
 */
object VideoDecoderEngine {

    private const val TAG = "VideoDecoderEngine"
    private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val MAX_PENDING_FRAMES = 120 // ~2 sec at 60fps before frames are discarded
    // Frames since the last IDR, replayed into a freshly created codec so it has every reference frame.
    private const val MAX_GOP_FRAMES = 300
    private const val MAX_GOP_BYTES = 12 * 1024 * 1024
    private const val UNBIND_TIMEOUT_MS = 500L
    private const val FALLBACK_SOFTWARE_DECODER = "OMX.google.h264.decoder"
    // In timestamp-sync mode a frame later than this is dropped instead of shown (UxPlay -vsync).
    private const val LATE_FRAME_DROP_NS = 40_000_000L
    // ...and one is handed to the display at most this early. Frames queued further ahead fill the
    // surface's few buffers, stall the decoder and inflate the measured decode delay.
    private const val MAX_RENDER_AHEAD_NS = 50_000_000L
    // Timestamp marker for GOP-replay frames that only rebuild references and must not be shown.
    private const val REPLAY_HIDDEN_NS = -1L
    // Sync mode holds compressed frames until shortly before they are due (polling for control
    // tasks meanwhile); a hold never exceeds this, whatever the timestamps say.
    private const val MAX_HOLD_NS = 600_000_000L
    private const val HOLD_POLL_MS = 4L
    // While a codec runs, the worker wakes this often without input to collect decoded frames:
    // mirroring is variable-frame-rate, and the last frame of a screen change must not wait for
    // the next change to be shown.
    private const val IDLE_DRAIN_POLL_MS = 5L
    // Decoder processing times above this are measurement noise (codec restart, stalled surface).
    private const val MAX_CODEC_DELAY_NS = 500_000_000L
    private const val TIMESTAMP_RING_SIZE = 256
    // Minimum spacing of input presentation times. Some hardware decoders (MediaTek TV SoCs) derive
    // the frame rate from it; a replayed GOP fed back to back would read as thousands of fps and the
    // decoder fails to allocate resources for that rate.
    private const val MIN_PTS_STEP_US = 16_667L
    // How long a frame waits for a free decoder input buffer before it is dropped. A dropped delta
    // frame corrupts every frame that references it until the next IDR, which a mirroring sender
    // rarely sends, so waiting (the queue absorbs the backlog) is far better than dropping.
    private const val INPUT_WAIT_US = 10_000L
    private const val MAX_INPUT_WAIT_RETRIES = 30

    private sealed class Task {
        class BindSurface(val surface: Surface) : Task()
        class UnbindSurface(val surface: Surface, val done: CountDownLatch) : Task()
        object Reset : Task()
        class Config(val sps: ByteArray, val pps: ByteArray) : Task()
        class Frame(val data: ByteArray, val isKeyFrame: Boolean, val localTimeNs: Long, val enqueuedAtNs: Long) : Task()
    }

    private val queue = LinkedBlockingDeque<Task>()

    private val _hasShownFrame = MutableStateFlow(false)
    /** True once a frame has been rendered into the currently bound surface. */
    val hasShownFrame: StateFlow<Boolean> = _hasShownFrame.asStateFlow()
    private val pendingFrames = AtomicInteger(0)
    private val gopInvalidated = AtomicBoolean(false)
    private var workerThread: Thread? = null

    // ─── Worker-thread state (only touched by AirPlay-VideoDecoder) ───
    private var mediaCodec: MediaCodec? = null
    private var boundSurface: Surface? = null
    private var spsBytes: ByteArray? = null
    private var ppsBytes: ByteArray? = null
    private var useSoftwareDecoder = false

    private var streamWidth = 1920
    private var streamHeight = 1080

    private val gopFrames = ArrayList<ByteArray>()
    private var gopBytes = 0
    private var gopIncomplete = false

    private var frameCount = 0
    private var bytesDecoded = 0L
    private var lastFpsCalculationTime = 0L

    // Input presentation time (µs) -> sender timestamp on the local clock (ns) and how long after
    // that timestamp the frame arrived here (ns), for sync mode.
    private val inputPtsRing = LongArray(TIMESTAMP_RING_SIZE)
    private val localTimeRing = LongArray(TIMESTAMP_RING_SIZE)
    private val arrivalDelayRing = LongArray(TIMESTAMP_RING_SIZE)
    // System.nanoTime() at which each input entered the codec.
    private val inputTimeRing = LongArray(TIMESTAMP_RING_SIZE)
    private var ringIndex = 0
    private var lastInputPtsUs = 0L
    // Smoothed time the codec takes from input to output.
    private var codecDelayNs = 0L

    init {
        startWorker()
    }

    @Synchronized
    private fun startWorker() {
        if (workerThread?.isAlive == true) return
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

    /**
     * Detaches [surface] and waits until the codec has stopped rendering into it, honouring the
     * SurfaceHolder.Callback.surfaceDestroyed contract. Must be called from the surface owner's thread.
     */
    fun unbindSurface(surface: Surface) {
        startWorker()
        Log.i(TAG, "Enqueuing UnbindSurface task")
        queue.removeIf { it is Task.BindSurface && it.surface === surface }
        val done = CountDownLatch(1)
        queue.offerFirst(Task.UnbindSurface(surface, done))
        if (!done.await(UNBIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "Decoder did not release the surface within ${UNBIND_TIMEOUT_MS}ms")
        }
    }

    fun onSpsPpsReady(sps: ByteArray, pps: ByteArray) {
        startWorker()
        Log.i(TAG, "Enqueuing Config task: SPS=${sps.size}B PPS=${pps.size}B")
        queue.offer(Task.Config(sps, pps))
    }

    /** [localTimeNs] is the sender timestamp on the System.nanoTime() clock, or 0 when unknown. */
    fun feedNalUnit(nalData: ByteArray, localTimeNs: Long, isKeyFrame: Boolean) {
        startWorker()
        if (pendingFrames.get() >= MAX_PENDING_FRAMES) {
            dropOldestDeltaFrame()
        }
        pendingFrames.incrementAndGet()
        queue.offer(Task.Frame(nalData, isKeyFrame, localTimeNs, System.nanoTime()))
    }

    /** Ends the current session: drops its queued frames and decoder state but keeps the surface. */
    fun stopCodec() {
        removeQueuedFrames { true }
        queue.removeIf { it is Task.Config }
        queue.offer(Task.Reset)
    }

    private fun dropOldestDeltaFrame() {
        if (removeQueuedFrames(limit = 1) { !it.isKeyFrame } > 0) {
            // The buffered GOP now misses a reference frame; it cannot be replayed until the next IDR.
            gopInvalidated.set(true)
            Log.w(TAG, "Decoder is ${MAX_PENDING_FRAMES} frames behind; dropped the oldest delta frame")
        }
    }

    private inline fun removeQueuedFrames(limit: Int = Int.MAX_VALUE, predicate: (Task.Frame) -> Boolean): Int {
        var removed = 0
        val iterator = queue.iterator()
        while (iterator.hasNext() && removed < limit) {
            val task = iterator.next()
            // removeFirstOccurrence reports whether this thread (not the worker) took the frame.
            if (task is Task.Frame && predicate(task) && queue.removeFirstOccurrence(task)) {
                pendingFrames.decrementAndGet()
                removed++
            }
        }
        return removed
    }

    // ─── Worker Loop (Runs exclusively on AirPlay-VideoDecoder thread) ───

    private fun runDecoderLoop() {
        Log.i(TAG, "AirPlay-VideoDecoder worker thread started")
        while (true) {
            try {
                val task = queue.poll(if (mediaCodec != null) IDLE_DRAIN_POLL_MS else 100L, TimeUnit.MILLISECONDS)
                if (task == null) {
                    drainIdle()
                    continue
                }
                when (task) {
                    is Task.BindSurface -> {
                        if (boundSurface === task.surface && mediaCodec != null) {
                            Log.i(TAG, "Worker: Surface already bound and codec active, ignoring redundant BindSurface")
                            continue
                        }
                        boundSurface = task.surface
                        _hasShownFrame.value = false
                        if (spsBytes != null && ppsBytes != null) {
                            rebuildCodec()
                        }
                    }
                    is Task.UnbindSurface -> {
                        if (boundSurface === task.surface) {
                            boundSurface = null
                            _hasShownFrame.value = false
                            releaseCodec()
                            useSoftwareDecoder = false
                        }
                        task.done.countDown()
                    }
                    is Task.Reset -> {
                        releaseCodec()
                        spsBytes = null
                        ppsBytes = null
                        clearGop()
                        useSoftwareDecoder = false
                        Log.i(TAG, "Decoder state reset for the next session")
                    }
                    is Task.Config -> applyConfig(task.sps, task.pps)
                    is Task.Frame -> {
                        pendingFrames.decrementAndGet()
                        holdUntilDue(task.localTimeNs)
                        decodeFrame(
                            task.data, task.isKeyFrame, remember = true, localTimeNs = task.localTimeNs,
                            arrivalDelayNs = if (task.localTimeNs > 0L) task.enqueuedAtNs - task.localTimeNs else 0L
                        )
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

    private fun applyConfig(sps: ByteArray, pps: ByteArray) {
        val unchanged = spsBytes?.contentEquals(sps) == true && ppsBytes?.contentEquals(pps) == true
        spsBytes = sps
        ppsBytes = pps

        if (unchanged) {
            // Senders repeat identical parameter sets; the running decoder (and buffered GOP) stay valid.
            if (mediaCodec == null && boundSurface != null) rebuildCodec()
            return
        }

        val parsedRes = H264SpsParser.parseResolution(sps)
        if (parsedRes == null) {
            Log.w(TAG, "SPS resolution parsing failed; keeping ${streamWidth}x${streamHeight}")
        }
        if (parsedRes != null && parsedRes.first in 64..4096 && parsedRes.second in 64..4096) {
            streamWidth = parsedRes.first
            streamHeight = parsedRes.second
            Log.i(TAG, "Parsed stream resolution from SPS: ${streamWidth}x${streamHeight}")
        }

        // Frames buffered under the previous parameter sets cannot be decoded with the new ones.
        clearGop()
        if (boundSurface != null) {
            rebuildCodec()
        }
    }

    private fun rebuildCodec() {
        releaseCodec()
        val surface = boundSurface ?: return
        if (!surface.isValid) {
            Log.w(TAG, "Cannot rebuildCodec: bound surface is invalid!")
            return
        }
        val sps = spsBytes ?: return
        val pps = ppsBytes ?: return

        try {
            val spsPrefix = ensureAnnexBPrefix(sps)
            val ppsPrefix = ensureAnnexBPrefix(pps)

            val codec = createCodec()
            val format = MediaFormat.createVideoFormat(MIME_TYPE, streamWidth, streamHeight).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(spsPrefix))
                setByteBuffer("csd-1", ByteBuffer.wrap(ppsPrefix))
                // An uncompressed frame is a safe upper bound for any access unit (large IDRs included).
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(streamWidth * streamHeight * 3 / 2, 1 shl 20))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                // Low-latency vendor keys for MediaTek OMX and Qualcomm hardware decoders
                setInteger("vdec-lowlatency", 1)
                setInteger("vendor.rtc-ext-dec-low-latency.enable", 1)
            }

            codec.configure(format, surface, null, 0)
            codec.start()

            mediaCodec = codec
            frameCount = 0
            bytesDecoded = 0
            lastFpsCalculationTime = System.currentTimeMillis()
            AirPlaySessionManager.setDisplayResolution(streamWidth, streamHeight, 60)
            Log.i(TAG, "MediaCodec started on worker thread: ${codec.name} (${streamWidth}x${streamHeight}, software=$useSoftwareDecoder)")

            replayBufferedGop(codec)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure and start MediaCodec", e)
            releaseCodec()
            if (!useSoftwareDecoder) {
                Log.w(TAG, "Attempting software decoder fallback...")
                useSoftwareDecoder = true
                rebuildCodec()
            }
        }
    }

    private fun replayBufferedGop(codec: MediaCodec) {
        if (gopFrames.isEmpty()) return
        // A GOP that lost frames (one dropped, or over the size cap) is only valid up to its keyframe.
        val incomplete = gopIncomplete || gopInvalidated.get()
        val frames = if (incomplete) listOf(gopFrames.first()) else gopFrames.toList()
        Log.i(TAG, "Replaying ${frames.size} buffered frame(s) from the last keyframe (incomplete=$incomplete)")
        for ((index, frame) in frames.withIndex()) {
            // The surface is being torn down and unbindSurface() is waiting for this thread.
            if (queue.peekFirst() is Task.UnbindSurface) return
            // Only the newest frame is shown; the others just rebuild the reference frames.
            val hidden = index != frames.lastIndex
            decodeFrame(frame, isKeyFrame = index == 0, remember = false, localTimeNs = if (hidden) REPLAY_HIDDEN_NS else 0L)
            if (mediaCodec !== codec) return // Codec was replaced after an error; it replays on its own.
        }
    }

    private fun rememberFrame(data: ByteArray, isKeyFrame: Boolean) {
        if (gopInvalidated.getAndSet(false)) gopIncomplete = true
        if (isKeyFrame) {
            clearGop()
        } else if (gopFrames.isEmpty() || gopIncomplete) {
            return
        }
        if (gopFrames.size >= MAX_GOP_FRAMES || gopBytes + data.size > MAX_GOP_BYTES) {
            gopIncomplete = true
            return
        }
        gopFrames.add(data)
        gopBytes += data.size
    }

    private fun clearGop() {
        gopFrames.clear()
        gopBytes = 0
        gopIncomplete = false
    }

    /**
     * Timestamp-sync mode: keeps a (compressed) frame until it can go through the codec and reach
     * the display just before it is due. Holding it here rather than on the display queue keeps the
     * codec's few output buffers free. Pending control tasks (surface, config, reset) end the wait.
     */
    private fun holdUntilDue(localTimeNs: Long) {
        if (localTimeNs <= 0L || mediaCodec == null || !AvSync.isActive()) return
        val start = System.nanoTime()
        val feedAt = minOf(
            localTimeNs + AvSync.latencyNs() - codecDelayNs - MAX_RENDER_AHEAD_NS,
            start + MAX_HOLD_NS
        )
        var now = start
        while (now < feedAt) {
            val head = queue.peekFirst()
            if (head != null && head !is Task.Frame) return
            Thread.sleep(minOf((feedAt - now) / 1_000_000L + 1, HOLD_POLL_MS))
            drainIdle()
            now = System.nanoTime()
        }
    }

    /** Collects frames the codec finished while no input was being fed. */
    private fun drainIdle() {
        val codec = mediaCodec ?: return
        try {
            drainOutput(codec)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "MediaCodec failed while draining output — initiating fallback", e)
            handleCodecFatalError()
        }
    }

    private fun decodeFrame(
        nalData: ByteArray,
        isKeyFrame: Boolean,
        remember: Boolean,
        localTimeNs: Long = 0L,
        arrivalDelayNs: Long = 0L
    ) {
        if (remember) rememberFrame(nalData, isKeyFrame)

        val codec = mediaCodec ?: return
        if (boundSurface == null) return

        val frameToSend: ByteArray
        if (isKeyFrame) {
            // Guarantee in-band SPS/PPS parameter set initialization before IDR keyframe
            // (standard UxPlay / RPiPlay technique for hardware AVC decoders)
            val sps = spsBytes
            val pps = ppsBytes
            frameToSend = if (sps != null && pps != null) {
                ensureAnnexBPrefix(sps) + ensureAnnexBPrefix(pps) + nalData
            } else {
                nalData
            }
        } else {
            frameToSend = nalData
        }

        try {
            drainOutput(codec)

            var inIndex = codec.dequeueInputBuffer(INPUT_WAIT_US)
            var retries = 0
            while (inIndex < 0 && retries < MAX_INPUT_WAIT_RETRIES) {
                drainOutput(codec)
                // A control task (surface teardown, new config) must not wait behind a busy decoder.
                if (queue.peekFirst().let { it != null && it !is Task.Frame }) break
                inIndex = codec.dequeueInputBuffer(INPUT_WAIT_US)
                retries++
            }

            if (inIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inIndex)
                val inputTimeNs = System.nanoTime()
                // Unique, increasing and at most 60 fps apart (see MIN_PTS_STEP_US).
                val presentationTimeUs = maxOf(inputTimeNs / 1000L, lastInputPtsUs + MIN_PTS_STEP_US)
                lastInputPtsUs = presentationTimeUs
                if (inputBuffer != null && frameToSend.size <= inputBuffer.capacity()) {
                    inputBuffer.clear()
                    inputBuffer.put(frameToSend)
                    codec.queueInputBuffer(inIndex, 0, frameToSend.size, presentationTimeUs, 0)
                    rememberTimestamp(presentationTimeUs, inputTimeNs, localTimeNs, arrivalDelayNs)
                    if (remember) bytesDecoded += frameToSend.size
                    if (isKeyFrame) {
                        Log.i(TAG, "Successfully queued IDR keyframe (${frameToSend.size}B) into MediaCodec input buffer $inIndex")
                    }
                } else {
                    // Return the buffer to the codec; leaking it would eventually starve the decoder.
                    codec.queueInputBuffer(inIndex, 0, 0, presentationTimeUs, 0)
                    Log.w(TAG, "Dropping ${frameToSend.size}B frame: exceeds decoder input buffer (${inputBuffer?.capacity()}B)")
                }
            } else {
                Log.w(TAG, "Dropping frame: decoder input buffer unavailable after retries (isKeyFrame=$isKeyFrame)")
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
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
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
            } else if (outIndex >= 0) {
                if (!renderOutput(codec, outIndex, bufferInfo.presentationTimeUs)) {
                    outIndex = codec.dequeueOutputBuffer(bufferInfo, 0L)
                    continue
                }
                if (!_hasShownFrame.value) _hasShownFrame.value = true
                frameCount++
                val now = System.currentTimeMillis()
                val elapsed = now - lastFpsCalculationTime
                if (elapsed >= 1000L) {
                    val fps = (frameCount * 1000L / elapsed).toInt()
                    val bitrateMbps = bytesDecoded * 8f / (elapsed * 1000f)
                    AirPlaySessionManager.updateMetrics(fps, bitrateMbps)
                    Log.i(TAG, "Rendering video successfully at $fps fps, %.2f Mbps (decoder=${codec.name}, res=${streamWidth}x${streamHeight})".format(bitrateMbps))
                    frameCount = 0
                    bytesDecoded = 0
                    lastFpsCalculationTime = now
                }
            }
            // Any other value is the deprecated INFO_OUTPUT_BUFFERS_CHANGED, which needs no action.
            outIndex = codec.dequeueOutputBuffer(bufferInfo, 0L)
        }
    }

    private fun rememberTimestamp(presentationTimeUs: Long, inputTimeNs: Long, localTimeNs: Long, arrivalDelayNs: Long) {
        inputPtsRing[ringIndex] = presentationTimeUs
        inputTimeRing[ringIndex] = inputTimeNs
        localTimeRing[ringIndex] = localTimeNs
        arrivalDelayRing[ringIndex] = arrivalDelayNs
        ringIndex = (ringIndex + 1) % TIMESTAMP_RING_SIZE
    }

    /** Ring slot of the input with this presentation time, or -1. */
    private fun lookupTimestamp(presentationTimeUs: Long): Int {
        for (i in 1..TIMESTAMP_RING_SIZE) {
            val index = (ringIndex - i + TIMESTAMP_RING_SIZE) % TIMESTAMP_RING_SIZE
            if (inputPtsRing[index] == presentationTimeUs) return index
        }
        return -1
    }

    /**
     * Renders a decoded frame. By default it is shown immediately (lowest latency). In timestamp-sync
     * mode it is scheduled for its sender timestamp plus the sync latency and dropped when already late.
     * Returns false when the frame was dropped.
     */
    private fun renderOutput(codec: MediaCodec, outIndex: Int, presentationTimeUs: Long): Boolean {
        val slot = lookupTimestamp(presentationTimeUs)
        val localTimeNs = if (slot >= 0) localTimeRing[slot] else 0L
        if (localTimeNs == REPLAY_HIDDEN_NS) {
            codec.releaseOutputBuffer(outIndex, false)
            return false
        }
        val now = System.nanoTime()
        val codecNs = if (slot >= 0) now - inputTimeRing[slot] else -1L
        if (codecNs in 0..MAX_CODEC_DELAY_NS) codecDelayNs += (codecNs - codecDelayNs) / 8
        if (!AvSync.enabled || localTimeNs <= 0L) {
            // An explicit time: the presentation time can run ahead of the clock (MIN_PTS_STEP_US),
            // and with render=true the display would hold the frame until then.
            codec.releaseOutputBuffer(outIndex, now)
            return true
        }
        // The unavoidable delay (network + decoding), excluding the time a frame was held on purpose.
        AvSync.reportVideoDecodeDelay(arrivalDelayRing[slot] + codecNs, now)
        val target = localTimeNs + AvSync.latencyNs()
        if (target < now - LATE_FRAME_DROP_NS) {
            codec.releaseOutputBuffer(outIndex, false)
            return false
        }
        codec.releaseOutputBuffer(outIndex, target.coerceIn(now, now + MAX_RENDER_AHEAD_NS))
        return true
    }

    private fun releaseCodec() {
        val codec = mediaCodec ?: return
        mediaCodec = null
        try {
            codec.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaCodec", e)
        }
        try {
            codec.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing MediaCodec", e)
        }
    }

    private fun createCodec(): MediaCodec {
        return if (useSoftwareDecoder) {
            val name = findSoftwareDecoderName() ?: FALLBACK_SOFTWARE_DECODER
            Log.i(TAG, "Creating software AVC decoder: $name")
            MediaCodec.createByCodecName(name)
        } else {
            try {
                Log.i(TAG, "Creating hardware AVC decoder by type: $MIME_TYPE")
                MediaCodec.createDecoderByType(MIME_TYPE)
            } catch (e: Exception) {
                Log.w(TAG, "Hardware decoder creation failed, falling back to a software decoder", e)
                useSoftwareDecoder = true
                MediaCodec.createByCodecName(findSoftwareDecoderName() ?: FALLBACK_SOFTWARE_DECODER)
            }
        }
    }

    /** Platform software AVC decoder (c2.android.avc.decoder on Android 10+, OMX.google.* before). */
    private fun findSoftwareDecoderName(): String? = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
            !info.isEncoder &&
                info.supportedTypes.any { it.equals(MIME_TYPE, ignoreCase = true) } &&
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    info.isSoftwareOnly
                } else {
                    info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android.")
                }
        }?.name
    }.getOrNull()

    private fun ensureAnnexBPrefix(data: ByteArray): ByteArray {
        if (data.size >= 4 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 0.toByte() && data[3] == 1.toByte()) {
            return data
        }
        if (data.size >= 3 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 1.toByte()) {
            return byteArrayOf(0) + data
        }
        return byteArrayOf(0, 0, 0, 1) + data
    }
}
