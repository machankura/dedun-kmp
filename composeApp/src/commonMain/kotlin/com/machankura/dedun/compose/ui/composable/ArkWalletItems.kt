package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.machankura.dedun.compose.text.grouped
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.composable.widgets.approximateDuration
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.ArkWalletUIState
import com.machankura.dedun.compose.wallet.ark.ArkExpiry
import com.machankura.dedun.compose.wallet.ark.ArkNetwork
import com.machankura.dedun.compose.wallet.ark.ArkUnavailability
import com.machankura.dedun.compose.wallet.ark.ArkWalletRollout
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.ark_wallet_coins_expired
import dedun.composeapp.generated.resources.ark_wallet_coins_last_s
import dedun.composeapp.generated.resources.ark_wallet_consent_backup
import dedun.composeapp.generated.resources.ark_wallet_consent_renewal
import dedun.composeapp.generated.resources.ark_wallet_consent_server_s
import dedun.composeapp.generated.resources.ark_wallet_consent_what
import dedun.composeapp.generated.resources.ark_wallet_could_not_open
import dedun.composeapp.generated.resources.ark_wallet_could_not_read
import dedun.composeapp.generated.resources.ark_wallet_not_turned_on
import dedun.composeapp.generated.resources.ark_wallet_opening
import dedun.composeapp.generated.resources.ark_wallet_pending_s_sats
import dedun.composeapp.generated.resources.ark_wallet_renewing_coins_expire_in_s
import dedun.composeapp.generated.resources.ark_wallet_restoring
import dedun.composeapp.generated.resources.ark_wallet_test_network_s
import dedun.composeapp.generated.resources.ark_wallet_turn_on
import dedun.composeapp.generated.resources.ark_wallet_turn_on_your_wallet_question
import dedun.composeapp.generated.resources.ark_wallet_unavailable_on_this_device
import dedun.composeapp.generated.resources.ark_wallet_unavailable_with_tor
import dedun.composeapp.generated.resources.ark_wallet_unavailable_without_a_phrase
import dedun.composeapp.generated.resources.ark_wallet_unavailable_yet
import dedun.composeapp.generated.resources.not_now
import dedun.composeapp.generated.resources.s_sats
import dedun.composeapp.generated.resources.wallet
import org.jetbrains.compose.resources.stringResource

/**
 * The profile's own wallet, on the profile tab above the guarded wallets --
 * `docs/bark-wallet.md`, Phase 4. One row: the spendable balance, and under it what
 * the wallet is waiting on, or why there is none.
 *
 * The row opens the wallet when there is one to open, and the first-open sheet when
 * the profile has not turned it on. A reason the user cannot change from here -- Tor,
 * this device, a profile without a phrase -- is a row that says so and does nothing.
 */
fun LazyListScope.arkWalletItems(
    state: ArkWalletUIState,
    onOpen: () -> Unit,
    onTurnOn: () -> Unit,
) {
    item {
        Text(
            modifier = Modifier.fillMaxWidth(),
            text = stringResource(Res.string.wallet),
            style = MaterialTheme.typography.titleMedium,
        )
    }
    item(key = "ark-wallet") {
        val action: (() -> Unit)? = when (state) {
            is ArkWalletUIState.Unavailable -> if (state.reason == ArkUnavailability.NotConsented) onTurnOn else null
            ArkWalletUIState.Loading -> null
            else -> onOpen
        }
        ListItem(
            modifier = if (action != null) Modifier.clickable(onClick = action).minimumInteractiveComponentSize() else Modifier,
            leadingContent = { Icon(Icons.Default.AccountBalanceWallet, contentDescription = Decorative) },
            headlineContent = {
                Text(
                    when (state) {
                        is ArkWalletUIState.Loaded -> stringResource(Res.string.s_sats, state.balance.spendable.grouped())
                        else -> stringResource(Res.string.wallet)
                    }
                )
            },
            supportingContent = { arkWalletSupportingLine(state)?.let { Text(it) } },
            trailingContent = if (state is ArkWalletUIState.Unavailable && state.reason == ArkUnavailability.NotConsented) {
                { TextButton(onClick = onTurnOn) { Text(stringResource(Res.string.ark_wallet_turn_on)) } }
            } else null,
        )
    }
}

/** What the row says under the balance, or instead of one. */
@Composable
internal fun arkWalletSupportingLine(state: ArkWalletUIState): String? = when (state) {
    ArkWalletUIState.Loading -> null
    is ArkWalletUIState.Unavailable -> arkUnavailableText(state.reason)
    ArkWalletUIState.Opening -> stringResource(Res.string.ark_wallet_opening)
    ArkWalletUIState.Restoring -> stringResource(Res.string.ark_wallet_restoring)
    is ArkWalletUIState.Failed -> stringResource(if (state.opened) Res.string.ark_wallet_could_not_read else Res.string.ark_wallet_could_not_open)
    is ArkWalletUIState.Loaded -> when {
        state.balance.pending.sat > 0 -> stringResource(Res.string.ark_wallet_pending_s_sats, state.balance.pending.grouped())
        else -> arkExpiryText(state.expiry)
    }
}

@Composable
internal fun arkUnavailableText(reason: ArkUnavailability): String = stringResource(
    when (reason) {
        ArkUnavailability.RolloutOff -> Res.string.ark_wallet_unavailable_yet
        ArkUnavailability.NoSeed -> Res.string.ark_wallet_unavailable_without_a_phrase
        ArkUnavailability.NotConsented -> Res.string.ark_wallet_not_turned_on
        ArkUnavailability.TorOn -> Res.string.ark_wallet_unavailable_with_tor
        ArkUnavailability.UnsupportedPlatform -> Res.string.ark_wallet_unavailable_on_this_device
    }
)

/** The earliest deadline, in time -- D12. Null when no coin expires. */
@Composable
internal fun arkExpiryText(expiry: ArkExpiry): String? = when (expiry) {
    ArkExpiry.Nothing -> null
    is ArkExpiry.Fine -> stringResource(Res.string.ark_wallet_coins_last_s, approximateDuration(expiry.blocksLeft))
    is ArkExpiry.RenewSoon -> stringResource(Res.string.ark_wallet_renewing_coins_expire_in_s, approximateDuration(expiry.blocksLeft))
    ArkExpiry.Expired -> stringResource(Res.string.ark_wallet_coins_expired)
}

/**
 * The first-open sheet -- D10. Until it is accepted nothing connects to Second: it
 * says who runs the server, what the server and its Esplora can see, that coins have
 * to be renewed, and what the phrase does and does not restore.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArkConsentSheet(
    onTurnOn: () -> Unit,
    onDismiss: () -> Unit,
    network: ArkNetwork = ArkWalletRollout.NETWORK,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MaterialTheme.spacing.screenMargin)
                .padding(bottom = MaterialTheme.spacing.sectionGap),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
        ) {
            Text(stringResource(Res.string.ark_wallet_turn_on_your_wallet_question), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(Res.string.ark_wallet_consent_what), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(Res.string.ark_wallet_consent_server_s, ArkWalletRollout.endpointsFor(network).server.removePrefix("https://")),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(stringResource(Res.string.ark_wallet_consent_renewal), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(Res.string.ark_wallet_consent_backup), style = MaterialTheme.typography.bodyMedium)
            if (!network.isMainnet) {
                Text(
                    stringResource(Res.string.ark_wallet_test_network_s, network.segment),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap, alignment = androidx.compose.ui.Alignment.End),
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.not_now)) }
                Button(onClick = onTurnOn) { Text(stringResource(Res.string.ark_wallet_turn_on)) }
            }
        }
    }
}
