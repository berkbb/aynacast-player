package com.babadogan.aynacast.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.babadogan.aynacast.tv.R
import com.babadogan.aynacast.tv.model.AppLanguage
import com.babadogan.aynacast.tv.ui.localization.AirPlayStrings
import com.babadogan.aynacast.tv.ui.theme.AppleDarkBackground
import com.babadogan.aynacast.tv.ui.theme.FocusFill

/**
 * Full-screen window with a title and a close button in the top-right corner.
 * Back closes it too, through the dialog's dismiss request.
 */
@Composable
fun TvFullScreenWindow(
    title: String,
    language: AppLanguage,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    // A Close button below the content too, as in Pitwall Updater, for long scrolling pages.
    bottomClose: Boolean = false,
    // Extra buttons shown to the left of the bottom Close button.
    bottomActions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(48.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AccentBar(height = 26.dp)
                        Spacer(Modifier.width(14.dp))
                        Text(
                            text = title,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                    if (subtitle != null) {
                        Spacer(Modifier.padding(top = 6.dp))
                        Text(
                            text = subtitle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp
                        )
                    }
                }
                CloseIconButton(
                    contentDescription = AirPlayStrings.close(language),
                    onClick = onDismiss
                )
            }
            Spacer(Modifier.padding(top = 20.dp))
            content()
            if (bottomClose) {
                Spacer(Modifier.padding(top = 20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End)
                ) {
                    bottomActions()
                    TvNavButton(
                        text = AirPlayStrings.close(language),
                        isSelected = true,
                        onClick = onDismiss
                    )
                }
            }
        }
    }
}

@Composable
private fun CloseIconButton(contentDescription: String, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (isFocused) FocusFill else MaterialTheme.colorScheme.surfaceVariant)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_close),
            contentDescription = contentDescription,
            tint = if (isFocused) AppleDarkBackground else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp)
        )
    }
}
