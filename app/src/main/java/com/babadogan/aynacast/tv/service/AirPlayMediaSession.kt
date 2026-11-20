package com.babadogan.aynacast.tv.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.babadogan.aynacast.tv.model.AppLanguage
import com.babadogan.aynacast.tv.session.NowPlaying
import com.babadogan.aynacast.tv.session.VideoCastSession
import com.babadogan.aynacast.tv.ui.NowPlayingActivity
import com.babadogan.aynacast.tv.ui.VideoCastActivity
import com.babadogan.aynacast.tv.ui.localization.AirPlayStrings

enum class MediaSessionMode { INACTIVE, AIRPLAY_AUDIO, VIDEO_CAST }

data class MediaSessionSnapshot(
    val mode: MediaSessionMode,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = PlaybackState.PLAYBACK_POSITION_UNKNOWN,
    val durationMs: Long = 0L,
    val actions: Long = 0L
)

/**
 * Pure mapper from active AirPlay audio / video-cast session state to the system [MediaSession]
 * metadata and transport state so launchers like OtagHome can show and control playback.
 */
fun resolveMediaSessionSnapshot(
    isStreaming: Boolean,
    isVideoActive: Boolean,
    isAudioActive: Boolean,
    nowPlaying: NowPlaying,
    clientName: String,
    castMediaActive: Boolean,
    castPlayback: VideoCastSession.PlaybackInfo,
    language: AppLanguage
): MediaSessionSnapshot {
    if (castMediaActive && castPlayback.state != VideoCastSession.State.STOPPED) {
        return MediaSessionSnapshot(
            mode = MediaSessionMode.VIDEO_CAST,
            title = AirPlayStrings.notificationVideoCastTitle(language),
            artist = clientName.ifBlank { AirPlayStrings.appName(language) },
            album = "AirPlay",
            isPlaying = castPlayback.state == VideoCastSession.State.PLAYING,
            isBuffering = castPlayback.state == VideoCastSession.State.LOADING,
            positionMs = (castPlayback.positionSec * 1000.0).toLong().coerceAtLeast(0L),
            durationMs = (castPlayback.durationSec * 1000.0).toLong().coerceAtLeast(0L),
            actions = PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_STOP
        )
    }

    if (isStreaming && !isVideoActive && (isAudioActive || nowPlaying.hasContent)) {
        return MediaSessionSnapshot(
            mode = MediaSessionMode.AIRPLAY_AUDIO,
            title = nowPlaying.title.ifBlank { AirPlayStrings.statusPlayingAudio(clientName, language) },
            artist = nowPlaying.artist.ifBlank { clientName.ifBlank { AirPlayStrings.appName(language) } },
            album = nowPlaying.album.ifBlank { "AirPlay" },
            isPlaying = isAudioActive,
            positionMs = if (nowPlaying.durationMs > 0L) nowPlaying.positionMs.coerceAtLeast(0L) else PlaybackState.PLAYBACK_POSITION_UNKNOWN,
            durationMs = nowPlaying.durationMs.coerceAtLeast(0L),
            actions = PlaybackState.ACTION_STOP
        )
    }

    return MediaSessionSnapshot(mode = MediaSessionMode.INACTIVE)
}

/**
 * Publishes active AirPlay music and video-cast sessions as an Android system [MediaSession].
 * Guarded against OS session errors so daemon operation never depends on system media controls.
 */
internal class AirPlayMediaSession(context: Context) {

    companion object {
        private const val TAG = "AirPlayMediaSession"
    }

    private val appContext = context.applicationContext
    private val callbackHandler = Handler(Looper.getMainLooper())
    private val session: MediaSession? = runCatching {
        MediaSession(appContext, "AynaCastTV").apply {
            @Suppress("DEPRECATION")
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(
                object : MediaSession.Callback() {
                    override fun onPlay() {
                        if (VideoCastSession.media.value != null) VideoCastSession.setRate(1f)
                    }

                    override fun onPause() {
                        if (VideoCastSession.media.value != null) VideoCastSession.setRate(0f)
                    }

                    override fun onStop() {
                        AirPlayDaemonService.stopStreaming(appContext)
                    }
                },
                callbackHandler
            )
        }
    }.onFailure {
        Log.w(TAG, "MediaSession unavailable; continuing without system media controls", it)
    }.getOrNull()

    fun update(snapshot: MediaSessionSnapshot, artwork: Bitmap?) {
        val media = session ?: return
        runCatching {
            if (snapshot.mode == MediaSessionMode.INACTIVE) {
                media.setPlaybackState(
                    PlaybackState.Builder()
                        .setState(PlaybackState.STATE_STOPPED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0f)
                        .build()
                )
                media.isActive = false
                return
            }

            val metaBuilder = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, snapshot.title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, snapshot.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, snapshot.artist)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, snapshot.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, snapshot.album)
            if (snapshot.durationMs > 0L) {
                metaBuilder.putLong(MediaMetadata.METADATA_KEY_DURATION, snapshot.durationMs)
            }
            if (artwork != null && !artwork.isRecycled) {
                metaBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork)
                metaBuilder.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, artwork)
            }
            media.setMetadata(metaBuilder.build())

            val playbackState = when {
                snapshot.isBuffering -> PlaybackState.STATE_BUFFERING
                snapshot.isPlaying -> PlaybackState.STATE_PLAYING
                else -> PlaybackState.STATE_PAUSED
            }
            media.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(snapshot.actions)
                    .setState(
                        playbackState,
                        snapshot.positionMs,
                        if (snapshot.isPlaying) 1f else 0f
                    )
                    .build()
            )
            media.setSessionActivity(activityIntentFor(snapshot.mode))
            media.isActive = true
        }.onFailure {
            Log.w(TAG, "Failed updating MediaSession state", it)
        }
    }

    private fun activityIntentFor(mode: MediaSessionMode): PendingIntent {
        val intent: Intent = when (mode) {
            MediaSessionMode.AIRPLAY_AUDIO -> NowPlayingActivity.createIntent(appContext)
            MediaSessionMode.VIDEO_CAST -> VideoCastActivity.createIntent(appContext)
            MediaSessionMode.INACTIVE -> AirPlayDaemonService.settingsActivityIntent(appContext)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getActivity(appContext, mode.ordinal, intent, flags)
    }

    fun release() {
        val media = session ?: return
        runCatching {
            callbackHandler.removeCallbacksAndMessages(null)
            media.isActive = false
            media.setCallback(null)
            media.release()
        }.onFailure {
            Log.w(TAG, "Failed releasing MediaSession", it)
        }
    }
}
