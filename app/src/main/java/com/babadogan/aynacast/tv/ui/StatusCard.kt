package com.babadogan.aynacast.tv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.babadogan.aynacast.tv.R
import com.babadogan.aynacast.tv.ui.theme.AppleAccentBlue
import com.babadogan.aynacast.tv.ui.theme.AppleSurfaceDark
import com.babadogan.aynacast.tv.ui.theme.AppleTextPrimary
import com.babadogan.aynacast.tv.ui.theme.AppleTextSecondary

private const val PULSE_MS = 1_800
private const val RING_COUNT = 3

/**
 * Full-screen card the receiver screens (mirroring, video casting, PIN) show while they start
 * ([active]: the icon breathes and sends out rings) and when they end (the rings settle and the
 * icon eases back). [symbol] replaces the AirPlay icon (e.g. a check mark), drawn in [accent].
 */
@Composable
fun StatusCard(
    title: String,
    subtitle: String,
    active: Boolean,
    symbol: String? = null,
    accent: Color = AppleAccentBlue
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(AppleSurfaceDark, Color.Black), radius = 1400f)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            PulsingIcon(active = active, symbol = symbol, accent = accent)
            Spacer(Modifier.height(40.dp))
            Text(text = title, color = AppleTextPrimary, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            if (subtitle.isNotBlank()) {
                Text(text = subtitle, color = AppleTextSecondary, fontSize = 20.sp)
            }
        }
    }
}

@Composable
private fun PulsingIcon(active: Boolean, symbol: String?, accent: Color) {
    val transition = rememberInfiniteTransition(label = "airplayPulse")
    val breath by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(PULSE_MS / 2, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath"
    )
    // Eases the rings away and the icon back to rest when casting ends.
    val activity = remember { Animatable(if (active) 1f else 0f) }
    LaunchedEffect(active) { activity.animateTo(if (active) 1f else 0f, tween(600)) }

    Box(modifier = Modifier.size(260.dp), contentAlignment = Alignment.Center) {
        repeat(RING_COUNT) { index ->
            val progress by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(PULSE_MS, easing = LinearEasing),
                    initialStartOffset = StartOffset(index * PULSE_MS / RING_COUNT)
                ),
                label = "ring$index"
            )
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .graphicsLayer {
                        val s = 1f + progress * 1.1f
                        scaleX = s
                        scaleY = s
                        alpha = (1f - progress) * 0.55f * activity.value
                    }
                    .border(2.dp, accent, CircleShape)
            )
        }
        Box(
            modifier = Modifier
                .size(120.dp)
                .scale(1f + (breath - 1f) * activity.value)
                .background(accent.copy(alpha = 0.14f), CircleShape)
                .border(1.dp, accent.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (symbol != null) {
                Text(text = symbol, color = accent, fontSize = 56.sp, fontWeight = FontWeight.Bold)
            } else {
                Image(
                    painter = painterResource(id = R.drawable.ic_airplay_logo),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp)
                )
            }
        }
    }
}
