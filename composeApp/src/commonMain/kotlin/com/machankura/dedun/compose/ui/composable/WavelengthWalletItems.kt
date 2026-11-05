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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.machankura.dedun.compose.text.grouped
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.composable.widgets.approximateDuration
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.WavelengthWalletUIState
import com.machankura.dedun.compose.wallet.wavelength.WalletExpiry
import com.machankura.dedun.compose.wallet.wavelength.WalletUnavailability
import com.machankura.dedun.compose.wallet.wavelength.WavelengthWalletRollout
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.not_now
import dedun.composeapp.generated.resources.s_sats
import dedun.composeapp.generated.resources.wallet
import dedun.composeapp.generated.resources.wavelength_coins_expired
import dedun.composeapp.generated.resources.wavelength_coins_last_s
import dedun.composeapp.generated.resources.wavelength_coins_renewing_s
import dedun.composeapp.generated.resources.wavelength_coins_urgent_s
import dedun.composeapp.generated.resources.wavelength_consent_backup
import dedun.composeapp.generated.resources.wavelength_consent_early
import dedun.composeapp.generated.resources.wavelength_consent_one_device
import dedun.composeapp.generated.resources.wavelength_consent_renewal
import dedun.composeapp.generated.resources.wavelength_consent_server
import dedun.composeapp.generated.resources.wavelength_consent_what
import dedun.composeapp.generated.resources.wavelength_could_not_open
import dedun.composeapp.generated.resources.wavelength_not_offered
import dedun.composeapp.generated.resources.wavelength_not_turned_on
import dedun.composeapp.generated.resources.wavelength_opening
import dedun.composeapp.generated.resources.wavelength_pending_s_sats
import dedun.composeapp.generated.resources.wavelength_recovery_phrase
import dedun.composeapp.generated.resources.wavelength_restoring
import dedun.composeapp.generated.resources.wavelength_syncing
import dedun.composeapp.generated.resources.wavelength_test_network_s
import dedun.composeapp.generated.resources.wavelength_turn_on
import dedun.composeapp.generated.resources.wavelength_turn_on_question
import dedun.composeapp.generated.resources.wavelength_unavailable_on_this_device
import dedun.composeapp.generated.resources.wavelength_unavailable_with_tor
import dedun.composeapp.generated.resources.wavelength_unavailable_without_a_phrase
import dedun.composeapp.generated.resources.wavelength_wrong_identity
import engineering.lightning.wavelength.Network
import fr.acinq.bitcoin.Satoshi
import org.jetbrains.compose.resources.stringResource

/**
 * The profile's own wallet, on the profile tab above the guarded wallets --
 * `docs/wavelength-wallet.md`, Phase 5. One row: the balance, and under it what the wallet is
 * waiting on, or why there is none.
 *
 * The row opens the wallet when there is one to open, and the first-open sheet when the profile
 * has not turned it on. A reason the user cannot change from here -- Tor, this device, a profile
 * without a phrase -- is a row that says so and does nothing.
 */
fun LazyListScope.wavelengthWalletItems(
    state: WavelengthWalletUIState,
    onOpen: () -> Unit,
    onTurnOn: () -> Unit,
    /** The words of a wallet with its own: an nsec's. Whatever state the wallet is in, since they are the way out of most of them. */
    onRecoveryPhrase: (() -> Unit)? = null,
) {
    item {
        Text(
            modifier = Modifier.fillMaxWidth(),
            text = stringResource(Res.string.wallet),
            style = MaterialTheme.typography.titleMedium,
        )
    }
    item(key = "wavelength-wallet") {
        val action: (() -> Unit)? = when (state) {
            is WavelengthWalletUIState.Unavailable -> if (state.reason == WalletUnavailability.NotConsented) onTurnOn else null
            WavelengthWalletUIState.Loading, WavelengthWalletUIState.WrongIdentity -> null
            else -> onOpen
        }
        ListItem(
            modifier = if (action != null) Modifier.clickable(onClick = action).minimumInteractiveComponentSize() else Modifier,
            leadingContent = { Icon(Icons.Default.AccountBalanceWallet, contentDescription = Decorative) },
            headlineContent = {
                Text(
                    when (state) {
                        is WavelengthWalletUIState.Loaded -> stringResource(Res.string.s_sats, Satoshi(state.balance.confirmedSat).grouped())
                        else -> stringResource(Res.string.wallet)
                    }
                )
            },
            supportingContent = { wavelengthWalletSupportingLine(state)?.let { Text(it) } },
            trailingContent = if (state is WavelengthWalletUIState.Unavailable && state.reason == WalletUnavailability.NotConsented) {
                { TextButton(onClick = onTurnOn) { Text(stringResource(Res.string.wavelength_turn_on)) } }
            } else null,
        )
    }
    val consented = !(state is WavelengthWalletUIState.Unavailable && state.reason == WalletUnavailability.NotConsented)
    if (onRecoveryPhrase != null && consented) {
        item(key = "wavelength-wallet-phrase") {
            TextButton(onClick = onRecoveryPhrase) { Text(stringResource(Res.string.wavelength_recovery_phrase)) }
        }
    }
}

/** What the row says under the balance, or instead of one. */
@Composable
internal fun wavelengthWalletSupportingLine(state: WavelengthWalletUIState): String? = when (state) {
    WavelengthWalletUIState.Loading -> null
    is WavelengthWalletUIState.Unavailable -> wavelengthUnavailableText(state.reason)
    WavelengthWalletUIState.Opening -> stringResource(Res.string.wavelength_opening)
    WavelengthWalletUIState.Restoring -> stringResource(Res.string.wavelength_restoring)
    WavelengthWalletUIState.Failed -> stringResource(Res.string.wavelength_could_not_open)
    WavelengthWalletUIState.WrongIdentity -> stringResource(Res.string.wavelength_wrong_identity)
    is WavelengthWalletUIState.Loaded -> when {
        state.pendingSat > 0 -> stringResource(Res.string.wavelength_pending_s_sats, Satoshi(state.pendingSat).grouped())
        state.syncing -> stringResource(Res.string.wavelength_syncing)
        else -> wavelengthExpiryText(state.expiry)
    }
}

/** The earliest deadline, in time -- D10. Null when no coin has one. */
@Composable
internal fun wavelengthExpiryText(expiry: WalletExpiry): String? = when (expiry) {
    WalletExpiry.Nothing -> null
    is WalletExpiry.Fine -> stringResource(Res.string.wavelength_coins_last_s, approximateDuration(expiry.blocksLeft))
    is WalletExpiry.Renewing -> stringResource(Res.string.wavelength_coins_renewing_s, approximateDuration(expiry.blocksLeft))
    is WalletExpiry.Urgent -> stringResource(Res.string.wavelength_coins_urgent_s, approximateDuration(expiry.blocksLeft))
    WalletExpiry.Expired -> stringResource(Res.string.wavelength_coins_expired)
}

@Composable
internal fun wavelengthUnavailableText(reason: WalletUnavailability): String = stringResource(
    when (reason) {
        WalletUnavailability.NoSeed -> Res.string.wavelength_unavailable_without_a_phrase
        WalletUnavailability.UnsupportedPlatform -> Res.string.wavelength_unavailable_on_this_device
        WalletUnavailability.TorOn -> Res.string.wavelength_unavailable_with_tor
        WalletUnavailability.NotConsented -> Res.string.wavelength_not_turned_on
        WalletUnavailability.NotOffered -> Res.string.wavelength_not_offered
    }
)

/**
 * The first-open sheet -- D9. Until it is accepted nothing connects to Lightning Labs: it says
 * who runs the server and what it can see, that the app has to be opened to keep coins renewed,
 * what the phrase restores, and that the software is young.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WavelengthConsentSheet(
    onTurnOn: () -> Unit,
    onDismiss: () -> Unit,
    network: Network = WavelengthWalletRollout.NETWORK,
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
            Text(stringResource(Res.string.wavelength_turn_on_question), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(Res.string.wavelength_consent_what), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(Res.string.wavelength_consent_server), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(Res.string.wavelength_consent_renewal), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(Res.string.wavelength_consent_backup), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(Res.string.wavelength_consent_one_device), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(Res.string.wavelength_consent_early), style = MaterialTheme.typography.bodyMedium)
            if (network != Network.MAINNET) {
                Text(
                    stringResource(Res.string.wavelength_test_network_s, network.wire),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap, alignment = Alignment.End),
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.not_now)) }
                Button(onClick = onTurnOn) { Text(stringResource(Res.string.wavelength_turn_on)) }
            }
        }
    }
}
