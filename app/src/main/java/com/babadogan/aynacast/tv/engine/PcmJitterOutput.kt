package com.babadogan.aynacast.tv.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import android.util.Log

/**
 * Plays 16-bit PCM through a jitter buffer and a dedicated writer thread.
 *
 * AirPlay audio arrives in Wi-Fi bursts, and the sender's clock never runs at exactly the TV's
 * output rate. Writing packets straight into a small AudioTrack overflowed it during a burst
 * (audio silently dropped) and drained it in the gap after (underrun): constant crackle. Here the
 * network side only appends to a queue; the writer thread hands the TV fixed chunks with blocking
 * writes, so the TV's own clock paces playback.
 *
 * - The queue is kept near an adaptive target: every time it runs dry the target grows, and it
 *   shrinks slowly again after a quiet period, so a clean network keeps the latency low.
 * - Clock drift is absorbed by dropping or repeating one sample frame per chunk, a ~0.2% speed
 *   change nobody hears, instead of discarding whole packets.
 * - Every refill pause adds its length to the latency for good (the sender keeps sending), which
 *   that correction would take minutes to undo. When the queue never came near the target for a
 *   while, the excess is cut at once, with a short fade-in.
 * - A track that stops consuming (seen on a Philips TV) is detected and rebuilt.
 *
 * Only the queue depends on [profile]; the AudioTrack is the same small, always-full buffer.
 */
internal class PcmJitterOutput(
    private val sampleRate: Int,
    private val channelCount: Int,
    initialVolume: Float,
    private val profile: Profile = Profile.LOW_LATENCY
) {
    /** Queue target bounds (ms): where it starts, and how far refills and quiet periods move it. */
    enum class Profile(val startMs: Int, val minMs: Int, val maxMs: Int) {
        // Screen mirroring: the picture is shown at once, so audio latency is lip-sync error.
        LOW_LATENCY(startMs = 120, minMs = 60, maxMs = 400),
        // Music has no picture to match: start deep enough for the iPhone's Wi-Fi gaps (up to ~410 ms
        // measured) instead of growing into them with a refill pause each.
        MUSIC(startMs = 400, minMs = 400, maxMs = 800)
    }

    private val frameBytes = channelCount * BYTES_PER_SAMPLE
    private val chunkFrames = sampleRate * CHUNK_MS / 1000

    private val lock = Object()
    // Ring buffer of PCM; guarded by [lock].
    private val ring = ByteArray(bytesForMillis(MAX_QUEUE_MS + profile.maxMs))
    private var readPos = 0
    private var queued = 0
    private var buffering = true
    private var targetMs = profile.startMs
    private var lastRebufferAtMs = SystemClock.elapsedRealtime()
    private var lastTargetDecayAtMs = lastRebufferAtMs
    // Lowest queue level since [windowStartMs], for the latency trim.
    private var windowMinBytes = Int.MAX_VALUE
    private var windowStartMs = lastRebufferAtMs
    private var fadeInFramesLeft = 0
    @Volatile private var running = true

    // Statistics since the last [takeStats]; guarded by [lock].
    private var rebuffers = 0
    private var overflowBytes = 0L
    private var framesDropped = 0
    private var framesRepeated = 0
    private var trimmedMs = 0L
    private var maxArrivalGapMs = 0L
    private var minQueuedMs = Long.MAX_VALUE
    private var lastArrivalAtMs = 0L

    @Volatile private var volume = initialVolume
    @Volatile private var track: AudioTrack? = null
    // elapsedRealtime when the writer last finished a write; a stalled track never returns.
    @Volatile private var lastWriteDoneAtMs = SystemClock.elapsedRealtime()
    @Volatile private var writing = false
    @Volatile private var rebuildTrack = false
    private var lastUnderrunCount = 0

    private val writer = Thread({ runWriter() }, "AirPlay-AudioOut").apply { start() }

    /** Appends PCM; never blocks on the TV's audio output. */
    fun write(data: ByteArray, length: Int) {
        if (length <= 0) return
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (lastArrivalAtMs > 0L) maxArrivalGapMs = maxOf(maxArrivalGapMs, now - lastArrivalAtMs)
            lastArrivalAtMs = now
            // Above the cap the oldest audio goes: latency must stay bounded if the output stalls.
            val capBytes = bytesForMillis(targetMs + MAX_QUEUE_MS)
            val excess = queued + length - capBytes
            if (excess > 0) {
                val drop = alignToFrame(minOf(excess, queued))
                readPos = (readPos + drop) % ring.size
                queued -= drop
                overflowBytes += drop
            }
            val bytes = alignToFrame(minOf(length, ring.size - queued))
            val writePos = (readPos + queued) % ring.size
            val firstPart = minOf(bytes, ring.size - writePos)
            System.arraycopy(data, 0, ring, writePos, firstPart)
            System.arraycopy(data, firstPart, ring, 0, bytes - firstPart)
            queued += bytes
            overflowBytes += length - bytes
            lock.notifyAll()
        }
        checkStall(now)
    }

    fun setVolume(gain: Float) {
        volume = gain
        track?.setVolume(gain)
    }

    /** Stops the writer and frees the track. */
    fun release() {
        running = false
        synchronized(lock) { lock.notifyAll() }
        // Unblocks a write that waits on a stalled output.
        track?.let { runCatching { it.pause(); it.flush() } }
        writer.join(RELEASE_TIMEOUT_MS)
    }

    /** One line for the periodic audio log; resets the counters. */
    fun takeStats(): String {
        val t = track
        val underruns = t?.underrunCount ?: lastUnderrunCount
        val trackUnderruns = underruns - lastUnderrunCount
        lastUnderrunCount = underruns
        synchronized(lock) {
            val line = "queue ${millisFor(queued)} ms (min ${if (minQueuedMs == Long.MAX_VALUE) 0 else minQueuedMs}) " +
                "target $targetMs ms, track ${t?.let { it.bufferSizeInFrames * 1000L / sampleRate } ?: 0} ms, " +
                "rebuffers +$rebuffers, track underruns +$trackUnderruns, overflow $overflowBytes B, " +
                "drift -$framesDropped/+$framesRepeated frames, trimmed $trimmedMs ms, max arrival gap $maxArrivalGapMs ms"
            rebuffers = 0
            overflowBytes = 0
            framesDropped = 0
            framesRepeated = 0
            trimmedMs = 0
            maxArrivalGapMs = 0
            minQueuedMs = Long.MAX_VALUE
            return line
        }
    }

    // ─── Writer thread ───

    private fun runWriter() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        // One frame more than a chunk, for a repeated frame.
        val chunk = ByteArray((chunkFrames + 1) * frameBytes)
        try {
            while (running) {
                val audioTrack = track?.takeUnless { rebuildTrack } ?: createTrack() ?: break
                val length = takeChunk(chunk)
                if (length <= 0) continue
                writing = true
                val written = audioTrack.write(chunk, 0, length, AudioTrack.WRITE_BLOCKING)
                writing = false
                lastWriteDoneAtMs = SystemClock.elapsedRealtime()
                if (written < 0) {
                    Log.w(TAG, "AudioTrack write failed ($written); rebuilding the track")
                    rebuildTrack = true
                }
            }
        } catch (e: InterruptedException) {
            // release()
        } catch (e: Exception) {
            Log.e(TAG, "Audio writer failed", e)
        } finally {
            writing = false
            releaseTrack()
        }
    }

    /**
     * Waits until audio is due and copies one chunk into [out], adjusted for drift. Returns the
     * byte count, or 0 when nothing should be written yet.
     */
    private fun takeChunk(out: ByteArray): Int {
        synchronized(lock) {
            if (buffering) {
                if (queued < bytesForMillis(targetMs)) {
                    lock.wait(WAIT_POLL_MS)
                    return 0
                }
                buffering = false
                startTrimWindow()
            }
            val chunkBytes = chunkFrames * frameBytes
            if (queued < chunkBytes) {
                // Ran dry: one clean gap while the queue refills beats crackling on every packet.
                buffering = true
                rebuffers++
                val now = SystemClock.elapsedRealtime()
                targetMs = minOf(targetMs + TARGET_STEP_UP_MS, profile.maxMs)
                lastRebufferAtMs = now
                lastTargetDecayAtMs = now
                return 0
            }
            decayTarget()
            trimExcessLatency()
            minQueuedMs = minOf(minQueuedMs, millisFor(queued))

            val level = millisFor(queued)
            var length = chunkBytes
            var consumed = chunkBytes
            if (level > targetMs + DRIFT_BAND_MS && queued >= chunkBytes + frameBytes) {
                // Running ahead: skip one frame.
                consumed += frameBytes
                framesDropped++
            } else if (level < targetMs - DRIFT_BAND_MS) {
                // Running behind: play the last frame twice.
                length += frameBytes
                framesRepeated++
            }
            copyOut(out, minOf(consumed, chunkBytes))
            if (length > chunkBytes) {
                System.arraycopy(out, chunkBytes - frameBytes, out, chunkBytes, frameBytes)
            }
            readPos = (readPos + consumed) % ring.size
            queued -= consumed
            applyFadeIn(out, length)
            return length
        }
    }

    private fun startTrimWindow() {
        windowMinBytes = Int.MAX_VALUE
        windowStartMs = SystemClock.elapsedRealtime()
    }

    /** Cuts the latency back to the target when the queue stayed well above it for a whole window. */
    private fun trimExcessLatency() {
        windowMinBytes = minOf(windowMinBytes, queued)
        if (SystemClock.elapsedRealtime() - windowStartMs < TRIM_WINDOW_MS) return
        val excess = alignToFrame(windowMinBytes - bytesForMillis(targetMs))
        if (excess > bytesForMillis(TRIM_THRESHOLD_MS)) {
            readPos = (readPos + excess) % ring.size
            queued -= excess
            trimmedMs += millisFor(excess)
            fadeInFramesLeft = sampleRate * FADE_IN_MS / 1000
        }
        startTrimWindow()
    }

    /** Ramps the start of [out] up from silence after a trim, so the cut does not click. */
    private fun applyFadeIn(out: ByteArray, length: Int) {
        if (fadeInFramesLeft <= 0) return
        val total = sampleRate * FADE_IN_MS / 1000
        var i = 0
        while (i < length && fadeInFramesLeft > 0) {
            val gain = (total - fadeInFramesLeft).toFloat() / total
            for (c in 0 until channelCount) {
                val at = i + c * BYTES_PER_SAMPLE
                val sample = ((out[at + 1].toInt() shl 8) or (out[at].toInt() and 0xFF)) * gain
                val scaled = sample.toInt()
                out[at] = scaled.toByte()
                out[at + 1] = (scaled shr 8).toByte()
            }
            fadeInFramesLeft--
            i += frameBytes
        }
    }

    private fun copyOut(out: ByteArray, bytes: Int) {
        val firstPart = minOf(bytes, ring.size - readPos)
        System.arraycopy(ring, readPos, out, 0, firstPart)
        System.arraycopy(ring, 0, out, firstPart, bytes - firstPart)
    }

    private fun decayTarget() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRebufferAtMs < TARGET_DECAY_QUIET_MS || now - lastTargetDecayAtMs < TARGET_DECAY_QUIET_MS) return
        targetMs = maxOf(targetMs - TARGET_STEP_DOWN_MS, profile.minMs)
        lastTargetDecayAtMs = now
    }

    /** A blocking write that never returns means the TV stopped consuming audio. */
    private fun checkStall(now: Long) {
        if (!writing || now - lastWriteDoneAtMs < STALL_MS) return
        val stalled = track ?: return
        Log.w(TAG, "Audio output stalled for ${now - lastWriteDoneAtMs} ms; rebuilding the track")
        lastWriteDoneAtMs = now
        rebuildTrack = true
        runCatching { stalled.pause(); stalled.flush() }
    }

    private fun createTrack(): AudioTrack? {
        releaseTrack()
        rebuildTrack = false
        return try {
            val channelMask = if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            // Blocking writes keep the track full, so its size is pure latency; the queue absorbs jitter.
            val bufferSize = maxOf(minBufferSize, bytesForMillis(TRACK_BUFFER_MS))
            val created = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            created.setVolume(volume)
            created.play()
            track = created
            lastUnderrunCount = created.underrunCount
            lastWriteDoneAtMs = SystemClock.elapsedRealtime()
            Log.i(TAG, "AudioTrack created: $sampleRate Hz, $channelCount ch, buffer=$bufferSize B (jitter buffer)")
            created
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack", e)
            null
        }
    }

    private fun releaseTrack() {
        val old = track ?: return
        track = null
        runCatching { old.pause(); old.flush() }
        runCatching { old.release() }
    }

    private fun bytesForMillis(millis: Int): Int = alignToFrame(sampleRate * frameBytes / 1000 * millis)
    private fun millisFor(bytes: Int): Long = bytes.toLong() / frameBytes * 1000L / sampleRate
    private fun alignToFrame(bytes: Int): Int = bytes - bytes % frameBytes

    private companion object {
        const val TAG = "AudioPlaybackEngine"
        const val BYTES_PER_SAMPLE = 2
        const val CHUNK_MS = 10
        const val TRACK_BUFFER_MS = 40
        const val TARGET_STEP_UP_MS = 40
        const val TARGET_STEP_DOWN_MS = 10
        const val TARGET_DECAY_QUIET_MS = 30_000L
        // The queue level may wander this far from the target before drift correction starts.
        const val DRIFT_BAND_MS = 30
        // A queue whose lowest level over this window exceeds the target by the threshold is trimmed.
        const val TRIM_WINDOW_MS = 2_000L
        const val TRIM_THRESHOLD_MS = 60
        const val FADE_IN_MS = 5
        // Audio queued beyond the target plus this is dropped (oldest first).
        const val MAX_QUEUE_MS = 500
        const val STALL_MS = 1_000L
        const val WAIT_POLL_MS = 20L
        const val RELEASE_TIMEOUT_MS = 500L
    }
}
