package com.babadogan.aynacast.tv.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.babadogan.aynacast.tv.R
import com.babadogan.aynacast.tv.model.AppLanguage
import com.babadogan.aynacast.tv.pairing.PinPairingManager
import com.babadogan.aynacast.tv.ui.localization.AirPlayStrings
import com.babadogan.aynacast.tv.ui.theme.AppleAccentBlue
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Devices that paired with the on-screen PIN, each removable on its own. */
@Composable
fun PairedDevicesDialog(
    devices: List<PinPairingManager.PairedDevice>,
    language: AppLanguage,
    onRemove: (PinPairingManager.PairedDevice) -> Unit,
    onForgetAll: () -> Unit,
    onDismiss: () -> Unit
) {
    // Nothing left to manage after the last removal.
    LaunchedEffect(devices.isEmpty()) {
        if (devices.isEmpty()) onDismiss()
    }
    val locale = when (language) {
        AppLanguage.TR -> Locale("tr", "TR")
        AppLanguage.EN -> Locale.ENGLISH
        AppLanguage.RO -> Locale("ro", "RO")
    }
    val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)

    TvFullScreenWindow(
        title = AirPlayStrings.pinPairedDevices(devices.size, language),
        subtitle = AirPlayStrings.pairedDevicesDesc(language),
        language = language,
        onDismiss = onDismiss
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 200.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(devices, key = { it.key.contentHashCode() }) { device ->
                PairedDeviceCard(
                    device = device,
                    since = device.pairedAtMs.takeIf { it > 0L }?.let { dateFormat.format(Date(it)) },
                    language = language,
                    onRemove = { onRemove(device) }
                )
            }
        }
        Spacer(Modifier.padding(top = 20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvChoiceChip(
                text = AirPlayStrings.pairedDevicesForgetAll(language),
                isSelected = false,
                onClick = onForgetAll
            )
            TvChoiceChip(
                text = AirPlayStrings.close(language),
                isSelected = true,
                onClick = onDismiss
            )
        }
    }
}

/** Hero-style tile: device icon, name and pairing date; it lights up while its remove button has focus. */
@Composable
private fun PairedDeviceCard(
    device: PinPairingManager.PairedDevice,
    since: String?,
    language: AppLanguage,
    onRemove: () -> Unit
) {
    var hasFocus by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .scale(if (hasFocus) 1.04f else 1f)
            .onFocusChanged { hasFocus = it.hasFocus }
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp))
            .border(
                if (hasFocus) BorderStroke(2.dp, AppleAccentBlue) else BorderStroke(0.dp, Color.Transparent),
                RoundedCornerShape(20.dp)
            )
            .padding(horizontal = 16.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(AppleAccentBlue.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(deviceIconFor(device)),
                contentDescription = null,
                tint = AppleAccentBlue,
                modifier = Modifier.size(38.dp)
            )
        }
        Spacer(Modifier.padding(top = 14.dp))
        Text(
            text = device.name ?: AirPlayStrings.pairedDeviceUnknown(language),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (since != null) {
            Text(
                text = AirPlayStrings.pairedDeviceSince(since, language),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.padding(top = 14.dp))
        TvChoiceChip(
            text = AirPlayStrings.pairedDeviceRemove(language),
            isSelected = false,
            onClick = onRemove
        )
    }
}

/**
 * Classic AirPlay senders are the Mac Music app, shown with a music note. Otherwise the hardware
 * model the device reported (e.g. "iPhone15,2") decides the icon; records without one fall back to the name.
 */
@DrawableRes
private fun deviceIconFor(device: PinPairingManager.PairedDevice): Int = when {
    device.isClassicSender -> R.drawable.ic_device_music
    else -> device.model?.let(::iconForText) ?: device.name?.let(::iconForText) ?: R.drawable.ic_device_generic
}

@DrawableRes
private fun iconForText(text: String): Int? {
    val t = text.lowercase(Locale.ROOT)
    return when {
        "ipad" in t -> R.drawable.ic_device_tablet
        "iphone" in t || "ipod" in t -> R.drawable.ic_device_phone
        "mac" in t -> R.drawable.ic_device_laptop
        else -> null
    }
}
