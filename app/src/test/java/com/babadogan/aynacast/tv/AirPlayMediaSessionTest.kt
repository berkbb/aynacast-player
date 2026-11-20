package com.babadogan.aynacast.tv

import android.media.session.PlaybackState
import com.babadogan.aynacast.tv.model.AppLanguage
import com.babadogan.aynacast.tv.service.MediaSessionMode
import com.babadogan.aynacast.tv.service.resolveMediaSessionSnapshot
import com.babadogan.aynacast.tv.session.NowPlaying
import com.babadogan.aynacast.tv.session.VideoCastSession
import com.babadogan.aynacast.tv.util.StorageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class AirPlayMediaSessionTest {

    @Test
    fun inactiveWhenNothingStreams() {
        val snapshot = resolveMediaSessionSnapshot(
            isStreaming = false,
            isVideoActive = false,
            isAudioActive = false,
            nowPlaying = NowPlaying(),
            clientName = "iPhone",
            castMediaActive = false,
            castPlayback = VideoCastSession.PlaybackInfo(),
            language = AppLanguage.TR
        )
        assertEquals(MediaSessionMode.INACTIVE, snapshot.mode)
    }

    @Test
    fun airPlayAudioPublishesMetadataAndStopAction() {
        val snapshot = resolveMediaSessionSnapshot(
            isStreaming = true,
            isVideoActive = false,
            isAudioActive = true,
            nowPlaying = NowPlaying(
                title = "Track",
                artist = "Artist",
                album = "Album",
                positionMs = 15_000L,
                durationMs = 180_000L
            ),
            clientName = "Berk iPhone",
            castMediaActive = false,
            castPlayback = VideoCastSession.PlaybackInfo(),
            language = AppLanguage.EN
        )
        assertEquals(MediaSessionMode.AIRPLAY_AUDIO, snapshot.mode)
        assertEquals("Track", snapshot.title)
        assertEquals("Artist", snapshot.artist)
        assertEquals("Album", snapshot.album)
        assertTrue(snapshot.isPlaying)
        assertEquals(15_000L, snapshot.positionMs)
        assertEquals(180_000L, snapshot.durationMs)
        assertEquals(PlaybackState.ACTION_STOP, snapshot.actions)
    }

    @Test
    fun mirroringVideoSuppressesAudioMediaSession() {
        val snapshot = resolveMediaSessionSnapshot(
            isStreaming = true,
            isVideoActive = true,
            isAudioActive = true,
            nowPlaying = NowPlaying(title = "Old"),
            clientName = "Mac",
            castMediaActive = false,
            castPlayback = VideoCastSession.PlaybackInfo(),
            language = AppLanguage.TR
        )
        assertEquals(MediaSessionMode.INACTIVE, snapshot.mode)
    }

    @Test
    fun videoCastMapsPlaybackStateAndActions() {
        val loading = resolveMediaSessionSnapshot(
            isStreaming = false,
            isVideoActive = false,
            isAudioActive = false,
            nowPlaying = NowPlaying(),
            clientName = "iPad",
            castMediaActive = true,
            castPlayback = VideoCastSession.PlaybackInfo(
                state = VideoCastSession.State.LOADING,
                durationSec = 120.0,
                positionSec = 5.0
            ),
            language = AppLanguage.EN
        )
        assertEquals(MediaSessionMode.VIDEO_CAST, loading.mode)
        assertTrue(loading.isBuffering)
        assertFalse(loading.isPlaying)

        val playing = resolveMediaSessionSnapshot(
            isStreaming = false,
            isVideoActive = false,
            isAudioActive = false,
            nowPlaying = NowPlaying(),
            clientName = "iPad",
            castMediaActive = true,
            castPlayback = VideoCastSession.PlaybackInfo(
                state = VideoCastSession.State.PLAYING,
                durationSec = 120.0,
                positionSec = 42.5
            ),
            language = AppLanguage.EN
        )
        assertFalse(playing.isBuffering)
        assertTrue(playing.isPlaying)
        assertEquals(42_500L, playing.positionMs)
        assertEquals(120_000L, playing.durationMs)
        assertTrue((playing.actions and PlaybackState.ACTION_PLAY_PAUSE) != 0L)
        assertTrue((playing.actions and PlaybackState.ACTION_STOP) != 0L)
    }

    @Test
    fun storageFormatFormatsUnitsAndPercent() {
        assertEquals("0 MB", StorageFormat.formatBytes(0L, Locale.ROOT))
        assertEquals("512 MB", StorageFormat.formatBytes(512_000_000L, Locale.ROOT))
        assertEquals("4.5 GB", StorageFormat.formatBytes(4_500_000_000L, Locale.ROOT))
        assertEquals(75, StorageFormat.usedPercent(2_500_000_000L, 10_000_000_000L))
        assertEquals(0, StorageFormat.usedPercent(0L, 0L))
    }
}
