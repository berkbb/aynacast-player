package com.babadogan.aynacast.tv.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.babadogan.aynacast.tv.pairing.PinPairingManager
import com.babadogan.aynacast.tv.pairing.PinPairingManager.Outcome
import com.babadogan.aynacast.tv.session.ScreenTransitions
import com.babadogan.aynacast.tv.model.AppLanguage
import com.babadogan.aynacast.tv.ui.theme.AppleAccentGreen
import com.babadogan.aynacast.tv.ui.theme.AppleAccentOrange
import com.babadogan.aynacast.tv.ui.theme.AppleTextSecondary
import kotlinx.coroutines.delay
import com.babadogan.aynacast.tv.service.AirPlayDaemonService
import com.babadogan.aynacast.tv.settings.AppSettingsRepository
import com.babadogan.aynacast.tv.ui.localization.AirPlayStrings
import com.babadogan.aynacast.tv.ui.theme.AirPlayTvTheme
import com.babadogan.aynacast.tv.ui.theme.AppleAccentBlue

/** Shows the 4-digit AirPlay code the user types on the iPhone / Mac during first-time pairing. */
class PinCodeActivity : ComponentActivity() {

    companion object {
        private const val OUTRO_FADE_MS = 350
        private const val OUTRO_MS = 1_600L

        fun createIntent(context: Context): Intent =
            Intent(context, PinCodeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settingsRepo = AppSettingsRepository.getInstance(this)

        setContent {
            val appSettings by settingsRepo.settings.collectAsState()
            val pin by PinPairingManager.pinCode.collectAsState()
            val outcome by PinPairingManager.outcome.collectAsState()

            // Paired, expired (PinPairingManager owns the timeout) or too many wrong entries: the
            // result is shown for a moment. A new code arriving meanwhile cancels the close.
            LaunchedEffect(pin) {
                if (pin == null) {
                    if (PinPairingManager.outcome.value != null) {
                        ScreenTransitions.beginOutro(OUTRO_MS)
                        delay(OUTRO_MS)
                    }
                    finish()
                }
            }

            AirPlayTvTheme(themeMode = appSettings.themeMode) {
                val result = outcome
                if (pin == null && result != null) {
                    // Starts hidden so the card fades in over the code.
                    val shown = remember(result) { MutableTransitionState(false).apply { targetState = true } }
                    AnimatedVisibility(visibleState = shown, enter = fadeIn(tween(OUTRO_FADE_MS))) {
                        OutcomeCard(result, appSettings.language)
                    }
                    return@AirPlayTvTheme
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = AirPlayStrings.pinTitle(appSettings.language),
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(36.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            pin.orEmpty().forEach { digit ->
                                Box(
                                    modifier = Modifier
                                        .size(width = 110.dp, height = 140.dp)
                                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = digit.toString(),
                                        color = AppleAccentBlue,
                                        fontSize = 76.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(36.dp))
                        Text(
                            text = AirPlayStrings.pinInstructions(appSettings.language),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 18.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 96.dp)
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun OutcomeCard(outcome: Outcome, language: AppLanguage) {
        when (outcome) {
            Outcome.PAIRED -> StatusCard(
                AirPlayStrings.pinPairedTitle(language), AirPlayStrings.pinPairedSubtitle(language),
                active = false, symbol = "✓", accent = AppleAccentGreen
            )
            Outcome.EXPIRED -> StatusCard(
                AirPlayStrings.pinExpiredTitle(language), AirPlayStrings.pinRetrySubtitle(language),
                active = false, symbol = "!", accent = AppleAccentOrange
            )
            Outcome.WRONG_CODE -> StatusCard(
                AirPlayStrings.pinWrongCodeTitle(language), AirPlayStrings.pinRetrySubtitle(language),
                active = false, symbol = "!", accent = AppleAccentOrange
            )
            Outcome.LOCKED -> StatusCard(
                AirPlayStrings.pinLockedTitle(language), AirPlayStrings.pinLockedSubtitle(language),
                active = false, symbol = "!", accent = AppleAccentOrange
            )
            Outcome.CANCELLED -> StatusCard(
                AirPlayStrings.pinCancelledTitle(language), "",
                active = false, symbol = "✕", accent = AppleTextSecondary
            )
        }
    }

    override fun onResume() {
        super.onResume()
        AirPlayDaemonService.cancelScreenNotification(this, AirPlayDaemonService.PIN_NOTIFICATION_ID)
    }
}
