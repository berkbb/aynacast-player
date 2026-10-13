package com.babadogan.aynacast.tv.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.ui.PlayerView
import com.babadogan.aynacast.tv.pairing.PinPairingManager
import com.babadogan.aynacast.tv.service.AirPlayDaemonService
import com.babadogan.aynacast.tv.session.ScreenTransitions
import com.babadogan.aynacast.tv.session.VideoCastSession
import com.babadogan.aynacast.tv.settings.AppSettingsRepository
import com.babadogan.aynacast.tv.ui.localization.AirPlayStrings
import com.babadogan.aynacast.tv.ui.theme.AppleAccentBlue
import com.babadogan.aynacast.tv.ui.theme.AppleAccentOrange
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Full-screen player for AirPlay video casting (URL / HLS playback with Media3 ExoPlayer).
 * The sender controls playback; the TV remote can also pause, resume and seek.
 */
class VideoCastActivity : ComponentActivity() {

    companion object {
        private const val TAG = "VideoCastActivity"
        private const val REPORT_INTERVAL_MS = 500L
        private const val REMOTE_SEEK_MS = 10_000L
        // Media buffered before playback starts (ExoPlayer default 2.5 s) and after a stall (5 s):
        // the sender is waiting for the video to appear, and a home network refills quickly.
        private const val START_BUFFER_MS = 1_000
        private const val REBUFFER_MS = 3_000
        // The preparing card fades away once the first frame is on screen...
        private const val REVEAL_MS = 450
        // ...and the ended card fades in and stays this long before the screen closes.
        private const val OUTRO_FADE_MS = 350
        private const val OUTRO_MS = 1_300L
        // An error message needs time to be read.
        private const val FAILURE_OUTRO_MS = 4_000L
        // At the end of the media the session stays open this long, reported as paused at the end:
        // the sender decides what follows (YouTube autoplay / repeat sends the next /play or a seek),
        // as with an Apple TV. Only then does the screen close.
        private const val END_GRACE_MS = 5_000L
        private val DRM_ERROR_CODES = PlaybackException.ERROR_CODE_DRM_UNSPECIFIED..PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED

        fun createIntent(context: Context): Intent =
            Intent(context, VideoCastActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
    }

    private var player: ExoPlayer? = null
    private var loadedSequence = -1L
    private var appliedSeekId = 0L
    private var appliedRateId = 0L
    private var reportJob: Job? = null
    private var endGraceJob: Job? = null
    // Set once this screen starts closing; from then on it neither plays nor reports, so it
    // cannot disturb a cast that arrives while it is still being destroyed.
    private var closing = false
    // False from loading a video until its first frame is rendered.
    private val firstFrameShown = mutableStateOf(false)
    private val ended = mutableStateOf(false)
    // Why the cast ended, when it was not the sender's or the viewer's choice.
    private enum class Failure { DRM, PLAYBACK }
    private val failure = mutableStateOf<Failure?>(null)
    // The video surface ignores the window's close animation; it is removed once the ended card covers it.
    private val videoRemoved = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                START_BUFFER_MS,
                REBUFFER_MS
            )
            .build()
        val exoPlayer = ExoPlayer.Builder(this).setLoadControl(loadControl).build().also { player = it }
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // Logs buffering, format switches and dropped frames under the "EventLogger" tag.
            exoPlayer.addAnalyticsListener(EventLogger())
        }
        exoPlayer.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = reportState()

            override fun onRenderedFirstFrame() {
                firstFrameShown.value = true
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Video casting playback failed", error)
                // Netflix, Apple TV+ and the like hand over FairPlay-protected streams (sometimes
                // after an unprotected intro); those cannot be played here, so say why.
                failure.value = if (error.errorCode in DRM_ERROR_CODES) Failure.DRM else Failure.PLAYBACK
                VideoCastSession.stop(loadedSequence)
            }
        })

        lifecycleScope.launch {
            combine(VideoCastSession.media, VideoCastSession.controls, ::Pair).collect { (media, controls) ->
                if (closing) return@collect
                if (media == null) {
                    close()
                    return@collect
                }
                if (media.sequence != loadedSequence) {
                    loadedSequence = media.sequence
                    appliedSeekId = 0L
                    appliedRateId = 0L
                    Log.i(TAG, "Casting ${media.url} from ${media.startPositionSec}s")
                    firstFrameShown.value = false
                    exoPlayer.setMediaItem(MediaItem.fromUri(media.url), (media.startPositionSec * 1000).toLong())
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                }
                if (controls.sequence == loadedSequence) applyControls(exoPlayer, controls)
            }
        }
        reportJob = lifecycleScope.launch {
            while (true) {
                delay(REPORT_INTERVAL_MS)
                reportState()
            }
        }

        val settingsRepo = AppSettingsRepository.getInstance(this)
        setContent {
            val playback by VideoCastSession.playback.collectAsState()
            val language = settingsRepo.settings.collectAsState().value.language
            Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                if (!videoRemoved.value) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            PlayerView(context).apply {
                                this.player = exoPlayer
                                useController = false
                                keepScreenOn = true
                            }
                        }
                    )
                }
                // Mid-playback buffering; before the first frame the preparing card says it all.
                if (playback.state == VideoCastSession.State.LOADING && firstFrameShown.value && !ended.value) {
                    CircularProgressIndicator(color = AppleAccentBlue)
                }
                // Covers the player until the video starts, so it appears with a fade instead of a cut.
                AnimatedVisibility(
                    visible = !firstFrameShown.value && !ended.value,
                    enter = fadeIn(tween(REVEAL_MS)),
                    exit = fadeOut(tween(REVEAL_MS)) + scaleOut(tween(REVEAL_MS), targetScale = 1.08f)
                ) {
                    StatusCard(
                        title = AirPlayStrings.castPreparingTitle(language),
                        subtitle = AirPlayStrings.castPreparingSubtitle(language),
                        active = true
                    )
                }
                AnimatedVisibility(visible = ended.value, enter = fadeIn(tween(OUTRO_FADE_MS))) {
                    when (failure.value) {
                        Failure.DRM -> StatusCard(
                            title = AirPlayStrings.castDrmTitle(language),
                            subtitle = AirPlayStrings.castDrmSubtitle(language),
                            active = false, symbol = "!", accent = AppleAccentOrange
                        )
                        Failure.PLAYBACK -> StatusCard(
                            title = AirPlayStrings.castFailedTitle(language),
                            subtitle = AirPlayStrings.castFailedSubtitle(language),
                            active = false, symbol = "!", accent = AppleAccentOrange
                        )
                        null -> StatusCard(
                            title = AirPlayStrings.castEndedTitle(language),
                            subtitle = AirPlayStrings.castEndedSubtitle(language),
                            active = false
                        )
                    }
                }
            }
        }
    }

    private fun applyControls(exoPlayer: ExoPlayer, controls: VideoCastSession.Controls) {
        if (controls.seekId != appliedSeekId) {
            appliedSeekId = controls.seekId
            exoPlayer.seekTo((controls.seekPositionSec * 1000).toLong())
        }
        if (controls.rateId != appliedRateId) {
            appliedRateId = controls.rateId
            val rate = controls.rate
            Log.i(TAG, "Sender sets rate $rate (playing=${exoPlayer.isPlaying})")
            if (rate <= 0f) {
                exoPlayer.pause()
            } else {
                exoPlayer.playbackParameters = PlaybackParameters(rate)
                exoPlayer.play()
            }
        }
        reportState()
    }

    /** The cast ended (sender stop, end of media, Back): shows the ended card, then closes. */
    private fun close() {
        if (closing) return
        closing = true
        reportJob?.cancel()
        player?.stop()
        ended.value = true
        val outroMs = if (failure.value != null) FAILURE_OUTRO_MS else OUTRO_MS
        ScreenTransitions.beginOutro(outroMs)
        lifecycleScope.launch {
            delay(OUTRO_FADE_MS.toLong())
            videoRemoved.value = true
            delay(outroMs - OUTRO_FADE_MS)
            finish()
        }
    }

    private fun reportState() {
        if (closing) return
        val exoPlayer = player ?: return
        val durationMs = exoPlayer.duration.takeIf { it > 0 } ?: 0L
        val state = when {
            exoPlayer.playbackState == Player.STATE_ENDED -> VideoCastSession.State.PAUSED
            exoPlayer.playbackState == Player.STATE_BUFFERING || exoPlayer.playbackState == Player.STATE_IDLE ->
                VideoCastSession.State.LOADING
            exoPlayer.isPlaying -> VideoCastSession.State.PLAYING
            else -> VideoCastSession.State.PAUSED
        }
        VideoCastSession.reportPlayback(
            loadedSequence,
            VideoCastSession.PlaybackInfo(
                state = state,
                durationSec = durationMs / 1000.0,
                positionSec = exoPlayer.currentPosition.coerceAtLeast(0L) / 1000.0,
                bufferedSec = exoPlayer.bufferedPosition.coerceAtLeast(0L) / 1000.0,
                rate = if (exoPlayer.isPlaying) exoPlayer.playbackParameters.speed else 0f,
                readyToPlay = exoPlayer.playbackState == Player.STATE_READY || exoPlayer.playbackState == Player.STATE_ENDED
            )
        )
        if (exoPlayer.playbackState == Player.STATE_ENDED) {
            if (endGraceJob == null) {
                val sequence = loadedSequence
                endGraceJob = lifecycleScope.launch {
                    delay(END_GRACE_MS)
                    VideoCastSession.stop(sequence)
                }
            }
        } else {
            // A seek or the next video arrived in time (replay, autoplay): keep playing.
            endGraceJob?.cancel()
            endGraceJob = null
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // TV remotes differ in what their play/pause buttons send; the log shows what arrives here.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            Log.i(TAG, "Remote key ${KeyEvent.keyCodeToString(event.keyCode)} (scan code ${event.scanCode})")
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val exoPlayer = player ?: return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> exoPlayer.play()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> exoPlayer.pause()
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ->
                exoPlayer.seekTo(exoPlayer.currentPosition + REMOTE_SEEK_MS)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND ->
                exoPlayer.seekTo((exoPlayer.currentPosition - REMOTE_SEEK_MS).coerceAtLeast(0L))
            KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_BACK -> {
                // Tells the sender playback stopped; close() also covers a cast already replaced.
                VideoCastSession.stop(loadedSequence)
                close()
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        reportState()
        return true
    }

    override fun onResume() {
        super.onResume()
        AirPlayDaemonService.cancelScreenNotification(this, AirPlayDaemonService.VIDEO_CAST_NOTIFICATION_ID)
    }

    override fun onStop() {
        super.onStop()
        // Home, another app or TV standby: nobody is watching, so end the cast rather than keep
        // playing invisibly (the screen is excluded from Recents and cannot be brought back).
        // Our own PIN code screen (another device pairing) only covers it for a moment.
        if (!isChangingConfigurations && !isFinishing && PinPairingManager.pinCode.value == null) finish()
    }

    override fun onDestroy() {
        reportJob?.cancel()
        player?.release()
        player = null
        // Leaving the screen (Back, Home) ends the cast so the sender knows playback stopped.
        if (!closing && !isChangingConfigurations) VideoCastSession.stop(loadedSequence)
        super.onDestroy()
    }
}
