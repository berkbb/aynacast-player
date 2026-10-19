package com.babadogan.aynacast.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.babadogan.aynacast.tv.model.AppLanguage
import com.babadogan.aynacast.tv.ui.localization.AirPlayStrings
import com.babadogan.aynacast.tv.ui.theme.AppleAccentBlue
import com.babadogan.aynacast.tv.ui.theme.AppleAccentGreen
import com.babadogan.aynacast.tv.update.SELF_RELEASES_PAGE
import com.babadogan.aynacast.tv.update.SelfUpdater
import com.babadogan.aynacast.tv.update.UpdateCheck
import com.babadogan.aynacast.tv.update.UpdateInstall

private val WarningOrange = Color(0xFFFF9F0A)

/**
 * Result of "Check for Updates" in About: up to date, no public release, GitHub unreachable, or a
 * newer version that can download and install itself ("Update Now"), with progress, the TV's
 * confirmation step and any failure (a different signing key needs uninstalling first).
 */
@Composable
fun SelfUpdateWindow(check: UpdateCheck, install: UpdateInstall, language: AppLanguage, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val primaryFocus = remember { FocusRequester() }
    val canInstall = check is UpdateCheck.Available && check.apk != null
    val (title, message, color) = when (check) {
        is UpdateCheck.Checking -> Triple(AirPlayStrings.checkingUpdates(language), "", AppleAccentBlue)
        is UpdateCheck.UpToDate -> Triple(AirPlayStrings.updateUpToDateTitle(language), AirPlayStrings.updateUpToDate(check.current, language), AppleAccentGreen)
        is UpdateCheck.Available -> Triple(
            AirPlayStrings.updateAvailableTitle(language),
            if (check.apk != null) AirPlayStrings.updateAvailableInstall(check.latest, check.current, language)
            else AirPlayStrings.updateAvailableManual(check.latest, check.current, SELF_RELEASES_PAGE, language),
            AppleAccentBlue
        )
        is UpdateCheck.Unavailable -> Triple(AirPlayStrings.updateFailedTitle(language), AirPlayStrings.updateUnavailable(language), WarningOrange)
        is UpdateCheck.Failed -> Triple(AirPlayStrings.updateFailedTitle(language), AirPlayStrings.updateOffline(language), WarningOrange)
    }
    val installLine: Pair<String, Color>? = when (install) {
        is UpdateInstall.Downloading -> AirPlayStrings.updateDownloading(install.percent, language) to MaterialTheme.colorScheme.onSurface
        is UpdateInstall.WaitingForConfirmation -> AirPlayStrings.updateConfirm(language) to AppleAccentGreen
        is UpdateInstall.PermissionNeeded -> AirPlayStrings.updatePermission(language) to WarningOrange
        is UpdateInstall.DownloadFailed -> AirPlayStrings.updateDownloadFailed(language) to WarningOrange
        is UpdateInstall.SignatureConflict -> AirPlayStrings.updateSignatureConflict(language) to WarningOrange
        is UpdateInstall.InstallFailed -> AirPlayStrings.updateInstallFailed(install.reason, language) to WarningOrange
        is UpdateInstall.Idle -> null
    }
    val busy = install is UpdateInstall.Downloading || install is UpdateInstall.WaitingForConfirmation

    TvFullScreenWindow(title = AirPlayStrings.checkUpdates(language), language = language, onDismiss = onDismiss) {
        // The buttons appear only with the result, and the window may still be taking focus from
        // About underneath: ask once they exist, after a frame or two.
        LaunchedEffect(check is UpdateCheck.Checking, canInstall) {
            if (check is UpdateCheck.Checking) return@LaunchedEffect
            delay(150)
            runCatching { primaryFocus.requestFocus() }
        }
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(Modifier.width(64.dp).height(6.dp).background(color, RoundedCornerShape(3.dp)))
            Spacer(Modifier.height(24.dp))
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            if (message.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    message, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 18.sp, lineHeight = 26.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 820.dp)
                )
            }
            if (installLine != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    installLine.first, color = installLine.second, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 820.dp)
                )
            }
            Spacer(Modifier.height(32.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (canInstall) {
                    TvNavButton(
                        text = AirPlayStrings.updateNow(language),
                        isSelected = true,
                        modifier = Modifier.focusRequester(primaryFocus),
                        onClick = { if (!busy) SelfUpdater.installUpdate(context) }
                    )
                    TvNavButton(text = AirPlayStrings.updateLater(language), isSelected = false, onClick = onDismiss)
                } else if (check !is UpdateCheck.Checking) {
                    TvNavButton(
                        text = AirPlayStrings.close(language),
                        isSelected = true,
                        modifier = Modifier.focusRequester(primaryFocus),
                        onClick = onDismiss
                    )
                }
            }
        }
    }
}
