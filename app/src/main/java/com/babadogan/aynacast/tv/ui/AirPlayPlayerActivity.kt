package com.babadogan.aynacast.tv.ui

import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
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
import com.babadogan.aynacast.tv.engine.GlVideoRenderer
import com.babadogan.aynacast.tv.engine.VideoDecoderEngine
import com.babadogan.aynacast.tv.session.AirPlaySessionManager
import com.babadogan.aynacast.tv.session.ScreenTransitions
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.babadogan.aynacast.tv.ui.theme.AirPlayTvTheme
import com.babadogan.aynacast.tv.ui.theme.AppleAccentGreen
import com.babadogan.aynacast.tv.ui.theme.AppleGlassOverlay
import com.babadogan.aynacast.tv.ui.theme.AppleTextPrimary
import com.babadogan.aynacast.tv.ui.theme.AppleTextSecondary
import kotlinx.coroutines.delay

import com.babadogan.aynacast.tv.settings.AppSettingsRepository
import com.babadogan.aynacast.tv.ui.localization.AirPlayStrings
import androidx.activity.addCallback
import android.content.Context
import android.content.Intent
import com.babadogan.aynacast.tv.service.AirPlayDaemonService

class AirPlayPlayerActivity : ComponentActivity() {

    // The video surface is a separate layer that ignores the window's close animation, so it is
    // removed first: otherwise it lingers as a black (often phone-shaped) rectangle.
    private val leaving = mutableStateOf(false)
    private val ended = mutableStateOf(false)

    companion object {
        private const val REVEAL_MS = 450
        private const val OUTRO_FADE_MS = 350
        private const val OUTRO_MS = 1_300L

        fun createIntent(context: Context): Intent =
            Intent(context, AirPlayPlayerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Root Back button on TV remote exits player cleanly
        onBackPressedDispatcher.addCallback(this) {
            leave()
        }

        // Keep TV screen awake during AirPlay streaming
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val settingsRepo = AppSettingsRepository.getInstance(this)

        setContent {
            val appSettings by settingsRepo.settings.collectAsState()
            val isVideoActive by AirPlaySessionManager.isVideoActive.collectAsState()
            val metadata by AirPlaySessionManager.clientMetadata.collectAsState()
            val currentFps by AirPlaySessionManager.currentFps.collectAsState()
            val hasShownFrame by VideoDecoderEngine.hasShownFrame.collectAsState()
            val language = appSettings.language

            AirPlayTvTheme(themeMode = appSettings.themeMode) {
                // The sender stopped mirroring (TEARDOWN or dropped connection): say so, then close.
                LaunchedEffect(isVideoActive) {
                    if (!isVideoActive) showEndedAndClose()
                }

                Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    if (!leaving.value && !ended.value) {
                        AirPlayPlayerScreen(
                            clientName = metadata.deviceName,
                            fps = currentFps,
                            resolution = "${metadata.resolutionWidth}x${metadata.resolutionHeight}",
                            resolutionWidth = metadata.resolutionWidth,
                            resolutionHeight = metadata.resolutionHeight,
                            decoderLabel = when {
                                metadata.decoderName.isEmpty() -> ""
                                metadata.isSoftwareDecoder -> "SW"
                                else -> "HW"
                            },
                            badgeText = AirPlayStrings.airplayActiveBadge(language)
                        )
                    }
                    AnimatedVisibility(
                        visible = !hasShownFrame && !ended.value && !leaving.value,
                        enter = fadeIn(tween(REVEAL_MS)),
                        exit = fadeOut(tween(REVEAL_MS)) + scaleOut(tween(REVEAL_MS), targetScale = 1.08f)
                    ) {
                        StatusCard(
                            title = AirPlayStrings.mirrorStartingTitle(language),
                            subtitle = AirPlayStrings.mirrorStartingSubtitle(metadata.deviceName, language),
                            active = true
                        )
                    }
                    AnimatedVisibility(visible = ended.value, enter = fadeIn(tween(OUTRO_FADE_MS))) {
                        StatusCard(
                            title = AirPlayStrings.mirrorEndedTitle(language),
                            subtitle = metadata.deviceName,
                            active = false
                        )
                    }
                    VolumeHud()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AirPlayDaemonService.cancelStreamNotification(this)
        // Mirroring may have ended while this activity was being (re)created.
        if (!AirPlaySessionManager.isVideoActive.value && !ended.value) finish()
    }

    /** Back: mirroring continues in the background; the screen just goes away. */
    private fun leave() {
        if (leaving.value || ended.value) return
        leaving.value = true
        // finish() once the surface is gone, i.e. after the next frame without it.
        window.decorView.post { finish() }
    }

    private fun showEndedAndClose() {
        if (ended.value) return
        ended.value = true
        ScreenTransitions.beginOutro(OUTRO_MS)
        lifecycleScope.launch {
            delay(OUTRO_MS)
            finish()
        }
    }
}

@Composable
fun AirPlayPlayerScreen(
    clientName: String,
    fps: Int,
    resolution: String,
    resolutionWidth: Int = 1920,
    resolutionHeight: Int = 1080,
    // "HW" / "SW" decoder; a software decoder on a TV usually means a decoder quirk worth reporting.
    decoderLabel: String = "",
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
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        // Full screen; GlVideoRenderer letterboxes the video to its aspect ratio inside it.
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context -> MirrorSurfaceView(context) },
            update = { view -> view.setVideoSize(resolutionWidth, resolutionHeight) }
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
                        text = "$resolution • ${if (fps > 0) fps else 60} FPS" + if (decoderLabel.isNotEmpty()) " • $decoderLabel" else "",
                        color = AppleTextSecondary.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

/**
 * Full-screen surface for the mirrored video. The decoder renders through [GlVideoRenderer], so
 * the TV receives finished 16:9 frames; if OpenGL cannot be set up, it renders here directly
 * (the TV's video plane then places and scales the video itself).
 */
private class MirrorSurfaceView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    private var renderer: GlVideoRenderer? = null
    private var decoderSurface: android.view.Surface? = null
    private var videoWidth = 0
    private var videoHeight = 0

    init {
        // Default Z order (behind the window), so the status cards can cover it.
        holder.addCallback(this)
    }

    fun setVideoSize(width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
        renderer?.setVideoSize(width, height)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val gl = try {
            GlVideoRenderer(holder.surface).also { it.setVideoSize(videoWidth, videoHeight) }
        } catch (e: Exception) {
            android.util.Log.w("MirrorSurfaceView", "OpenGL rendering unavailable; decoding to the screen directly", e)
            null
        }
        renderer = gl
        val target = gl?.inputSurface ?: holder.surface
        decoderSurface = target
        VideoDecoderEngine.bindOutputSurface(target)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        renderer?.setOutputSize(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // Blocks until the decoder stops rendering into its surface.
        decoderSurface?.let { VideoDecoderEngine.unbindSurface(it) }
        decoderSurface = null
        renderer?.release()
        renderer = null
    }
}
