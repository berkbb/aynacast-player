package com.babadogan.aynacast.tv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.babadogan.aynacast.tv.session.AirPlaySessionManager
import com.babadogan.aynacast.tv.ui.theme.AppleAccentBlue
import com.babadogan.aynacast.tv.ui.theme.AppleGlassOverlay
import com.babadogan.aynacast.tv.ui.theme.AppleTextPrimary
import kotlinx.coroutines.delay

private const val VISIBLE_MS = 2_000L

/**
 * Apple TV-like volume indicator: slides in at the top when the sender changes the volume and
 * hides after a moment. Place it last in a full-screen Box so it draws over the content.
 */
@Composable
fun VolumeHud() {
    val volume by AirPlaySessionManager.senderVolume.collectAsState()
    // Changes from before this screen opened are not news.
    val openedAtChange = remember { AirPlaySessionManager.senderVolume.value.changeId }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(volume.changeId) {
        if (volume.changeId == openedAtChange) return@LaunchedEffect
        visible = true
        delay(VISIBLE_MS)
        visible = false
    }
    val level by animateFloatAsState(volume.level, tween(180), label = "volumeLevel")

    Box(modifier = Modifier.fillMaxSize().padding(top = 40.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = visible,
            // Appears at once (it answers a button press); only the exit takes its time.
            enter = fadeIn(tween(90)) + slideInVertically(tween(120)) { -it / 4 },
            exit = fadeOut(tween(300)) + slideOutVertically(tween(300)) { -it }
        ) {
            Row(
                modifier = Modifier
                    .background(AppleGlassOverlay, RoundedCornerShape(28.dp))
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Canvas(modifier = Modifier.size(30.dp)) { drawSpeaker(level) }
                Spacer(Modifier.width(16.dp))
                Box(
                    modifier = Modifier
                        .width(260.dp)
                        .height(8.dp)
                        .background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(4.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(level.coerceIn(0f, 1f))
                            .background(AppleAccentBlue, RoundedCornerShape(4.dp))
                    )
                }
                Spacer(Modifier.width(16.dp))
                Text(
                    text = "${(volume.level * 100).toInt()}",
                    color = AppleTextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.width(40.dp)
                )
            }
        }
    }
}

/** Speaker with up to three sound waves for [level]; a cross when muted. */
private fun DrawScope.drawSpeaker(level: Float) {
    val w = size.width
    val h = size.height
    val body = Path().apply {
        moveTo(w * 0.08f, h * 0.36f)
        lineTo(w * 0.26f, h * 0.36f)
        lineTo(w * 0.48f, h * 0.16f)
        lineTo(w * 0.48f, h * 0.84f)
        lineTo(w * 0.26f, h * 0.64f)
        lineTo(w * 0.08f, h * 0.64f)
        close()
    }
    drawPath(body, AppleTextPrimary)
    val stroke = Stroke(width = w * 0.07f, cap = StrokeCap.Round)
    if (level <= 0f) {
        drawLine(AppleTextPrimary, Offset(w * 0.62f, h * 0.36f), Offset(w * 0.90f, h * 0.64f), stroke.width, StrokeCap.Round)
        drawLine(AppleTextPrimary, Offset(w * 0.90f, h * 0.36f), Offset(w * 0.62f, h * 0.64f), stroke.width, StrokeCap.Round)
        return
    }
    val waves = when {
        level < 0.34f -> 1
        level < 0.67f -> 2
        else -> 3
    }
    for (i in 1..waves) {
        val r = w * (0.12f + 0.13f * i)
        drawArc(
            color = AppleTextPrimary,
            startAngle = -45f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(w * 0.40f - r, h * 0.5f - r),
            size = Size(r * 2, r * 2),
            style = stroke
        )
    }
}
