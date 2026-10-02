package com.babadogan.aynacast.tv.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.babadogan.aynacast.tv.R
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.babadogan.aynacast.tv.model.AppLanguage
import com.babadogan.aynacast.tv.model.AppThemeMode
import com.babadogan.aynacast.tv.service.AirPlayDaemonService
import com.babadogan.aynacast.tv.session.AirPlaySessionManager
import com.babadogan.aynacast.tv.session.ConnectionStatus
import com.babadogan.aynacast.tv.settings.AppSettingsRepository
import com.babadogan.aynacast.tv.ui.localization.AirPlayStrings
import com.babadogan.aynacast.tv.ui.theme.AirPlayTvTheme
import com.babadogan.aynacast.tv.ui.theme.AppleAccentBlue
import com.babadogan.aynacast.tv.ui.theme.AppleAccentGreen
import com.babadogan.aynacast.tv.util.DeviceIdentityHelper
import com.babadogan.aynacast.tv.util.requestNotificationPermissionOnce

class AirPlaySettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionOnce()

        // Ensure daemon service is running
        val serviceIntent = Intent(this, AirPlayDaemonService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)

        val settingsRepo = AppSettingsRepository.getInstance(this)
        val deviceName = DeviceIdentityHelper.getAirPlayDeviceName(this)

        setContent {
            val appSettings by settingsRepo.settings.collectAsState()
            val status by AirPlaySessionManager.connectionStatus.collectAsState()
            val metadata by AirPlaySessionManager.clientMetadata.collectAsState()

            AirPlayTvTheme(themeMode = appSettings.themeMode) {
                AirPlaySettingsMainScreen(
                    status = status,
                    deviceName = deviceName,
                    clientName = metadata.deviceName,
                    currentLanguage = appSettings.language,
                    currentTheme = appSettings.themeMode,
                    keepAliveInBackground = appSettings.keepAliveInBackground,
                    onLanguageChanged = { settingsRepo.setLanguage(it) },
                    onThemeChanged = { settingsRepo.setThemeMode(it) },
                    onKeepAliveChanged = { enabled ->
                        settingsRepo.setKeepAliveInBackground(enabled)
                        if (enabled) {
                            ContextCompat.startForegroundService(this, serviceIntent)
                        }
                    },
                    onRestartService = {
                        stopService(serviceIntent)
                        ContextCompat.startForegroundService(this, serviceIntent)
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val settings = AppSettingsRepository.getInstance(this).settings.value
        if (!settings.keepAliveInBackground && !AirPlaySessionManager.isStreaming.value) {
            val serviceIntent = Intent(this, AirPlayDaemonService::class.java)
            stopService(serviceIntent)
        }
    }
}

@Composable
fun AirPlaySettingsMainScreen(
    status: ConnectionStatus,
    deviceName: String,
    clientName: String,
    currentLanguage: AppLanguage,
    currentTheme: AppThemeMode,
    keepAliveInBackground: Boolean,
    onLanguageChanged: (AppLanguage) -> Unit,
    onThemeChanged: (AppThemeMode) -> Unit,
    onKeepAliveChanged: (Boolean) -> Unit,
    onRestartService: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Settings, 1: About

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 48.dp, vertical = 36.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Header with Navigation Tabs
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_airplay_logo),
                        contentDescription = "AirPlay TV Logo",
                        modifier = Modifier.size(52.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = AirPlayStrings.appName(currentLanguage),
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 30.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = AirPlayStrings.appSubtitle(currentLanguage),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp,
                            maxLines = 2
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                // TV Navigation Tabs (Settings / About)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TvNavButton(
                        text = AirPlayStrings.tabSettings(currentLanguage),
                        isSelected = selectedTab == 0,
                        onClick = { selectedTab = 0 }
                    )
                    TvNavButton(
                        text = AirPlayStrings.tabAbout(currentLanguage),
                        isSelected = selectedTab == 1,
                        onClick = { selectedTab = 1 }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Body Content based on Tab
            if (selectedTab == 0) {
                SettingsTabContent(
                    status = status,
                    deviceName = deviceName,
                    clientName = clientName,
                    currentLanguage = currentLanguage,
                    currentTheme = currentTheme,
                    keepAliveInBackground = keepAliveInBackground,
                    onLanguageChanged = onLanguageChanged,
                    onThemeChanged = onThemeChanged,
                    onKeepAliveChanged = onKeepAliveChanged,
                    onRestartService = onRestartService
                )
            } else {
                AboutTabContent(
                    currentLanguage = currentLanguage
                )
            }
        }
    }
}

@Composable
fun SettingsTabContent(
    status: ConnectionStatus,
    deviceName: String,
    clientName: String,
    currentLanguage: AppLanguage,
    currentTheme: AppThemeMode,
    keepAliveInBackground: Boolean,
    onLanguageChanged: (AppLanguage) -> Unit,
    onThemeChanged: (AppThemeMode) -> Unit,
    onKeepAliveChanged: (Boolean) -> Unit,
    onRestartService: () -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Status & How-To Cards Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Status Card
            TvCard(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(AppleAccentGreen, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = AirPlayStrings.serviceStatusActive(currentLanguage),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 18.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = when (status) {
                        ConnectionStatus.IDLE -> AirPlayStrings.statusIdle(deviceName, currentLanguage)
                        ConnectionStatus.WAITING_FOR_STREAM -> AirPlayStrings.statusConnecting(clientName, currentLanguage)
                        ConnectionStatus.STREAMING -> AirPlayStrings.statusStreaming(clientName, currentLanguage)
                        ConnectionStatus.DISCONNECTED -> AirPlayStrings.statusDisconnected(currentLanguage)
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            }

            // Instructions Card
            TvCard(modifier = Modifier.weight(1f)) {
                Text(
                    text = AirPlayStrings.howToConnectTitle(currentLanguage),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 18.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = AirPlayStrings.howToConnectInstructions(deviceName, currentLanguage),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    lineHeight = 22.sp
                )
            }
        }

        // Appearance & Language Configuration Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Theme Mode Card
            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = AirPlayStrings.settingsSectionAppearance(currentLanguage),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 18.sp
                    )
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
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = AirPlayStrings.settingsSectionLanguage(currentLanguage),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 18.sp
                    )
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

        // 24/7 Background Service Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = AirPlayStrings.settingKeepAliveTitle(currentLanguage),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 18.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = AirPlayStrings.settingKeepAliveDesc(currentLanguage),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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

        // Device & Network Info Card
        val context = androidx.compose.ui.platform.LocalContext.current
        val macAddress = remember { DeviceIdentityHelper.getDeviceMacAddress(context) }
        TvCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = AirPlayStrings.deviceInfoTitle(currentLanguage),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 18.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
            )
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

        // Restart Service Action Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            var isBtnFocused by remember { mutableStateOf(false) }
            Button(
                onClick = onRestartService,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isBtnFocused) AppleAccentBlue else MaterialTheme.colorScheme.surfaceVariant
                ),
                border = if (isBtnFocused) BorderStroke(2.dp, Color.White) else null,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.onFocusChanged { isBtnFocused = it.isFocused }
            ) {
                Text(
                    text = AirPlayStrings.restartService(currentLanguage),
                    fontSize = 15.sp,
                    color = if (isBtnFocused) Color.White else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(36.dp))
    }
}

@Composable
fun AboutTabContent(
    currentLanguage: AppLanguage
) {
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
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
                Text(
                    text = AirPlayStrings.aboutDisclaimerTitle(currentLanguage),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 18.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                )
            }
            Text(
                text = AirPlayStrings.aboutDisclaimerDesc(currentLanguage),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = AirPlayStrings.appSubtitle(currentLanguage),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                }
                Text(
                    text = AirPlayStrings.aboutVersion("1.0.0", currentLanguage),
                    color = AppleAccentBlue,
                    fontSize = 16.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
            }
        }

        // Architecture Card
        TvCard {
            Text(
                text = AirPlayStrings.aboutArchitectureTitle(currentLanguage),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 17.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = AirPlayStrings.aboutArchitectureDesc(currentLanguage),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        }

        // Protocols Card
        TvCard {
            Text(
                text = AirPlayStrings.aboutProtocolsTitle(currentLanguage),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 17.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = AirPlayStrings.aboutProtocolsDesc(currentLanguage),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        }

        // Developer Card
        TvCard {
            Text(
                text = AirPlayStrings.aboutDeveloperTitle(currentLanguage),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 17.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = AirPlayStrings.aboutDeveloperDesc(currentLanguage),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp
            )
        }

        // License Card
        TvCard(
            modifier = Modifier.onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.DirectionDown) {
                    coroutineScope.launch {
                        scrollState.animateScrollBy(150f)
                    }
                    false
                } else {
                    false
                }
            }
        ) {
            Text(
                text = AirPlayStrings.aboutLicenseTitle(currentLanguage),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 17.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = AirPlayStrings.aboutLicenseDesc(currentLanguage),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        }

        // Bottom spacer for comfortable TV viewing and scrolling clearance
        Spacer(modifier = Modifier.height(48.dp))
    }
}

@Composable
fun TvCard(
    modifier: Modifier = Modifier,
    borderColor: Color? = null,
    backgroundColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused }
            .focusable(),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor ?: when {
                isFocused -> MaterialTheme.colorScheme.surfaceVariant
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        border = when {
            isFocused -> BorderStroke(2.5.dp, AppleAccentBlue)
            borderColor != null -> BorderStroke(1.dp, borderColor)
            else -> BorderStroke(1.dp, Color.Transparent)
        },
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

@Composable
fun TvNavButton(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = when {
                isFocused -> AppleAccentBlue
                isSelected -> AppleAccentBlue.copy(alpha = 0.35f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        border = when {
            isFocused -> BorderStroke(3.dp, Color.White)
            isSelected -> BorderStroke(2.dp, AppleAccentBlue)
            else -> BorderStroke(1.dp, Color.Transparent)
        },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.onFocusChanged { isFocused = it.isFocused }
    ) {
        Text(
            text = text,
            color = when {
                isFocused -> Color.White
                isSelected -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontSize = 15.sp,
            fontWeight = if (isSelected || isFocused) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
fun TvChoiceChip(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = when {
                isFocused -> AppleAccentBlue
                isSelected -> AppleAccentBlue.copy(alpha = 0.25f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        border = when {
            isFocused -> BorderStroke(2.5.dp, Color.White)
            isSelected -> BorderStroke(1.5.dp, AppleAccentBlue)
            else -> null
        },
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.onFocusChanged { isFocused = it.isFocused }
    ) {
        Text(
            text = text,
            color = when {
                isFocused -> Color.White
                isSelected -> AppleAccentBlue
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontSize = 13.sp,
            fontWeight = if (isSelected || isFocused) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
