package com.babadogan.aynacast.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.babadogan.aynacast.tv.model.AppLanguage
import com.babadogan.aynacast.tv.pairing.PinPairingManager
import com.babadogan.aynacast.tv.ui.localization.AirPlayStrings
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

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(720.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp))
                .padding(32.dp)
        ) {
            Text(
                text = AirPlayStrings.pinPairedDevices(devices.size, language),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.padding(top = 6.dp))
            Text(
                text = AirPlayStrings.pairedDevicesDesc(language),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp
            )
            Spacer(Modifier.padding(top = 20.dp))
            LazyColumn(
                modifier = Modifier.heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(devices, key = { it.key.contentHashCode() }) { device ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = device.name ?: AirPlayStrings.pairedDeviceUnknown(language),
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Medium
                            )
                            if (device.pairedAtMs > 0L) {
                                Text(
                                    text = AirPlayStrings.pairedDeviceSince(dateFormat.format(Date(device.pairedAtMs)), language),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 13.sp
                                )
                            }
                        }
                        TvChoiceChip(
                            text = AirPlayStrings.pairedDeviceRemove(language),
                            isSelected = false,
                            onClick = { onRemove(device) }
                        )
                    }
                }
            }
            Spacer(Modifier.padding(top = 24.dp))
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
}
