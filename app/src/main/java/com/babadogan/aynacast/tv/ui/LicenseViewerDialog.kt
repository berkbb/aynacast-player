package com.babadogan.aynacast.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.babadogan.aynacast.tv.model.AppLanguage
import com.babadogan.aynacast.tv.ui.localization.AirPlayStrings

/**
 * Full-screen read-only viewer for the repository's GNU General Public License v3.0 (GPL-3.0).
 * Opens with one click/tap from the About screen and supports TV remote D-Pad scrolling.
 */
@Composable
fun LicenseViewerDialog(
    language: AppLanguage,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val licenseText = remember {
        try {
            context.assets.open("LICENSE").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            "GNU GENERAL PUBLIC LICENSE\nVersion 3, 29 June 2007\n\nhttps://www.gnu.org/licenses/gpl-3.0.html"
        }
    }
    val scrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    TvFullScreenWindow(
        title = AirPlayStrings.aboutLicenseTitle(language),
        subtitle = "GNU General Public License v3.0 (GPL-3.0)",
        language = language,
        bottomClose = true,
        onDismiss = onDismiss
    ) {
        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .focusable()
                .dpadScroll(scrollState, step = 320f)
                .verticalScroll(scrollState),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = licenseText,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                modifier = Modifier.padding(24.dp)
            )
        }
    }
}
