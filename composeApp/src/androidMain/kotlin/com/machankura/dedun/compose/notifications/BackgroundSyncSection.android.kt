package com.machankura.dedun.compose.notifications

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.machankura.dedun.android.background.StayConnectedService
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.background_android_explanation
import dedun.composeapp.generated.resources.stay_connected
import dedun.composeapp.generated.resources.stay_connected_supporting
import com.machankura.dedun.compose.ui.composable.SwitchRow
import org.jetbrains.compose.resources.stringResource

@Composable
actual fun PlatformBackgroundExplanation() {
    Text(stringResource(Res.string.background_android_explanation), style = MaterialTheme.typography.bodyMedium)
}

/** Stay connected, which only Android offers: the others either cannot or need not. Phase 10. */
@Composable
actual fun PlatformBackgroundSettings() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(StayConnectedService.isOn(context)) }
    SwitchRow(
        headline = stringResource(Res.string.stay_connected),
        supporting = stringResource(Res.string.stay_connected_supporting),
        checked = on,
        enabled = true,
    ) { checked ->
        on = checked
        StayConnectedService.set(context.applicationContext, checked)
    }
}
