package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.machankura.dedun.compose.managers.GroupLightningWalletManager
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.GroupLightningRepository
import com.machankura.dedun.compose.text.grouped
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.composable.widgets.EmptyState
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.composable.widgets.PaymentReceived
import com.machankura.dedun.compose.ui.composable.widgets.QRCodeView
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.composable.widgets.approximateDuration
import com.machankura.dedun.compose.ui.composable.widgets.rememberNotifier
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.GroupSwapInViewModel
import com.machankura.dedun.compose.ui.view.state.GroupSwapInUIState
import com.machankura.dedun.compose.wallet.GroupLightningRollout
import com.machankura.dedun.compose.wallet.SwapInAddress
import com.machankura.dedun.compose.wallet.SwapInCoin
import com.machankura.dedun.compose.wallet.bip21Uri
import com.machankura.dedun.compose.wallet.lightning.GroupSwapInWallet
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.this_address_was_paid_and_the_next_one_is_below
import dedun.composeapp.generated.resources.couldn_t_ask_the_group_to_refund
import dedun.composeapp.generated.resources.the_group_was_asked_to_refund
import dedun.composeapp.generated.resources.ask_the_group_to_refund_s_sats
import dedun.composeapp.generated.resources.address_copied
import dedun.composeapp.generated.resources.can_be_refunded_in_s
import dedun.composeapp.generated.resources.can_be_refunded_now
import dedun.composeapp.generated.resources.coins_sent_here_move_into_the_group_s_lightning
import dedun.composeapp.generated.resources.copy_address
import dedun.composeapp.generated.resources.copy_address_s
import dedun.composeapp.generated.resources.deposit_onchain
import dedun.composeapp.generated.resources.deposits
import dedun.composeapp.generated.resources.finding_your_deposits
import dedun.composeapp.generated.resources.must_move_into_lightning_within_s
import dedun.composeapp.generated.resources.n_of_n_confirmations
import dedun.composeapp.generated.resources.nothing_has_been_sent_to_your_swap_in_addresses_yet
import dedun.composeapp.generated.resources.paid_s_sats
import dedun.composeapp.generated.resources.paid_spent_since
import dedun.composeapp.generated.resources.ready_coins_move_while_the_operator
import dedun.composeapp.generated.resources.ready_to_move_into_lightning
import dedun.composeapp.generated.resources.s_s_used_addresses
import dedun.composeapp.generated.resources.s_sats
import dedun.composeapp.generated.resources.s_sats_must_move_into_lightning_within_s
import dedun.composeapp.generated.resources.the_group_s_wallet_takes_no_onchain_deposits_on_this_network
import dedun.composeapp.generated.resources.used_addresses
import dedun.composeapp.generated.resources.waiting_for_its_first_confirmation
import dedun.composeapp.generated.resources.you_need_to_be_a_depositor
import dedun.composeapp.generated.resources.your_swap_in_address
import fr.acinq.bitcoin.Satoshi
import fr.acinq.lightning.utils.sum
import org.jetbrains.compose.resources.stringResource

/**
 * Depositing onchain into the group's Lightning wallet (`docs/group-lightning-swap-in.md`, Phase 6): the
 * member's own swap-in address as a QR code, their coins on it and where each is on its way into the
 * channel, and the addresses already used -- theirs and, to check against, everyone else's.
 *
 * Like the onchain addresses screen: only the member's own current address is offered to be paid, a used
 * one is copied and never shown as a QR code again, and nothing opens a block explorer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupSwapInScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    chatRepository: ChatRepository,
    lightningRepository: GroupLightningRepository,
    swapIns: (suspend (chatRoomId: String, wallet: GroupLightningWalletManager.Wallet) -> GroupSwapInWallet?)?,
    onNavigateBack: () -> Unit,
) {
    val viewModel: GroupSwapInViewModel = viewModel(
        factory = GroupSwapInViewModel.factory(activeUserPublicKey, chatRoomId, GroupLightningRollout.NETWORK, chatRepository, lightningRepository, swapIns)
    )
    LaunchedEffect(true) { viewModel.initiate() }

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.deposit_onchain), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { NavigateBackButton(onNavigateBack) },
            )
        },
    ) { padding ->
        val notify = rememberNotifier(rememberCoroutineScope())
        val asked = stringResource(Res.string.the_group_was_asked_to_refund)
        val couldNot = stringResource(Res.string.couldn_t_ask_the_group_to_refund)
        GroupSwapInContent(
            viewModel.uiState,
            Modifier.padding(padding),
            onRefund = if (viewModel.canPropose && !viewModel.isRefunding) {
                { viewModel.refund(onProposed = { notify(asked) }, onFailure = { notify(couldNot) }) }
            } else null,
        )
    }
}

/** The screen's body for a given state: separate so that it can be drawn without a group. */
@Composable
fun GroupSwapInContent(
    uiState: GroupSwapInUIState,
    modifier: Modifier = Modifier,
    /** Asks the group to refund the refundable coins; null where this profile cannot ask. */
    onRefund: (() -> Unit)? = null,
) {
    val notify = rememberNotifier(rememberCoroutineScope())
    ScreenStateTransition(uiState) { state ->
        when (state) {
            GroupSwapInUIState.Loading ->
                LoadingDataIndicator(modifier = modifier.fillMaxWidth(), text = stringResource(Res.string.finding_your_deposits))
            // Nothing to retry: the network decides it, and the Lightning wallet screen only links here when there is one.
            GroupSwapInUIState.Unavailable ->
                ErrorState(modifier = modifier, message = stringResource(Res.string.the_group_s_wallet_takes_no_onchain_deposits_on_this_network), onRetry = null)
            GroupSwapInUIState.NotDepositor ->
                EmptyState(modifier = modifier, message = stringResource(Res.string.you_need_to_be_a_depositor))
            is GroupSwapInUIState.Loaded -> SwapInList(state, notify, onRefund, modifier)
        }
    }
}

@Composable
private fun SwapInList(state: GroupSwapInUIState.Loaded, notify: (String) -> Unit, onRefund: (() -> Unit)?, modifier: Modifier) {
    val clipboardManager = LocalClipboardManager.current
    val addressCopied = stringResource(Res.string.address_copied)
    val copy = { address: String ->
        clipboardManager.setText(buildAnnotatedString { append(address) })
        notify(addressCopied)
    }

    LazyColumn(
        modifier = modifier.readableContent().fillMaxSize().padding(MaterialTheme.spacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap),
    ) {
        // Ahead of everything: the one thing on this screen with a deadline.
        val expiring = state.coins.filter { it.status is SwapInCoin.Status.ExpiringSoon }
        if (expiring.isNotEmpty()) {
            item(key = "expiring") {
                val soonest = expiring.minOf { (it.status as SwapInCoin.Status.ExpiringSoon).blocksLeft }
                Text(
                    text = stringResource(
                        Res.string.s_sats_must_move_into_lightning_within_s,
                        expiring.map { it.amount }.sum().grouped(),
                        approximateDuration(soonest.toLong()),
                        approximateDuration(state.refundDelay.toLong()),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // The address rotates by itself when one is paid, so what is missing here is only the
        // saying so (docs/paid-requests.md, D7).
        state.justPaid?.let { paid ->
            item(key = "just-paid") {
                PaymentReceived(
                    amount = paid.balance,
                    note = stringResource(Res.string.this_address_was_paid_and_the_next_one_is_below),
                )
            }
        }

        item(key = "receive") {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
            ) {
                Text(stringResource(Res.string.your_swap_in_address), style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
                Text(
                    text = stringResource(Res.string.coins_sent_here_move_into_the_group_s_lightning, state.minConfirmations),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
                // BIP21, so a scanning wallet knows what the text is.
                QRCodeView(bip21Uri(state.current.address))
                SelectionContainer {
                    Text(text = state.current.address, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
                }
                TextButton(onClick = { copy(state.current.address) }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = Decorative)
                    Spacer(modifier = Modifier.width(MaterialTheme.spacing.relatedGap))
                    Text(stringResource(Res.string.copy_address))
                }
            }
        }

        item(key = "deposits") { SectionHeading(stringResource(Res.string.deposits)) }
        if (state.coins.isEmpty()) {
            item(key = "no-deposits") { EmptyState(message = stringResource(Res.string.nothing_has_been_sent_to_your_swap_in_addresses_yet)) }
        }
        if (state.coins.any { it.status == SwapInCoin.Status.Ready || it.status is SwapInCoin.Status.ExpiringSoon }) {
            item(key = "waiting") {
                Text(stringResource(Res.string.ready_coins_move_while_the_operator), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // Past the refund delay only the group's FROST key spends a coin, back to the member's own branch (D9).
        val refundable = state.coins.filter { it.status == SwapInCoin.Status.Refundable }
        if (onRefund != null && refundable.isNotEmpty()) {
            item(key = "refund") {
                OutlinedButton(onClick = onRefund) {
                    Text(stringResource(Res.string.ask_the_group_to_refund_s_sats, refundable.map { it.amount }.sum().grouped()))
                }
            }
        }
        items(state.coins, key = { "coin-${it.outPoint}" }) { coin ->
            ListItem(
                headlineContent = { Text(stringResource(Res.string.s_sats, coin.amount.grouped())) },
                supportingContent = { Text(coin.status.label()) },
            )
        }

        if (state.used.isNotEmpty()) {
            item(key = "used") { SectionHeading(stringResource(Res.string.used_addresses)) }
            items(state.used, key = { "mine-${it.address}" }) { address -> UsedAddressRow(address, onCopy = { copy(address.address) }) }
        }
        state.others.forEach { (name, addresses) ->
            item(key = "others-$name") { SectionHeading(stringResource(Res.string.s_s_used_addresses, "@$name")) }
            items(addresses, key = { "other-${it.address}" }) { address -> UsedAddressRow(address, onCopy = { copy(address.address) }) }
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = MaterialTheme.spacing.sectionGap))
}

/** A paid address: there to check and copy, never to be shown as a QR code again. */
@Composable
private fun UsedAddressRow(address: SwapInAddress, onCopy: () -> Unit) {
    val index = address.index?.toString().orEmpty()
    ListItem(
        leadingContent = { Text(text = index, style = MaterialTheme.typography.labelLarge) },
        headlineContent = {
            Text(
                text = address.address,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        },
        supportingContent = {
            Text(
                if (address.balance > Satoshi(0)) stringResource(Res.string.paid_s_sats, address.balance.grouped())
                else stringResource(Res.string.paid_spent_since)
            )
        },
        trailingContent = {
            IconButton(onClick = onCopy) {
                Icon(Icons.Default.ContentCopy, contentDescription = stringResource(Res.string.copy_address_s, index))
            }
        },
    )
}

/** Where a coin is on its way into the group's Lightning balance, in the catalogue's words. */
@Composable
private fun SwapInCoin.Status.label(): String = when (this) {
    SwapInCoin.Status.Unconfirmed -> stringResource(Res.string.waiting_for_its_first_confirmation)
    is SwapInCoin.Status.Confirming -> stringResource(Res.string.n_of_n_confirmations, confirmations, needed)
    SwapInCoin.Status.Ready, SwapInCoin.Status.Swapping -> stringResource(Res.string.ready_to_move_into_lightning)
    is SwapInCoin.Status.ExpiringSoon -> stringResource(Res.string.must_move_into_lightning_within_s, approximateDuration(blocksLeft.toLong()))
    is SwapInCoin.Status.Locked -> stringResource(Res.string.can_be_refunded_in_s, approximateDuration(blocksUntilRefund.toLong()))
    SwapInCoin.Status.Refundable -> stringResource(Res.string.can_be_refunded_now)
}
