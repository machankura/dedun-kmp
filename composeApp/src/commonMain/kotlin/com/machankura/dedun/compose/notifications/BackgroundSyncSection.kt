package com.machankura.dedun.compose.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.machankura.dedun.compose.database.model.NotificationLedger
import com.machankura.dedun.compose.extensions.toChatListTimestampString
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.background_last_checked
import dedun.composeapp.generated.resources.background_never_checked
import dedun.composeapp.generated.resources.background_sync
import com.machankura.dedun.compose.ui.theme.spacing
import org.jetbrains.compose.resources.stringResource

/**
 * What this platform does while the app is closed, said plainly, and when it last did it.
 * docs/notifications-and-background-sync.md, D10.
 *
 * The time is what a "my notifications are late" report starts from, which is why it is on the
 * screen and not only in a log.
 */
@Composable
fun BackgroundSyncSection(ledger: NotificationLedger) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = MaterialTheme.spacing.screenMargin,
            vertical = MaterialTheme.spacing.itemGap,
        ),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap),
    ) {
        Text(
            text = stringResource(Res.string.background_sync),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        PlatformBackgroundExplanation()
        Text(
            text = ledger.lastBackgroundSyncAt
                ?.let { stringResource(Res.string.background_last_checked, it.toChatListTimestampString()) }
                ?: stringResource(Res.string.background_never_checked),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    PlatformBackgroundSettings()
}

/** The sentence for this platform's background behaviour. */
@Composable
expect fun PlatformBackgroundExplanation()

/** Switches only this platform has (Android's Stay connected). Nothing elsewhere. */
@Composable
expect fun PlatformBackgroundSettings()
