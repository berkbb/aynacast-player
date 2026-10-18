package com.babadogan.aynacast.tv.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.launch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.babadogan.aynacast.tv.R
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.babadogan.aynacast.tv.model.AppLanguage
import com.babadogan.aynacast.tv.model.AppThemeMode
import com.babadogan.aynacast.tv.pairing.PinPairingManager
import com.babadogan.aynacast.tv.session.NowPlaying
import com.babadogan.aynacast.tv.session.VideoCastSession
import com.babadogan.aynacast.tv.service.AirPlayDaemonService
import com.babadogan.aynacast.tv.session.AirPlaySessionManager
import com.babadogan.aynacast.tv.session.ConnectionStatus
import com.babadogan.aynacast.tv.settings.AppSettingsRepository
import com.babadogan.aynacast.tv.ui.localization.AirPlayStrings
import com.babadogan.aynacast.tv.ui.theme.AirPlayTvTheme
import com.babadogan.aynacast.tv.ui.theme.AppleAccentBlue
import com.babadogan.aynacast.tv.ui.theme.AppleAccentGreen
import com.babadogan.aynacast.tv.ui.theme.AppleAccentOrange
import com.babadogan.aynacast.tv.ui.theme.AppleDarkBackground
import com.babadogan.aynacast.tv.ui.theme.FocusFill
import com.babadogan.aynacast.tv.util.DeviceIdentityHelper
import com.babadogan.aynacast.tv.util.requestNotificationPermissionOnce
import kotlinx.coroutines.flow.MutableStateFlow

class AirPlaySettingsActivity : ComponentActivity() {

    private val overlayPermissionGranted = MutableStateFlow(true)
    private val overlaySettingsUnavailable = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionOnce()

        val settingsRepo = AppSettingsRepository.getInstance(this)
        // Ensure the daemon service is running, unless the receiver was switched off.
        val serviceIntent = Intent(this, AirPlayDaemonService::class.java)
        if (settingsRepo.settings.value.receiverEnabled) {
            ContextCompat.startForegroundService(this, serviceIntent)
        }

        val deviceName = DeviceIdentityHelper.getAirPlayDeviceName(this)
        val appVersion = readAppVersion()
        PinPairingManager.init(this)

        setContent {
            val appSettings by settingsRepo.settings.collectAsState()
            val status by AirPlaySessionManager.connectionStatus.collectAsState()
            val metadata by AirPlaySessionManager.clientMetadata.collectAsState()
            val isServiceRunning by AirPlaySessionManager.isServiceRunning.collectAsState()
            val isVideoActive by AirPlaySessionManager.isVideoActive.collectAsState()
            val pairedDevices by PinPairingManager.pairedDeviceDetails.collectAsState()
            val isStreaming by AirPlaySessionManager.isStreaming.collectAsState()
            val nowPlaying by AirPlaySessionManager.nowPlaying.collectAsState()
            val isAudioActive by AirPlaySessionManager.isAudioActive.collectAsState()
            val castMedia by VideoCastSession.media.collectAsState()
            var showPairedDevices by remember { mutableStateOf(false) }
            var showSettings by rememberSaveable { mutableStateOf(false) }
            var showAbout by rememberSaveable { mutableStateOf(false) }
            val canDrawOverlays by overlayPermissionGranted.collectAsState()
            val overlayUnavailable by overlaySettingsUnavailable.collectAsState()
            // Asked once each time the app is opened while the permission is missing.
            var overlayPromptDone by rememberSaveable { mutableStateOf(false) }

            AirPlayTvTheme(themeMode = appSettings.themeMode) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AirPlaySettingsMainScreen(
                        nowPlaying = if (isStreaming && !isVideoActive && nowPlaying.hasContent) nowPlaying else null,
                        onShowNowPlaying = { startActivity(NowPlayingActivity.createIntent(this@AirPlaySettingsActivity)) },
                        receiverEnabled = appSettings.receiverEnabled,
                        isAudioActive = isAudioActive,
                        hasActiveStream = status == ConnectionStatus.STREAMING || castMedia != null,
                        onStopStreaming = { AirPlayDaemonService.stopStreaming(this@AirPlaySettingsActivity) },
                        onReceiverEnabledChanged = { enabled ->
                            settingsRepo.setReceiverEnabled(enabled)
                            // Stopping the service closes every AirPlay connection and withdraws the
                            // mDNS records, so Apple devices stop listing the TV.
                            if (enabled) {
                                ContextCompat.startForegroundService(this@AirPlaySettingsActivity, serviceIntent)
                            } else {
                                stopService(serviceIntent)
                            }
                        },
                        status = status,
                        isServiceRunning = isServiceRunning,
                        isVideoActive = isVideoActive,
                        deviceName = deviceName,
                        clientName = metadata.deviceName,
                        appVersion = appVersion,
                        currentLanguage = appSettings.language,
                        showOverlayPermission = !canDrawOverlays,
                        overlaySettingsUnavailable = overlayUnavailable,
                        onShowMirroring = { startActivity(AirPlayPlayerActivity.createIntent(this@AirPlaySettingsActivity)) },
                        onGrantOverlayPermission = {
                            overlaySettingsUnavailable.value = !openOverlayPermissionSettings()
                        },
                        onOpenSettings = { showSettings = true },
                        onOpenAbout = { showAbout = true }
                    )
                    if (showSettings) {
                        TvFullScreenWindow(
                            title = AirPlayStrings.tabSettings(appSettings.language),
                            language = appSettings.language,
                            bottomClose = true,
                            bottomActions = {
                                TvNavButton(
                                    text = AirPlayStrings.restartService(appSettings.language),
                                    isSelected = false,
                                    onClick = {
                                        stopService(serviceIntent)
                                        if (settingsRepo.settings.value.receiverEnabled) {
                                            ContextCompat.startForegroundService(this@AirPlaySettingsActivity, serviceIntent)
                                        }
                                    }
                                )
                            },
                            onDismiss = { showSettings = false }
                        ) {
                            SettingsContent(
                                modifier = Modifier.weight(1f),
                                currentLanguage = appSettings.language,
                                currentTheme = appSettings.themeMode,
                                keepAliveInBackground = appSettings.keepAliveInBackground,
                                discoveryRefresh = appSettings.discoveryRefresh,
                                onDiscoveryRefreshChanged = { settingsRepo.setDiscoveryRefresh(it) },
                                avSyncEnabled = appSettings.avSyncEnabled,
                                pinRequired = appSettings.pinRequired,
                                pairedDeviceCount = pairedDevices.size,
                                onLanguageChanged = { settingsRepo.setLanguage(it) },
                                onThemeChanged = { settingsRepo.setThemeMode(it) },
                                onAvSyncChanged = { settingsRepo.setAvSyncEnabled(it) },
                                onPinRequiredChanged = { settingsRepo.setPinRequired(it) },
                                onManagePairedDevices = { showPairedDevices = true },
                                onKeepAliveChanged = { enabled ->
                                    settingsRepo.setKeepAliveInBackground(enabled)
                                    if (enabled && settingsRepo.settings.value.receiverEnabled) {
                                        ContextCompat.startForegroundService(this@AirPlaySettingsActivity, serviceIntent)
                                    }
                                }
                            )
                        }
                    }
                    if (showAbout) {
                        TvFullScreenWindow(
                            title = AirPlayStrings.tabAbout(appSettings.language),
                            language = appSettings.language,
                            bottomClose = true,
                            onDismiss = { showAbout = false }
                        ) {
                            AboutContent(
                                modifier = Modifier.weight(1f),
                                currentLanguage = appSettings.language,
                                appVersion = appVersion
                            )
                        }
                    }
                    if (!canDrawOverlays && !overlayPromptDone && !showPairedDevices && !showSettings && !showAbout) {
                        OverlayPermissionDialog(
                            language = appSettings.language,
                            onGrant = {
                                overlayPromptDone = true
                                overlaySettingsUnavailable.value = !openOverlayPermissionSettings()
                            },
                            onLater = { overlayPromptDone = true }
                        )
                    }
                    if (showPairedDevices) {
                        PairedDevicesDialog(
                            devices = pairedDevices,
                            language = appSettings.language,
                            onRemove = { PinPairingManager.forgetDevice(it.key) },
                            onForgetAll = { PinPairingManager.forgetAllDevices() },
                            onDismiss = { showPairedDevices = false }
                        )
                    }
                    VolumeHud()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        overlayPermissionGranted.value = hasOverlayPermission()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Configuration changes also destroy the activity; only a real exit may stop the service.
        if (!isFinishing) return
        val settings = AppSettingsRepository.getInstance(this).settings.value
        if (!settings.keepAliveInBackground && !AirPlaySessionManager.isStreaming.value) {
            val serviceIntent = Intent(this, AirPlayDaemonService::class.java)
            stopService(serviceIntent)
        }
    }

    /** Android 10+ only lets background apps open activities when they may draw over other apps. */
    private fun hasOverlayPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(this)

    private fun openOverlayPermissionSettings(): Boolean {
        val packageUri = "package:$packageName".toUri()
        val candidates = listOf(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
        )
        for (intent in candidates) {
            try {
                startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
                // Many Android TV builds omit some settings screens; try the next one.
            }
        }
        return false
    }

    private fun readAppVersion(): String = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        info.versionName.orEmpty()
    } catch (_: PackageManager.NameNotFoundException) {
        ""
    }
}

@Composable
fun AirPlaySettingsMainScreen(
    nowPlaying: NowPlaying?,
    onShowNowPlaying: () -> Unit,
    receiverEnabled: Boolean,
    isAudioActive: Boolean,
    hasActiveStream: Boolean,
    onStopStreaming: () -> Unit,
    onReceiverEnabledChanged: (Boolean) -> Unit,
    status: ConnectionStatus,
    isServiceRunning: Boolean,
    isVideoActive: Boolean,
    deviceName: String,
    clientName: String,
    appVersion: String,
    currentLanguage: AppLanguage,
    showOverlayPermission: Boolean,
    overlaySettingsUnavailable: Boolean,
    onShowMirroring: () -> Unit,
    onGrantOverlayPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 48.dp, vertical = 36.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Header; Settings and About open in their own windows
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AccentBar()
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = AirPlayStrings.appName(currentLanguage),
                                color = MaterialTheme.colorScheme.onBackground,
                                fontSize = 30.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = AirPlayStrings.appSubtitle(currentLanguage),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 16.sp,
                            maxLines = 2
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                // Settings / About windows
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TvNavButton(
                        text = AirPlayStrings.tabSettings(currentLanguage),
                        isSelected = false,
                        onClick = onOpenSettings
                    )
                    TvNavButton(
                        text = AirPlayStrings.tabAbout(currentLanguage),
                        isSelected = false,
                        onClick = onOpenAbout
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            HomeContent(
                nowPlaying = nowPlaying,
                onShowNowPlaying = onShowNowPlaying,
                receiverEnabled = receiverEnabled,
                isAudioActive = isAudioActive,
                hasActiveStream = hasActiveStream,
                onStopStreaming = onStopStreaming,
                onReceiverEnabledChanged = onReceiverEnabledChanged,
                status = status,
                isServiceRunning = isServiceRunning,
                isVideoActive = isVideoActive,
                deviceName = deviceName,
                clientName = clientName,
                appVersion = appVersion,
                currentLanguage = currentLanguage,
                showOverlayPermission = showOverlayPermission,
                overlaySettingsUnavailable = overlaySettingsUnavailable,
                onShowMirroring = onShowMirroring,
                onGrantOverlayPermission = onGrantOverlayPermission
            )
        }
    }
}

/** Status, how-to and permission cards; the configuration lives in [SettingsContent]. */
@Composable
fun HomeContent(
    nowPlaying: NowPlaying?,
    onShowNowPlaying: () -> Unit,
    receiverEnabled: Boolean,
    isAudioActive: Boolean,
    hasActiveStream: Boolean,
    onStopStreaming: () -> Unit,
    onReceiverEnabledChanged: (Boolean) -> Unit,
    status: ConnectionStatus,
    isServiceRunning: Boolean,
    isVideoActive: Boolean,
    deviceName: String,
    clientName: String,
    appVersion: String,
    currentLanguage: AppLanguage,
    showOverlayPermission: Boolean,
    overlaySettingsUnavailable: Boolean,
    onShowMirroring: () -> Unit,
    onGrantOverlayPermission: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Info cards, as on the Pitwall Updater home screen
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            InfoCard(
                title = AirPlayStrings.cardReceiverName(currentLanguage),
                value = deviceName,
                modifier = Modifier.weight(1f)
            )
            InfoCard(
                title = AirPlayStrings.cardVersion(currentLanguage),
                value = "v$appVersion",
                modifier = Modifier.weight(1f)
            )
            val statusColor = when {
                !receiverEnabled -> AppleAccentOrange
                isServiceRunning -> AppleAccentGreen
                else -> AppleAccentOrange
            }
            InfoCard(
                title = AirPlayStrings.cardStatus(currentLanguage),
                value = when {
                    !receiverEnabled -> AirPlayStrings.receiverOffStatus(currentLanguage)
                    isServiceRunning -> AirPlayStrings.serviceStatusActive(currentLanguage)
                    else -> AirPlayStrings.serviceStatusStopped(currentLanguage)
                },
                valueColor = statusColor,
                dotColor = statusColor,
                modifier = Modifier.weight(1.2f)
            )
        }

        // Connection & How-To Cards Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Connection Card
            // Holds the AirPlay on/off button, so the card itself must not take focus.
            TvCard(modifier = Modifier.weight(1f), focusable = false) {
                CardLabel(AirPlayStrings.cardConnection(currentLanguage))
                Text(
                    text = when {
                        !receiverEnabled -> AirPlayStrings.receiverOffDesc(currentLanguage)
                        !isServiceRunning -> AirPlayStrings.serviceStatusStarting(currentLanguage)
                        status == ConnectionStatus.IDLE -> AirPlayStrings.statusIdle(deviceName, currentLanguage)
                        status == ConnectionStatus.WAITING_FOR_STREAM -> AirPlayStrings.statusConnecting(clientName, currentLanguage)
                        status == ConnectionStatus.STREAMING -> when {
                            isVideoActive -> AirPlayStrings.statusStreaming(clientName, currentLanguage)
                            isAudioActive -> AirPlayStrings.statusPlayingAudio(clientName, currentLanguage)
                            // iOS keeps the connection a while after the music or mirroring stopped.
                            else -> AirPlayStrings.statusConnectedIdle(clientName, currentLanguage)
                        }
                        else -> AirPlayStrings.statusDisconnected(currentLanguage)
                    },
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
                if (isVideoActive) {
                    Spacer(modifier = Modifier.height(6.dp))
                    TvChoiceChip(
                        text = AirPlayStrings.showMirroring(currentLanguage),
                        isSelected = true,
                        onClick = onShowMirroring
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Ends what is playing now; the TV stays available for the next connection.
                    if (hasActiveStream && receiverEnabled) {
                        TvChoiceChip(
                            text = AirPlayStrings.stopStreaming(currentLanguage),
                            isSelected = false,
                            onClick = onStopStreaming
                        )
                    }
                    // Kill switch: the receiver goes away until it is started again.
                    TvChoiceChip(
                        text = if (receiverEnabled) AirPlayStrings.receiverStop(currentLanguage) else AirPlayStrings.receiverStart(currentLanguage),
                        isSelected = !receiverEnabled,
                        onClick = { onReceiverEnabledChanged(!receiverEnabled) }
                    )
                }
                // Music keeps playing after Back closes the Now Playing screen; this brings it back.
                if (nowPlaying != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = AirPlayStrings.nowPlayingInApp(nowPlaying.title, nowPlaying.artist, currentLanguage),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    TvChoiceChip(
                        text = AirPlayStrings.showNowPlaying(currentLanguage),
                        isSelected = true,
                        onClick = onShowNowPlaying
                    )
                }
            }

            // Instructions Card: text only, so the D-pad skips it
            TvCard(modifier = Modifier.weight(1f), focusable = false) {
                CardLabel(AirPlayStrings.howToConnectTitle(currentLanguage))
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = AirPlayStrings.howToConnectInstructions(deviceName, currentLanguage),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            }
        }

        // Android 10+ background-launch permission
        if (showOverlayPermission) {
            TvCard(
                modifier = Modifier.fillMaxWidth(),
                borderColor = AppleAccentOrange.copy(alpha = 0.6f),
                focusable = false
            ) {
                CardLabel(AirPlayStrings.overlayPermissionTitle(currentLanguage))
                Text(
                    text = if (overlaySettingsUnavailable) {
                        AirPlayStrings.overlayPermissionUnavailable(currentLanguage)
                    } else {
                        AirPlayStrings.overlayPermissionDesc(currentLanguage)
                    },
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
                TvChoiceChip(
                    text = AirPlayStrings.overlayPermissionButton(currentLanguage),
                    isSelected = false,
                    onClick = onGrantOverlayPermission
                )
            }
        }
    }
}

@Composable
fun SettingsContent(
    modifier: Modifier = Modifier,
    currentLanguage: AppLanguage,
    currentTheme: AppThemeMode,
    keepAliveInBackground: Boolean,
    discoveryRefresh: Boolean,
    onDiscoveryRefreshChanged: (Boolean) -> Unit,
    avSyncEnabled: Boolean,
    pinRequired: Boolean,
    pairedDeviceCount: Int,
    onLanguageChanged: (AppLanguage) -> Unit,
    onThemeChanged: (AppThemeMode) -> Unit,
    onAvSyncChanged: (Boolean) -> Unit,
    onPinRequiredChanged: (Boolean) -> Unit,
    onManagePairedDevices: () -> Unit,
    onKeepAliveChanged: (Boolean) -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Appearance & Language Configuration Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Theme Mode Card
            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    CardLabel(AirPlayStrings.settingsSectionAppearance(currentLanguage))
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AppThemeMode.entries.forEach { theme ->
                            TvChoiceChip(
                                text = AirPlayStrings.themeName(theme, currentLanguage),
                                isSelected = currentTheme == theme,
                                onClick = { onThemeChanged(theme) }
                            )
                        }
                    }
                }
            }

            // Language Card
            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    CardLabel(AirPlayStrings.settingsSectionLanguage(currentLanguage))
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AppLanguage.entries.forEach { lang ->
                            TvChoiceChip(
                                text = AirPlayStrings.languageName(lang, currentLanguage),
                                isSelected = currentLanguage == lang,
                                onClick = { onLanguageChanged(lang) }
                            )
                        }
                    }
                }
            }
        }

        // On-screen PIN pairing (UxPlay -pin)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                CardLabel(AirPlayStrings.pinSettingTitle(currentLanguage))
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = AirPlayStrings.pinSettingDesc(currentLanguage),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
                Spacer(modifier = Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvChoiceChip(
                        text = AirPlayStrings.pinOff(currentLanguage),
                        isSelected = !pinRequired,
                        onClick = { onPinRequiredChanged(false) }
                    )
                    TvChoiceChip(
                        text = AirPlayStrings.pinOn(currentLanguage),
                        isSelected = pinRequired,
                        onClick = { onPinRequiredChanged(true) }
                    )
                    if (pairedDeviceCount > 0) {
                        TvChoiceChip(
                            text = AirPlayStrings.pinPairedDevices(pairedDeviceCount, currentLanguage),
                            isSelected = false,
                            onClick = onManagePairedDevices
                        )
                    }
                }
            }
        }

        // Audio/video timestamp sync (UxPlay -vsync)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                CardLabel(AirPlayStrings.avSyncTitle(currentLanguage))
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = AirPlayStrings.avSyncDesc(currentLanguage),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
                Spacer(modifier = Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvChoiceChip(
                        text = AirPlayStrings.avSyncOff(currentLanguage),
                        isSelected = !avSyncEnabled,
                        onClick = { onAvSyncChanged(false) }
                    )
                    TvChoiceChip(
                        text = AirPlayStrings.avSyncOn(currentLanguage),
                        isSelected = avSyncEnabled,
                        onClick = { onAvSyncChanged(true) }
                    )
                }
            }
        }

        // 24/7 Background Service Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                CardLabel(AirPlayStrings.settingKeepAliveTitle(currentLanguage))
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = AirPlayStrings.settingKeepAliveDesc(currentLanguage),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
                Spacer(modifier = Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvChoiceChip(
                        text = AirPlayStrings.keepAliveEnabled(currentLanguage),
                        isSelected = keepAliveInBackground,
                        onClick = { onKeepAliveChanged(true) }
                    )
                    TvChoiceChip(
                        text = AirPlayStrings.keepAliveDisabled(currentLanguage),
                        isSelected = !keepAliveInBackground,
                        onClick = { onKeepAliveChanged(false) }
                    )
                }
            }
        }

        // mDNS re-announcement for TVs that do not receive multicast queries
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                CardLabel(AirPlayStrings.discoveryRefreshTitle(currentLanguage))
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = AirPlayStrings.discoveryRefreshDesc(currentLanguage),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
                Spacer(modifier = Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvChoiceChip(
                        text = AirPlayStrings.discoveryRefreshOn(currentLanguage),
                        isSelected = discoveryRefresh,
                        onClick = { onDiscoveryRefreshChanged(true) }
                    )
                    TvChoiceChip(
                        text = AirPlayStrings.discoveryRefreshOff(currentLanguage),
                        isSelected = !discoveryRefresh,
                        onClick = { onDiscoveryRefreshChanged(false) }
                    )
                }
            }
        }

        // Device & Network Info Card
        val context = androidx.compose.ui.platform.LocalContext.current
        val macAddress = remember { DeviceIdentityHelper.getDeviceMacAddress(context) }
        TvCard(modifier = Modifier.fillMaxWidth()) {
            CardLabel(AirPlayStrings.deviceInfoTitle(currentLanguage))
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = AirPlayStrings.deviceInfoPort(currentLanguage),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp
            )
            Text(
                text = AirPlayStrings.deviceInfoResolution(currentLanguage),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp
            )
            Text(
                text = AirPlayStrings.deviceInfoMac(macAddress, currentLanguage),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
fun AboutContent(
    modifier: Modifier = Modifier,
    currentLanguage: AppLanguage,
    appVersion: String
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Legal Protection & Disclaimer Card (Highlighted)
        TvCard(
            borderColor = AppleAccentBlue.copy(alpha = 0.5f),
            backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(AppleAccentBlue, CircleShape)
                )
                Spacer(modifier = Modifier.width(10.dp))
                CardLabel(AirPlayStrings.aboutDisclaimerTitle(currentLanguage))
            }
            Text(
                text = AirPlayStrings.aboutDisclaimerDesc(currentLanguage),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                lineHeight = 20.sp
            )
        }

        // App & Version Card
        TvCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "AynaCast AirPlay TV Receiver",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = AirPlayStrings.appSubtitle(currentLanguage),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                }
                Text(
                    text = AirPlayStrings.aboutVersion(appVersion, currentLanguage),
                    color = AppleAccentBlue,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Architecture Card
        TvCard {
            CardLabel(AirPlayStrings.aboutArchitectureTitle(currentLanguage))
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = AirPlayStrings.aboutArchitectureDesc(currentLanguage),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        }

        // Protocols Card
        TvCard {
            CardLabel(AirPlayStrings.aboutProtocolsTitle(currentLanguage))
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = AirPlayStrings.aboutProtocolsDesc(currentLanguage),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        }

        // Developer Card
        TvCard {
            CardLabel(AirPlayStrings.aboutDeveloperTitle(currentLanguage))
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = AirPlayStrings.aboutDeveloperDesc(currentLanguage),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // License Card
        var showLicenseViewer by rememberSaveable { mutableStateOf(false) }

        if (showLicenseViewer) {
            LicenseViewerDialog(
                language = currentLanguage,
                onDismiss = { showLicenseViewer = false }
            )
        }

        TvCard(
            modifier = Modifier.dpadScroll(scrollState),
            onClick = { showLicenseViewer = true }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    CardLabel(AirPlayStrings.aboutLicenseTitle(currentLanguage))
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = AirPlayStrings.aboutLicenseDesc(currentLanguage),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Box(
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = AirPlayStrings.viewLicense(currentLanguage),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Bottom spacer for comfortable TV viewing and scrolling clearance
        Spacer(modifier = Modifier.height(48.dp))
    }
}

/**
 * For the last focusable card on a scrolling screen: with nothing below it to take focus, Down
 * would not move the page and Up would jump focus (and the page) back to the header. While the
 * card has focus, Down scrolls the page in steps until the end and Up scrolls back to where the
 * card got focus before it lets focus leave.
 */
@Composable
fun Modifier.dpadScroll(scrollState: ScrollState, step: Float = 240f): Modifier {
    val scope = rememberCoroutineScope()
    var focusScroll by remember { mutableIntStateOf(0) }
    return this
        .onFocusChanged { if (it.hasFocus) focusScroll = scrollState.value }
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionDown -> {
                    // At the bottom, let focus move on (e.g. to the Close button below the page).
                    if (scrollState.canScrollForward) {
                        scope.launch { scrollState.animateScrollBy(step) }
                        true
                    } else {
                        false
                    }
                }
                Key.DirectionUp -> {
                    val back = (scrollState.value - focusScroll).toFloat()
                    if (back > 0f) {
                        scope.launch { scrollState.animateScrollBy(-minOf(step, back)) }
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
}

@Composable
fun TvCard(
    modifier: Modifier = Modifier,
    borderColor: Color? = null,
    backgroundColor: Color? = null,
    // A card holding its own controls must not take focus itself: D-pad focus search only moves
    // between siblings, so the controls inside a focusable card are unreachable with the arrows.
    focusable: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.hasFocus }
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else if (focusable) {
                    Modifier.focusable()
                } else {
                    Modifier
                }
            ),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor ?: MaterialTheme.colorScheme.surface
        ),
        border = when {
            isFocused -> BorderStroke(2.dp, AppleAccentBlue)
            borderColor != null -> BorderStroke(1.dp, borderColor)
            else -> BorderStroke(1.dp, Color.Transparent)
        },
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

/** Small spaced grey caption that opens a card, as on the Pitwall Updater info cards. */
@Composable
fun CardLabel(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp
    )
}

/** Vertical accent bar shown before screen and window titles. */
@Composable
fun AccentBar(height: Dp = 32.dp) {
    Box(
        modifier = Modifier
            .width(6.dp)
            .height(height)
            .background(AppleAccentBlue, RoundedCornerShape(3.dp))
    )
}

/** Pitwall-style info card: a caption on top and a bold value below it. */
@Composable
fun InfoCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    dotColor: Color? = null
) {
    Card(
        modifier = modifier.height(104.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            CardLabel(title)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (dotColor != null) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(dotColor, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                }
                Text(
                    text = value,
                    color = valueColor,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Focus fills the button with a light colour and turns the text dark, like the Pitwall Updater
 * buttons; [isSelected] marks the active choice with the accent colour.
 */
@Composable
private fun PitwallButton(
    text: String,
    isSelected: Boolean,
    height: Dp,
    fontSize: TextUnit,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = when {
                isFocused -> FocusFill
                isSelected -> AppleAccentBlue
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = when {
                isFocused -> AppleDarkBackground
                isSelected -> Color.White
                else -> MaterialTheme.colorScheme.onSurface
            }
        ),
        border = if (isFocused) BorderStroke(3.dp, FocusFill) else null,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .height(height)
            .onFocusChanged { isFocused = it.isFocused }
    ) {
        Text(
            text = text,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
    }
}

@Composable
fun TvNavButton(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit
) = PitwallButton(text = text, isSelected = isSelected, height = 52.dp, fontSize = 16.sp, onClick = onClick)

@Composable
fun TvChoiceChip(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit
) = PitwallButton(text = text, isSelected = isSelected, height = 44.dp, fontSize = 14.sp, onClick = onClick)
