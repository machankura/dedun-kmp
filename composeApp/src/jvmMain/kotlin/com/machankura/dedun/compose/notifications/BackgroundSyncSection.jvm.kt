package com.machankura.dedun.compose.notifications

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.background_desktop_explanation
import org.jetbrains.compose.resources.stringResource

@Composable
actual fun PlatformBackgroundExplanation() {
    Text(stringResource(Res.string.background_desktop_explanation), style = MaterialTheme.typography.bodyMedium)
}

@Composable
actual fun PlatformBackgroundSettings() = Unit
