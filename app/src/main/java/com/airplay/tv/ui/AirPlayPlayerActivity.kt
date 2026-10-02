package com.airplay.tv.ui

import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.airplay.tv.engine.VideoDecoderEngine
import com.airplay.tv.session.AirPlaySessionManager
import com.airplay.tv.ui.theme.AirPlayTvTheme
import com.airplay.tv.ui.theme.AppleAccentGreen
import com.airplay.tv.ui.theme.AppleGlassOverlay
import com.airplay.tv.ui.theme.AppleTextPrimary
import com.airplay.tv.ui.theme.AppleTextSecondary
import kotlinx.coroutines.delay

import com.airplay.tv.settings.AppSettingsRepository
import com.airplay.tv.ui.localization.AirPlayStrings
import androidx.activity.addCallback
import android.content.Intent
import com.airplay.tv.service.AirPlayDaemonService

class AirPlayPlayerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Root Back button on TV remote exits player cleanly
        onBackPressedDispatcher.addCallback(this) {
            finish()
        }

        // Keep TV screen awake during AirPlay streaming
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val settingsRepo = AppSettingsRepository.getInstance(this)

        setContent {
            val appSettings by settingsRepo.settings.collectAsState()
            val isStreaming by AirPlaySessionManager.isStreaming.collectAsState()
            val metadata by AirPlaySessionManager.clientMetadata.collectAsState()
            val currentFps by AirPlaySessionManager.currentFps.collectAsState()

            AirPlayTvTheme(themeMode = appSettings.themeMode) {
                // Auto-close when iPhone terminates session (TEARDOWN)
                LaunchedEffect(isStreaming) {
                    if (!isStreaming) {
                        finish()
                    }
                }

                AirPlayPlayerScreen(
                    clientName = metadata.deviceName,
                    fps = currentFps,
                    resolution = "${metadata.resolutionWidth}x${metadata.resolutionHeight}",
                    badgeText = AirPlayStrings.airplayActiveBadge(appSettings.language)
                )
            }
        }
    }

    override fun onDestroy() {
        VideoDecoderEngine.unbindSurface()
        super.onDestroy()
        val settings = AppSettingsRepository.getInstance(this).settings.value
        if (!settings.keepAliveInBackground && !AirPlaySessionManager.isStreaming.value) {
            val serviceIntent = Intent(this, AirPlayDaemonService::class.java)
            stopService(serviceIntent)
        }
    }
}

@Composable
fun AirPlayPlayerScreen(
    clientName: String,
    fps: Int,
    resolution: String,
    badgeText: String
) {
    var showOverlay by remember { mutableStateOf(true) }

    // Fade out overlay details after 6 seconds of streaming
    LaunchedEffect(Unit) {
        delay(6000)
        showOverlay = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Hardware accelerated video rendering surface
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                SurfaceView(context).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            VideoDecoderEngine.bindOutputSurface(holder.surface)
                        }

                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            VideoDecoderEngine.unbindSurface()
                        }
                    })
                }
            }
        )

        // Leanback HUD / On-Screen Display (Top-Right)
        AnimatedVisibility(
            visible = showOverlay,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(32.dp)
        ) {
            Box(
                modifier = Modifier
                    .background(AppleGlassOverlay, RoundedCornerShape(16.dp))
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(AppleAccentGreen, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = badgeText,
                            color = AppleTextPrimary,
                            fontSize = 16.sp
                        )
                    }
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(
                        text = clientName,
                        color = AppleTextSecondary,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "$resolution • ${if (fps > 0) fps else 60} FPS",
                        color = AppleTextSecondary.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}
