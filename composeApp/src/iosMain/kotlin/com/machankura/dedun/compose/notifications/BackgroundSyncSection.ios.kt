package com.machankura.dedun.compose.notifications

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.background_ios_explanation
import dedun.composeapp.generated.resources.background_ios_refresh_off
import org.jetbrains.compose.resources.stringResource
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundRefreshStatus

@Composable
actual fun PlatformBackgroundExplanation() {
    Text(stringResource(Res.string.background_ios_explanation), style = MaterialTheme.typography.bodyMedium)
    val refreshOff = remember {
        UIApplication.sharedApplication.backgroundRefreshStatus != UIBackgroundRefreshStatus.UIBackgroundRefreshStatusAvailable
    }
    if (refreshOff) {
        Text(
            stringResource(Res.string.background_ios_refresh_off),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
actual fun PlatformBackgroundSettings() = Unit
