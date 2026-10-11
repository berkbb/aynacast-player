package com.babadogan.aynacast.tv.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.babadogan.aynacast.tv.pairing.PinPairingManager
import com.babadogan.aynacast.tv.service.AirPlayDaemonService
import com.babadogan.aynacast.tv.session.VideoCastSession
import com.babadogan.aynacast.tv.ui.theme.AppleAccentBlue
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
    // Set once this screen starts closing; from then on it neither plays nor reports, so it
    // cannot disturb a cast that arrives while it is still being destroyed.
    private var closing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val exoPlayer = ExoPlayer.Builder(this).build().also { player = it }
        exoPlayer.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = reportState()

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Video casting playback failed", error)
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

        setContent {
            val playback by VideoCastSession.playback.collectAsState()
            Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
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
                if (playback.state == VideoCastSession.State.LOADING) {
                    CircularProgressIndicator(color = AppleAccentBlue)
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
            if (rate <= 0f) {
                exoPlayer.pause()
            } else {
                exoPlayer.playbackParameters = PlaybackParameters(rate)
                exoPlayer.play()
            }
        }
        reportState()
    }

    private fun close() {
        closing = true
        reportJob?.cancel()
        player?.stop()
        finish()
    }

    private fun reportState() {
        if (closing) return
        val exoPlayer = player ?: return
        val durationMs = exoPlayer.duration.takeIf { it > 0 } ?: 0L
        val state = when {
            exoPlayer.playbackState == Player.STATE_ENDED -> VideoCastSession.State.STOPPED
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
            VideoCastSession.stop(loadedSequence)
        }
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
            KeyEvent.KEYCODE_MEDIA_STOP -> VideoCastSession.stop(loadedSequence)
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
