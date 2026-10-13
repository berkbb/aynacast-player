package com.babadogan.aynacast.tv.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer

/**
 * Plays the AirPlay audio stream.
 *
 * ALAC streams arrive already decoded to 16-bit PCM by the native core. AAC-ELD (screen mirroring)
 * and AAC-LC streams arrive as encoded access units and are decoded with MediaCodec here.
 * The PCM plays through [PcmJitterOutput]; mirroring audio in timestamp-sync mode is placed into an
 * AudioTrack directly, by sender timestamp.
 * All methods are called from native server threads and are serialized by the object monitor.
 */
object AudioPlaybackEngine {

    private const val TAG = "AudioPlaybackEngine"
    private const val DEFAULT_SAMPLE_RATE = 44100
    private const val DEFAULT_CHANNELS = 2
    private const val BYTES_PER_SAMPLE = 2
    private const val PREROLL_MS = 40
    private const val DECODER_INPUT_TIMEOUT_US = 5_000L
    private const val MAX_DECODER_RESTARTS = 3
    // Timestamp-sync mode: tolerated scheduling error and the most silence inserted at once.
    private const val SYNC_TOLERANCE_NS = 30_000_000L
    private const val MAX_SILENCE_NS = 200_000_000L
    // Timestamp sync may hold audio back by up to AvSync's maximum latency; the track needs room for it.
    private const val SYNC_TRACK_BUFFER_MS = 700
    // Late packets in a row before the shared latency is raised (one late packet is just jitter)...
    private const val LATE_PACKETS_BEFORE_RAISE = 3
    // ...and before giving up on dropping them (unusable timestamps): play rather than stay silent.
    private const val MAX_CONSECUTIVE_SKIPS = 25
    private const val TIMESTAMP_RING_SIZE = 64
    private const val STATS_INTERVAL_MS = 5_000L
    // FDK-based platform decoders support AAC-ELD; vendor DSP decoders frequently do not.
    private val PREFERRED_AAC_DECODERS = listOf("c2.android.aac.decoder", "OMX.google.aac.decoder")

    private var appContext: Context? = null
    private var codec = AirPlayAudioFormat.CODEC_STOPPED
    private var sampleRate = DEFAULT_SAMPLE_RATE
    private var channelCount = DEFAULT_CHANNELS

    // Default mode: a jitter buffer with its own writer thread (see PcmJitterOutput).
    private var jitterOutput: PcmJitterOutput? = null
    // Timestamp-sync mode writes into this track directly, placing audio by sender timestamps.
    private var audioTrack: AudioTrack? = null
    private var trackStarted = false
    private var bytesWrittenBeforePlay = 0
    private var framesWritten = 0L

    // Output health, logged every STATS_INTERVAL_MS while audio plays.
    private var droppedBytes = 0L
    private var decoderInputMisses = 0
    private var lastStatsAtMs = 0L
    private var lastUnderruns = 0
    private val audioTimestamp = AudioTimestamp()
    private var silence = ByteArray(0)
    private var consecutiveLatePackets = 0
    private var volumeGain = 1f

    private var aacDecoder: MediaCodec? = null
    private var aacConfig: ByteArray? = null
    private var aacSamplesPerFrame = 0
    private var decodedFrames = 0L
    private var decoderRestarts = 0
    private val bufferInfo = MediaCodec.BufferInfo()
    private var pcmScratch = ByteArray(8192)
    // AAC input presentation time (µs) -> sender timestamp on the local clock (ns).
    private val inputPtsRing = LongArray(TIMESTAMP_RING_SIZE)
    private val localTimeRing = LongArray(TIMESTAMP_RING_SIZE)
    private var ringIndex = 0

    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    @Synchronized
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Applies the format the sender negotiated in its audio SETUP (or stops audio on CODEC_STOPPED). */
    @Synchronized
    fun configure(codec: Int, sampleRate: Int, channels: Int, samplesPerFrame: Int) {
        releaseOutput()
        if (codec == AirPlayAudioFormat.CODEC_STOPPED) {
            this.codec = codec
            abandonAudioFocus()
            Log.i(TAG, "Audio stream stopped by sender.")
            return
        }

        this.codec = codec
        this.sampleRate = if (sampleRate > 0) sampleRate else DEFAULT_SAMPLE_RATE
        this.channelCount = channels.coerceIn(1, 2)

        if (AirPlayAudioFormat.isAac(codec)) {
            aacConfig = AirPlayAudioFormat.audioSpecificConfig(codec, this.sampleRate, channelCount, samplesPerFrame)
            aacSamplesPerFrame = samplesPerFrame
            decoderRestarts = 0
            aacDecoder = createAacDecoder()
        }
        requestAudioFocus()
        Log.i(TAG, "Audio configured: codec=$codec ${this.sampleRate} Hz, $channelCount ch, $samplesPerFrame samples/frame")
    }

    /** [localTimeNs] is the sender timestamp on the System.nanoTime() clock, or 0 when unknown. */
    @Synchronized
    fun writePacket(data: ByteArray, localTimeNs: Long = 0L) {
        when (codec) {
            AirPlayAudioFormat.CODEC_PCM_S16 -> writePcmAt(data, data.size, localTimeNs)
            AirPlayAudioFormat.CODEC_AAC_LC, AirPlayAudioFormat.CODEC_AAC_ELD -> decodeAac(data, localTimeNs)
            else -> Unit // No audio stream configured yet
        }
    }

    @Synchronized
    fun setVolumeDb(volumeDb: Float) {
        volumeGain = AirPlayAudioFormat.volumeDbToGain(volumeDb)
        audioTrack?.setVolume(volumeGain)
        jitterOutput?.setVolume(volumeGain)
    }

    /**
     * Sync mode writes into its own, larger track (see SYNC_TRACK_BUFFER_MS); the output is rebuilt
     * for the new mode on the next packet, so switching the setting mid-session takes effect at once.
     */
    @Synchronized
    fun onSyncModeChanged() {
        releaseTrack()
        consecutiveLatePackets = 0
    }

    @Synchronized
    fun stop() {
        releaseOutput()
        codec = AirPlayAudioFormat.CODEC_STOPPED
        volumeGain = 1f
        abandonAudioFocus()
    }

    // ─── AAC decoding ───

    private fun createAacDecoder(): MediaCodec? {
        val config = aacConfig ?: return null
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(config))
        }
        for (name in decoderCandidates()) {
            var decoder: MediaCodec? = null
            try {
                decoder = if (name != null) MediaCodec.createByCodecName(name)
                          else MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                decoder.configure(format, null, null, 0)
                decoder.start()
                decodedFrames = 0
                Log.i(TAG, "AAC decoder started: ${decoder.name}")
                return decoder
            } catch (e: Exception) {
                Log.w(TAG, "AAC decoder ${name ?: "default"} rejected the stream config", e)
                runCatching { decoder?.release() }
            }
        }
        Log.e(TAG, "No AAC decoder available; audio will be muted for this session.")
        return null
    }

    /** Platform FDK decoders first (null = let the framework choose). */
    private fun decoderCandidates(): List<String?> {
        val available = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_AUDIO_AAC, true) } }
                .map { it.name }
        }.getOrDefault(emptyList())
        return PREFERRED_AAC_DECODERS.filter { it in available } + listOf(null)
    }

    private fun decodeAac(frame: ByteArray, localTimeNs: Long) {
        val decoder = aacDecoder ?: return
        try {
            val inIndex = decoder.dequeueInputBuffer(DECODER_INPUT_TIMEOUT_US)
            if (inIndex >= 0) {
                val input = decoder.getInputBuffer(inIndex)
                val ptsUs = decodedFrames * aacSamplesPerFrame * 1_000_000L / sampleRate
                decodedFrames++
                inputPtsRing[ringIndex] = ptsUs
                localTimeRing[ringIndex] = localTimeNs
                ringIndex = (ringIndex + 1) % TIMESTAMP_RING_SIZE
                if (input != null && frame.size <= input.capacity()) {
                    input.clear()
                    input.put(frame)
                    decoder.queueInputBuffer(inIndex, 0, frame.size, ptsUs, 0)
                } else {
                    // Hand the buffer back even when the frame cannot be used, so the decoder never starves.
                    decoder.queueInputBuffer(inIndex, 0, 0, ptsUs, 0)
                }
            } else {
                decoderInputMisses++
            }
            drainAac(decoder)
        } catch (e: IllegalStateException) {
            runCatching { decoder.release() }
            aacDecoder = if (++decoderRestarts <= MAX_DECODER_RESTARTS) {
                Log.e(TAG, "AAC decoder failed; recreating it (attempt $decoderRestarts)", e)
                createAacDecoder()
            } else {
                Log.e(TAG, "AAC decoder keeps failing; audio muted for this stream", e)
                null
            }
        }
    }

    private fun drainAac(decoder: MediaCodec) {
        while (true) {
            val outIndex = decoder.dequeueOutputBuffer(bufferInfo, 0L)
            when {
                outIndex >= 0 -> {
                    val output = decoder.getOutputBuffer(outIndex)
                    val size = bufferInfo.size
                    if (output != null && size > 0) {
                        if (pcmScratch.size < size) pcmScratch = ByteArray(size)
                        output.position(bufferInfo.offset)
                        output.limit(bufferInfo.offset + size)
                        output.get(pcmScratch, 0, size)
                        writePcmAt(pcmScratch, size, lookupLocalTime(bufferInfo.presentationTimeUs))
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = decoder.outputFormat
                    val newRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val newChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2)
                    if (newRate != sampleRate || newChannels != channelCount) {
                        Log.i(TAG, "Decoder output format: $newRate Hz, $newChannels ch")
                        sampleRate = newRate
                        channelCount = newChannels
                        releaseTrack()
                    }
                }
                else -> return // INFO_TRY_AGAIN_LATER
            }
        }
    }

    private fun lookupLocalTime(presentationTimeUs: Long): Long {
        for (i in 1..TIMESTAMP_RING_SIZE) {
            val index = (ringIndex - i + TIMESTAMP_RING_SIZE) % TIMESTAMP_RING_SIZE
            if (inputPtsRing[index] == presentationTimeUs) return localTimeRing[index]
        }
        return 0L
    }

    // ─── PCM output ───

    /**
     * Writes PCM. With timestamp sync active (mirroring with [AvSync] enabled) the chunk is placed so it
     * plays at its sender timestamp plus the sync latency: early audio gets silence in front, late audio
     * is dropped. Otherwise it is played as soon as possible.
     */
    private fun writePcmAt(data: ByteArray, length: Int, localTimeNs: Long) {
        // Timestamp sync aligns mirroring audio (AAC-ELD) with its picture; music has none.
        if (!AvSync.enabled || codec != AirPlayAudioFormat.CODEC_AAC_ELD) {
            writeBuffered(data, length)
            return
        }
        if (localTimeNs <= 0L || !AvSync.isActive()) {
            writePcm(data, length)
            return
        }
        val track = audioTrack ?: createTrack() ?: return
        val error = estimateNextPlayTimeNs(track) - (localTimeNs + AvSync.latencyNs())
        if (error > SYNC_TOLERANCE_NS) {
            // Would play too late. If that persists, the audio path is slower than the latency
            // allows: raise the latency (video waits for audio) instead of skipping forever.
            consecutiveLatePackets++
            if (consecutiveLatePackets >= LATE_PACKETS_BEFORE_RAISE) AvSync.reportAudioLate(error)
            if (consecutiveLatePackets <= MAX_CONSECUTIVE_SKIPS) return
        } else {
            consecutiveLatePackets = 0
            if (error < -SYNC_TOLERANCE_NS) writeSilence(track, minOf(-error, MAX_SILENCE_NS), length)
        }
        writePcm(data, length)
    }

    private fun writeBuffered(data: ByteArray, length: Int) {
        if (audioTrack != null) releaseTrack() // Leaving sync mode.
        val output = jitterOutput ?: PcmJitterOutput(sampleRate, channelCount, volumeGain, jitterProfile())
            .also { jitterOutput = it }
        output.write(data, length)
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastStatsAtMs >= STATS_INTERVAL_MS) {
            lastStatsAtMs = now
            Log.i(TAG, "Audio output: ${output.takeStats()}, decoder input misses $decoderInputMisses")
            decoderInputMisses = 0
        }
    }

    /** AAC-ELD is screen mirroring audio; ALAC and AAC-LC are music without a picture to match. */
    private fun jitterProfile(): PcmJitterOutput.Profile =
        if (codec == AirPlayAudioFormat.CODEC_AAC_ELD) PcmJitterOutput.Profile.LOW_LATENCY
        else PcmJitterOutput.Profile.MUSIC

    /** When the next sample written to [track] will be heard, on the System.nanoTime() clock. */
    private fun estimateNextPlayTimeNs(track: AudioTrack): Long {
        val now = System.nanoTime()
        if (trackStarted && track.getTimestamp(audioTimestamp)) {
            val pending = framesWritten - audioTimestamp.framePosition
            return audioTimestamp.nanoTime + pending * 1_000_000_000L / sampleRate
        }
        val played = if (trackStarted) track.playbackHeadPosition.toLong() else 0L
        return now + (framesWritten - played) * 1_000_000_000L / sampleRate
    }

    private fun queuedMs(track: AudioTrack): Long =
        (framesWritten - track.playbackHeadPosition.toLong()) * 1000L / sampleRate

    private fun logStats(track: AudioTrack) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastStatsAtMs < STATS_INTERVAL_MS) return
        lastStatsAtMs = now
        val bufferedMs = if (trackStarted) queuedMs(track) else framesWritten * 1000L / sampleRate
        val underruns = track.underrunCount
        Log.i(
            TAG, "Audio output: ${bufferedMs} ms buffered of ${track.bufferSizeInFrames * 1000L / sampleRate} ms, " +
                "underruns +${underruns - lastUnderruns}, dropped ${droppedBytes} B, decoder input misses $decoderInputMisses"
        )
        lastUnderruns = underruns
        droppedBytes = 0
        decoderInputMisses = 0
    }

    /** Inserts up to [durationNs] of silence, always leaving room for the [reserveBytes] packet that follows. */
    private fun writeSilence(track: AudioTrack, durationNs: Long, reserveBytes: Int) {
        val frameBytes = channelCount * BYTES_PER_SAMPLE
        val played = if (trackStarted) track.playbackHeadPosition.toLong() else 0L
        val freeFrames = track.bufferSizeInFrames - (framesWritten - played) - reserveBytes / frameBytes
        val frames = minOf(durationNs * sampleRate / 1_000_000_000L, freeFrames)
        val bytes = frames.toInt() * frameBytes
        if (bytes <= 0) return
        if (silence.size < bytes) silence = ByteArray(bytes)
        writePcm(silence, bytes)
    }

    private fun writePcm(data: ByteArray, length: Int) {
        jitterOutput?.let { it.release(); jitterOutput = null } // Entering sync mode.
        val track = audioTrack ?: createTrack() ?: return
        try {
            val written = track.write(data, 0, length, AudioTrack.WRITE_NON_BLOCKING)
            if (written > 0) framesWritten += written / (channelCount * BYTES_PER_SAMPLE)
            if (written in 0 until length) droppedBytes += length - written
            logStats(track)
            if (!trackStarted && written > 0) {
                bytesWrittenBeforePlay += written
                // Pre-roll a little audio before starting so the first network hiccup does not underrun.
                if (bytesWrittenBeforePlay >= bytesForMillis(PREROLL_MS)) {
                    track.play()
                    trackStarted = true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write PCM data into AudioTrack", e)
        }
    }

    private fun createTrack(): AudioTrack? {
        return try {
            val channelMask = if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            val bufferSize = maxOf(minBufferSize * 2, bytesForMillis(SYNC_TRACK_BUFFER_MS))

            val track = AudioTrack.Builder()
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
            track.setVolume(volumeGain)
            audioTrack = track
            trackStarted = false
            bytesWrittenBeforePlay = 0
            framesWritten = 0
            consecutiveLatePackets = 0
            Log.i(TAG, "AudioTrack created: $sampleRate Hz, $channelCount ch, buffer=$bufferSize B")
            track
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack", e)
            null
        }
    }

    private fun bytesForMillis(millis: Int): Int = sampleRate * channelCount * BYTES_PER_SAMPLE * millis / 1000

    private fun releaseTrack() {
        jitterOutput?.release()
        jitterOutput = null
        audioTrack?.let { track ->
            runCatching {
                if (trackStarted) track.pause()
                track.flush()
            }
            runCatching { track.release() }
        }
        audioTrack = null
        trackStarted = false
        bytesWrittenBeforePlay = 0
        lastUnderruns = 0
    }

    private fun releaseOutput() {
        aacDecoder?.let { decoder ->
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
        }
        aacDecoder = null
        aacConfig = null
        releaseTrack()
    }

    // ─── Audio focus ───

    private fun requestAudioFocus() {
        if (audioFocusRequest != null) return
        val manager = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener { /* Mirrored audio keeps playing; the sender controls it. */ }
            .build()
        manager.requestAudioFocus(request)
        audioManager = manager
        audioFocusRequest = request
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let { request -> audioManager?.abandonAudioFocusRequest(request) }
        audioFocusRequest = null
        audioManager = null
    }
}
