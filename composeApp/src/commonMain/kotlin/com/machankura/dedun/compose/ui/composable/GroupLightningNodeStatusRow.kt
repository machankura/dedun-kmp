package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.PhonelinkOff
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.machankura.dedun.compose.extensions.toChatListTimestampString
import com.machankura.dedun.compose.managers.GroupLightningNodeStatus
import com.machankura.dedun.compose.managers.LspDrop
import com.machankura.dedun.compose.managers.LspLink
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.theme.spacing
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.attempt_s
import dedun.composeapp.generated.resources.can_t_reach_the_lsp
import dedun.composeapp.generated.resources.connected_to_the_lsp
import dedun.composeapp.generated.resources.connecting_to_the_lsp
import dedun.composeapp.generated.resources.drop_closed_by_lsp
import dedun.composeapp.generated.resources.drop_feature_mismatch
import dedun.composeapp.generated.resources.drop_handshake
import dedun.composeapp.generated.resources.drop_other
import dedun.composeapp.generated.resources.drop_tor_without_onion
import dedun.composeapp.generated.resources.drop_unreachable
import dedun.composeapp.generated.resources.open_dedun_on_the_device_that_runs_it
import dedun.composeapp.generated.resources.refusal_no_onchain_wallet
import dedun.composeapp.generated.resources.refusal_no_seed
import dedun.composeapp.generated.resources.refusal_not_operator
import dedun.composeapp.generated.resources.refusal_pool_short
import dedun.composeapp.generated.resources.refusal_room_not_synced
import dedun.composeapp.generated.resources.since_s
import dedun.composeapp.generated.resources.starting_your_node
import dedun.composeapp.generated.resources.this_device_checks_again_every_minute
import dedun.composeapp.generated.resources.try_now
import dedun.composeapp.generated.resources.trying_again_in_n_minutes
import dedun.composeapp.generated.resources.trying_again_in_n_seconds
import dedun.composeapp.generated.resources.trying_again_now
import dedun.composeapp.generated.resources.waiting_for_enough_of_the_group_to_be_ready_to_sign
import dedun.composeapp.generated.resources.your_node_isn_t_running
import dedun.composeapp.generated.resources.your_node_isn_t_running_on_this_device
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * What this device's node for the group's Lightning wallet is doing, on the operator's own device
 * (`docs/lsp-connection-status.md`, Phase 4): whether it runs, and whether it can reach the LSP. [onTryNow]
 * skips the backoff's wait while it cannot. [compact] is the headline alone, for an earlier wallet's card.
 */
@Composable
internal fun NodeStatusRow(status: GroupLightningNodeStatus, onTryNow: (() -> Unit)?, compact: Boolean = false) {
    val link = (status as? GroupLightningNodeStatus.Running)?.link
    Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap)) {
        Box(modifier = Modifier.size(ICON_SIZE), contentAlignment = Alignment.Center) {
            when {
                status is GroupLightningNodeStatus.Starting || link is LspLink.Connecting ->
                    CircularProgressIndicator(modifier = Modifier.size(PROGRESS_SIZE), strokeWidth = PROGRESS_STROKE)
                link is LspLink.Connected -> StatusIcon(Icons.Default.CloudDone, MaterialTheme.colorScheme.primary)
                link is LspLink.Unreachable -> StatusIcon(Icons.Default.CloudOff, MaterialTheme.colorScheme.error)
                status is GroupLightningNodeStatus.NotOnThisDevice -> StatusIcon(Icons.Default.PhonelinkOff, MaterialTheme.colorScheme.onSurfaceVariant)
                else -> StatusIcon(Icons.Default.PowerOff, MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)) {
            Text(headline(status), style = MaterialTheme.typography.bodyLarge)
            if (!compact) {
                supporting(status).forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (link is LspLink.Unreachable) {
                    SelectionContainer {
                        Text(link.drop.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (onTryNow != null) {
                        // Once per wait: a second tap would only dial over the first.
                        var tapped by remember(link) { mutableStateOf(false) }
                        TextButton(onClick = { tapped = true; onTryNow() }, enabled = !tapped) { Text(stringResource(Res.string.try_now)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusIcon(icon: ImageVector, tint: Color) {
    // The headline beside it says the same.
    Icon(icon, contentDescription = Decorative, tint = tint)
}

@Composable
private fun headline(status: GroupLightningNodeStatus): String = stringResource(
    when (status) {
        GroupLightningNodeStatus.NotOnThisDevice -> Res.string.your_node_isn_t_running_on_this_device
        is GroupLightningNodeStatus.NotRunning -> Res.string.your_node_isn_t_running
        GroupLightningNodeStatus.Starting -> Res.string.starting_your_node
        is GroupLightningNodeStatus.Running -> when (status.link) {
            is LspLink.Connecting -> Res.string.connecting_to_the_lsp
            is LspLink.Connected -> Res.string.connected_to_the_lsp
            is LspLink.Unreachable -> Res.string.can_t_reach_the_lsp
        }
    }
)

@Composable
private fun supporting(status: GroupLightningNodeStatus): List<String> = when (status) {
    GroupLightningNodeStatus.NotOnThisDevice -> listOf(stringResource(Res.string.open_dedun_on_the_device_that_runs_it))
    is GroupLightningNodeStatus.NotRunning -> listOfNotNull(status.reason?.let { refusalText(it) }, stringResource(Res.string.this_device_checks_again_every_minute))
    GroupLightningNodeStatus.Starting -> listOf(stringResource(Res.string.waiting_for_enough_of_the_group_to_be_ready_to_sign))
    is GroupLightningNodeStatus.Running -> when (val link = status.link) {
        is LspLink.Connecting -> listOfNotNull(link.attempt.takeIf { it > 1 }?.let { stringResource(Res.string.attempt_s, it.toString()) })
        is LspLink.Connected -> listOf(stringResource(Res.string.since_s, link.since.toChatListTimestampString()))
        is LspLink.Unreachable -> listOf(dropText(link.drop.cause), retryText(link.retryAt))
    }
}

@Composable
internal fun refusalText(reason: GroupLightningNodeStatus.Refusal): String = stringResource(
    when (reason) {
        GroupLightningNodeStatus.Refusal.NOT_OPERATOR -> Res.string.refusal_not_operator
        GroupLightningNodeStatus.Refusal.ROOM_NOT_SYNCED -> Res.string.refusal_room_not_synced
        GroupLightningNodeStatus.Refusal.NO_SEED -> Res.string.refusal_no_seed
        GroupLightningNodeStatus.Refusal.POOL_SHORT -> Res.string.refusal_pool_short
        GroupLightningNodeStatus.Refusal.NO_ONCHAIN_WALLET -> Res.string.refusal_no_onchain_wallet
    }
)

/** Why a connection to the LSP ended, in words; shared with the members' row, which hears the cause by name. */
@Composable
internal fun dropText(cause: LspDrop.Cause): String = stringResource(
    when (cause) {
        LspDrop.Cause.FEATURE_MISMATCH -> Res.string.drop_feature_mismatch
        LspDrop.Cause.UNREACHABLE -> Res.string.drop_unreachable
        LspDrop.Cause.HANDSHAKE -> Res.string.drop_handshake
        LspDrop.Cause.CLOSED_BY_LSP -> Res.string.drop_closed_by_lsp
        LspDrop.Cause.TOR_WITHOUT_ONION -> Res.string.drop_tor_without_onion
        LspDrop.Cause.OTHER -> Res.string.drop_other
    }
)

/** *Trying again in 32 seconds*, counting down once a second while it is on screen; nothing in it moves. */
@Composable
private fun retryText(retryAt: Instant): String {
    val now by produceState(Clock.System.now(), retryAt) {
        while (true) {
            value = Clock.System.now()
            delay(1.seconds)
        }
    }
    val left = retryAt - now
    return when {
        left <= Duration.ZERO -> stringResource(Res.string.trying_again_now)
        left < 1.minutes -> left.inWholeSeconds.toInt().coerceAtLeast(1).let { pluralStringResource(Res.plurals.trying_again_in_n_seconds, it, it) }
        // Rounded up: "in 1 minute" while 61 seconds remain would be early.
        else -> ((left.inWholeSeconds + 59) / 60).toInt().let { pluralStringResource(Res.plurals.trying_again_in_n_minutes, it, it) }
    }
}

private val ICON_SIZE = 24.dp
private val PROGRESS_SIZE = 20.dp
private val PROGRESS_STROKE = 2.dp
