package com.babadogan.aynacast.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.babadogan.aynacast.tv.model.AppLanguage
import com.babadogan.aynacast.tv.ui.localization.AirPlayStrings

/**
 * Asks for "Display over other apps" when the app opens without it (Android 10+). Without it the
 * mirroring, PIN and video screens cannot open over another app, and Android TV does not show the
 * fallback notification on screen.
 */
@Composable
fun OverlayPermissionDialog(
    language: AppLanguage,
    onGrant: () -> Unit,
    onLater: () -> Unit
) {
    Dialog(onDismissRequest = onLater) {
        Column(
            modifier = Modifier
                .width(720.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp))
                .padding(32.dp)
        ) {
            Text(
                text = AirPlayStrings.overlayPermissionTitle(language),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.padding(top = 10.dp))
            Text(
                text = AirPlayStrings.overlayPermissionDesc(language),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 15.sp,
                lineHeight = 22.sp
            )
            Spacer(Modifier.padding(top = 24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvChoiceChip(
                    text = AirPlayStrings.overlayPermissionButton(language),
                    isSelected = true,
                    onClick = onGrant
                )
                TvChoiceChip(
                    text = AirPlayStrings.overlayPermissionLater(language),
                    isSelected = false,
                    onClick = onLater
                )
            }
        }
    }
}
