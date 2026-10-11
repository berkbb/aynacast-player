package com.babadogan.aynacast.tv.engine

import java.util.concurrent.atomic.AtomicLong

/**
 * Optional timestamp-based audio/video synchronisation, the equivalent of UxPlay's "-vsync".
 *
 * The native core maps the sender's timestamps onto the local System.nanoTime() clock (via NTP).
 * When enabled, video frames are presented at `timestamp + latency` (late frames are dropped, as in
 * UxPlay) and audio is played at the same offset. The latency follows the unavoidable video delay
 * (network + decoding) and grows when the TV's audio output needs more; video frames are held
 * before decoding so they can wait as long as audio needs.
 *
 * Disabled (the default) renders everything as soon as it is decoded, which UxPlay recommends for
 * hardware-decoding receivers and macOS "second monitor" use ("-vsync no").
 */
object AvSync {

    private const val MIN_LATENCY_NS = 60_000_000L
    private const val MAX_LATENCY_NS = 500_000_000L
    private const val MARGIN_NS = 20_000_000L
    // Video frames reported within this window mean a mirroring (not audio-only) session.
    private const val VIDEO_ACTIVE_WINDOW_NS = 2_000_000_000L
    // Decode delay rises immediately and decays slowly, so occasional slow frames are not dropped.
    private const val DECAY_SHIFT = 6

    @Volatile
    var enabled: Boolean = false

    private val decodeDelayNs = AtomicLong(0)
    private val lastVideoReportNs = AtomicLong(0)
    // Extra latency the audio path needs (TV output latency, sound processing): learned when audio
    // keeps arriving too late for the current latency. Device-specific, so it does not decay.
    private val audioExtraNs = AtomicLong(0)

    /** True when audio should be scheduled against video timestamps. */
    fun isActive(nowNs: Long = System.nanoTime()): Boolean =
        enabled && nowNs - lastVideoReportNs.get() < VIDEO_ACTIVE_WINDOW_NS

    /** Presentation latency added to sender timestamps. */
    fun latencyNs(): Long =
        (decodeDelayNs.get() + MARGIN_NS + audioExtraNs.get()).coerceIn(MIN_LATENCY_NS, MAX_LATENCY_NS)

    /** Audio could not be played on time by [lateByNs]: raise the shared latency so it can. */
    fun reportAudioLate(lateByNs: Long) {
        if (lateByNs <= 0) return
        audioExtraNs.updateAndGet { (it + lateByNs).coerceAtMost(MAX_LATENCY_NS) }
    }

    /** Records how long after its timestamp a frame finished decoding. */
    fun reportVideoDecodeDelay(delayNs: Long, nowNs: Long = System.nanoTime()) {
        if (delayNs < 0 || delayNs > MAX_LATENCY_NS * 4) return // Clock not synced yet or bogus stamp
        lastVideoReportNs.set(nowNs)
        decodeDelayNs.updateAndGet { current ->
            if (delayNs > current) delayNs else current - ((current - delayNs) shr DECAY_SHIFT)
        }
    }

    fun reset() {
        decodeDelayNs.set(0)
        lastVideoReportNs.set(0)
        audioExtraNs.set(0)
    }
}
