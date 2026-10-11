package com.babadogan.aynacast.tv.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * AirPlay video casting (an app's AirPlay button sending a media URL, as opposed to mirroring).
 * The native server turns /play, /scrub, /rate and /stop into calls here; the player screen
 * executes them and reports its state back, which answers /playback-info and drives events.
 *
 * Everything is tagged with the /play [Media.sequence], so a player screen that is still closing
 * cannot report for, or stop, the cast that replaced it.
 */
object VideoCastSession {

    /** Media requested by the sender. [sequence] changes for every /play, even for the same URL. */
    data class Media(val url: String, val startPositionSec: Double, val sequence: Long)

    /**
     * Sender controls for the media with [sequence]. Kept as state rather than one-shot events so a
     * /scrub or /rate that arrives before the player screen is listening is not lost.
     * [seekId] changes for every /scrub and [rateId] for every /rate (the TV remote may have
     * changed playback in between, so a repeated rate must still apply); [rateId] 0 means none yet.
     */
    data class Controls(
        val sequence: Long = 0L,
        val seekId: Long = 0L,
        val seekPositionSec: Double = 0.0,
        val rateId: Long = 0L,
        val rate: Float = 1f
    )

    enum class State { LOADING, PLAYING, PAUSED, STOPPED }

    data class PlaybackInfo(
        val state: State = State.STOPPED,
        val durationSec: Double = 0.0,
        val positionSec: Double = 0.0,
        val bufferedSec: Double = 0.0,
        val rate: Float = 0f,
        val readyToPlay: Boolean = false
    )

    private val _media = MutableStateFlow<Media?>(null)
    val media: StateFlow<Media?> = _media.asStateFlow()

    private val _controls = MutableStateFlow(Controls())
    val controls: StateFlow<Controls> = _controls.asStateFlow()

    private val _playback = MutableStateFlow(PlaybackInfo())
    val playback: StateFlow<PlaybackInfo> = _playback.asStateFlow()

    private var sequence = 0L
    private var seekId = 0L
    private var rateId = 0L

    @Synchronized
    fun play(url: String, startPositionSec: Double) {
        val next = ++sequence
        _controls.value = Controls(sequence = next)
        _playback.value = PlaybackInfo(state = State.LOADING)
        _media.value = Media(url, startPositionSec.coerceAtLeast(0.0), next)
    }

    @Synchronized
    fun seek(positionSec: Double) {
        _controls.update { it.copy(seekId = ++seekId, seekPositionSec = positionSec.coerceAtLeast(0.0)) }
    }

    @Synchronized
    fun setRate(rate: Float) {
        _controls.update { it.copy(rateId = ++rateId, rate = rate) }
    }

    /** The sender stopped, or its connections are gone. */
    @Synchronized
    fun stop() {
        _media.value = null
        _playback.value = PlaybackInfo(state = State.STOPPED)
    }

    /** Stop from the player (end of media, error, Back): only if [sequence] is still the current cast. */
    @Synchronized
    fun stop(sequence: Long) {
        if (_media.value?.sequence == sequence) stop()
    }

    /** Called by the player screen whenever its state changes and periodically while playing. */
    @Synchronized
    fun reportPlayback(sequence: Long, info: PlaybackInfo) {
        if (_media.value?.sequence == sequence) _playback.value = info
    }
}
