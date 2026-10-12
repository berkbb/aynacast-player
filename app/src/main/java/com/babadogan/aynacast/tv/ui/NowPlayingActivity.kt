package com.babadogan.aynacast.tv.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.babadogan.aynacast.tv.model.AppLanguage
import com.babadogan.aynacast.tv.session.AirPlaySessionManager
import com.babadogan.aynacast.tv.session.NowPlaying
import com.babadogan.aynacast.tv.settings.AppSettingsRepository
import com.babadogan.aynacast.tv.ui.localization.AirPlayStrings
import com.babadogan.aynacast.tv.ui.theme.AirPlayTvTheme
import com.babadogan.aynacast.tv.ui.theme.AppleAccentBlue
import kotlinx.coroutines.delay

/**
 * Apple TV-like "Now Playing" screen for audio-only AirPlay (music): cover art, track, artist,
 * album and progress. Back closes it while the music keeps playing; it also closes when the
 * session ends or mirrored video starts.
 */
class NowPlayingActivity : ComponentActivity() {

    companion object {
        // Audio packets arriving within this window mean the track is playing (not paused).
        private const val PLAYING_WINDOW_MS = 1_000L

        fun createIntent(context: Context): Intent =
            Intent(context, NowPlayingActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settingsRepo = AppSettingsRepository.getInstance(this)

        setContent {
            val appSettings by settingsRepo.settings.collectAsState()
            val isStreaming by AirPlaySessionManager.isStreaming.collectAsState()
            val isVideoActive by AirPlaySessionManager.isVideoActive.collectAsState()
            val nowPlaying by AirPlaySessionManager.nowPlaying.collectAsState()
            val metadata by AirPlaySessionManager.clientMetadata.collectAsState()

            LaunchedEffect(isStreaming, isVideoActive, nowPlaying.hasContent) {
                if (!isStreaming || isVideoActive || !nowPlaying.hasContent) finish()
            }

            AirPlayTvTheme(themeMode = appSettings.themeMode) {
                Box(modifier = Modifier.fillMaxSize()) {
                    NowPlayingScreen(
                        nowPlaying = nowPlaying,
                        clientName = metadata.deviceName,
                        language = appSettings.language
                    )
                    VolumeHud()
                }
            }
        }
    }

    @Composable
    private fun NowPlayingScreen(nowPlaying: NowPlaying, clientName: String, language: AppLanguage) {
        // Ticks twice a second so the progress bar advances between sender updates.
        var nowMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(500)
                nowMs = SystemClock.elapsedRealtime()
            }
        }
        val isPlaying = nowMs - AirPlaySessionManager.lastAudioPacketAtMs < PLAYING_WINDOW_MS
        val position = if (nowPlaying.durationMs <= 0L) 0L else {
            val advanced = if (isPlaying) nowMs - nowPlaying.positionUpdatedAtMs else 0L
            (nowPlaying.positionMs + advanced).coerceIn(0L, nowPlaying.durationMs)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.background)
                    )
                )
                .padding(horizontal = 72.dp, vertical = 56.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(56.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val artwork = nowPlaying.artwork
                if (artwork != null) {
                    Image(
                        bitmap = artwork.asImageBitmap(),
                        contentDescription = nowPlaying.album.ifBlank { nowPlaying.title },
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(360.dp)
                            .clip(RoundedCornerShape(24.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(360.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "♪", fontSize = 140.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = AirPlayStrings.nowPlayingFrom(clientName, language),
                        color = AppleAccentBlue,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = nowPlaying.title.ifBlank { AirPlayStrings.nowPlayingUnknownTitle(language) },
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 38.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 44.sp
                    )
                    if (nowPlaying.artist.isNotBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = nowPlaying.artist,
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 24.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (nowPlaying.album.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = nowPlaying.album,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 20.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (nowPlaying.durationMs > 0L) {
                        Spacer(modifier = Modifier.height(36.dp))
                        LinearProgressIndicator(
                            progress = { position.toFloat() / nowPlaying.durationMs.toFloat() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = AppleAccentBlue,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = formatTime(position),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = "-" + formatTime(nowPlaying.durationMs - position),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 16.sp
                            )
                        }
                    }
                    if (!isPlaying) {
                        Spacer(modifier = Modifier.height(18.dp))
                        Text(
                            text = AirPlayStrings.nowPlayingPaused(language),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 16.sp
                        )
                    }
                }
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }
}
