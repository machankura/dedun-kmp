package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.text.grouped
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.composable.widgets.EmptyState
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.composable.widgets.QRCodeView
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.composable.widgets.approximateDuration
import com.machankura.dedun.compose.ui.composable.widgets.dialogs.ModalBottomSheet
import com.machankura.dedun.compose.ui.composable.widgets.rememberNotifier
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.SwapInWalletViewModel
import com.machankura.dedun.compose.ui.view.state.SwapInWalletUIState
import com.machankura.dedun.compose.wallet.FirstDeposit
import com.machankura.dedun.compose.wallet.SwapInAddress
import com.machankura.dedun.compose.wallet.SwapInCoin
import com.machankura.dedun.compose.wallet.SwapInNotice
import com.machankura.dedun.compose.wallet.SwapInRefusal
import com.machankura.dedun.compose.wallet.SwapInWaiting
import com.machankura.dedun.compose.wallet.bip21Uri
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.s_sats_added_to_your_lightning_balance
import dedun.composeapp.generated.resources.moving_s_sats_into_lightning
import dedun.composeapp.generated.resources.waiting_for_your_node_to_move_them
import dedun.composeapp.generated.resources.waiting_the_lsp_couldn_t_take_them_yet
import dedun.composeapp.generated.resources.moving_deposits_into_lightning_is_turned_off
import dedun.composeapp.generated.resources.waiting_the_fee_is_over_your_share
import dedun.composeapp.generated.resources.waiting_the_fee_is_over_your_limit
import dedun.composeapp.generated.resources.waiting_for_your_node_to_connect_to_the_lsp
import dedun.composeapp.generated.resources.address_copied
import dedun.composeapp.generated.resources.can_be_refunded_in_s
import dedun.composeapp.generated.resources.can_be_refunded_now
import dedun.composeapp.generated.resources.coins_sent_here_move_into_lightning_after_n
import dedun.composeapp.generated.resources.copy_address
import dedun.composeapp.generated.resources.copy_address_s
import dedun.composeapp.generated.resources.copy_transaction
import dedun.composeapp.generated.resources.deposit
import dedun.composeapp.generated.resources.deposit_address
import dedun.composeapp.generated.resources.deposits
import dedun.composeapp.generated.resources.finding_your_deposits
import dedun.composeapp.generated.resources.first_deposit_opens_a_channel
import dedun.composeapp.generated.resources.first_deposit_over_your_limit
import dedun.composeapp.generated.resources.legacy
import dedun.composeapp.generated.resources.moving_into_lightning
import dedun.composeapp.generated.resources.must_move_into_lightning_within_s
import dedun.composeapp.generated.resources.n_of_n_confirmations
import dedun.composeapp.generated.resources.nothing_has_been_sent_to_your_deposit_addresses_yet
import dedun.composeapp.generated.resources.onchain_deposits
import dedun.composeapp.generated.resources.paid_s_sats
import dedun.composeapp.generated.resources.paid_spent_since
import dedun.composeapp.generated.resources.ready_to_move_into_lightning
import dedun.composeapp.generated.resources.s_confirmations
import dedun.composeapp.generated.resources.s_sats
import dedun.composeapp.generated.resources.show_details
import dedun.composeapp.generated.resources.this_profile_has_no_wallet
import dedun.composeapp.generated.resources.transaction_copied
import dedun.composeapp.generated.resources.used_addresses
import dedun.composeapp.generated.resources.waiting_for_its_first_confirmation
import fr.acinq.bitcoin.Satoshi
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * The personal node's swap-in wallet (docs/personal-swap-in-wallet.md, Phase 3): the address to
 * deposit to, every coin sent there with where it is on its way into Lightning, and the addresses
 * already paid.
 *
 * The current address is the only one shown as a QR code. A used one is listed to be checked and
 * copied, never handed out again (D4); and as on the group's address list, nothing here opens a block
 * explorer, which would hand every address of the wallet to somebody else's server.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwapInWalletScreen(
    identity: Identity?,
    onNavigateBack: () -> Unit,
) {
    val swapInViewModel: SwapInWalletViewModel = viewModel(factory = SwapInWalletViewModel.factory(identity))
    LaunchedEffect(true) { swapInViewModel.initiate() }

    // In the screen's scope rather than a handler's: the notice is read as the node acts, not on a tap.
    val notify = rememberNotifier(rememberCoroutineScope())
    LaunchedEffect(swapInViewModel) {
        swapInViewModel.notices.collect { notice ->
            notify(
                when (notice) {
                    is SwapInNotice.Moving -> getString(Res.string.moving_s_sats_into_lightning, notice.amount.grouped())
                    is SwapInNotice.Received -> getString(Res.string.s_sats_added_to_your_lightning_balance, notice.amount.grouped())
                }
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.onchain_deposits), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { NavigateBackButton(onNavigateBack) },
            )
        },
    ) { padding ->
        SwapInWalletContent(
            uiState = swapInViewModel.uiState,
            onRetry = swapInViewModel::initiate,
            modifier = Modifier.padding(padding),
        )
    }
}

/** The screen's body for a given state: separate so that it can be drawn without a node. */
@Composable
fun SwapInWalletContent(
    uiState: SwapInWalletUIState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val notify = rememberNotifier(rememberCoroutineScope())
    var shown by remember { mutableStateOf<SwapInCoin?>(null) }

    ScreenStateTransition(uiState) { state ->
        when (state) {
            SwapInWalletUIState.Loading ->
                LoadingDataIndicator(modifier = modifier.fillMaxWidth(), text = stringResource(Res.string.finding_your_deposits))

            // The profile screen offers no way here for a profile without a node, so there is nothing to retry.
            SwapInWalletUIState.NoNode ->
                ErrorState(modifier = modifier, message = stringResource(Res.string.this_profile_has_no_wallet), onRetry = null)

            SwapInWalletUIState.Error ->
                ErrorState(modifier = modifier, onRetry = onRetry)

            is SwapInWalletUIState.Loaded -> {
                SwapInWalletList(state = state, notify = notify, onShow = { shown = it }, modifier = modifier)
                // Read from the latest state, so a sheet left open follows its coin as it confirms.
                shown?.let { coin -> state.coins.firstOrNull { it.outPoint == coin.outPoint } }?.let { coin ->
                    CoinSheet(coin = coin, notify = notify, onDismiss = { shown = null })
                }
            }
        }
    }
}

@Composable
private fun SwapInWalletList(
    state: SwapInWalletUIState.Loaded,
    notify: (String) -> Unit,
    onShow: (SwapInCoin) -> Unit,
    modifier: Modifier,
) {
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
        item(key = "receive") {
            ReceiveSection(state = state, onCopy = { copy(state.current.address) })
        }

        item(key = "deposits") {
            SectionHeading(stringResource(Res.string.deposits))
        }
        state.waiting?.let { waiting ->
            item(key = "waiting") { WaitingLine(waiting) }
        }
        if (state.coins.isEmpty()) {
            item(key = "no-deposits") { EmptyState(message = stringResource(Res.string.nothing_has_been_sent_to_your_deposit_addresses_yet)) }
        }
        items(state.coins, key = { "coin-${it.outPoint}" }) { coin ->
            CoinRow(coin = coin, onShow = { onShow(coin) })
        }

        if (state.used.isNotEmpty()) {
            item(key = "used") { SectionHeading(stringResource(Res.string.used_addresses)) }
            items(state.used, key = { "address-${it.address}" }) { address ->
                UsedAddressRow(address = address, onCopy = { copy(address.address) })
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = MaterialTheme.spacing.sectionGap),
    )
}

/** The address to deposit to, as a QR code, and what a deposit there will do. */
@Composable
private fun ReceiveSection(state: SwapInWalletUIState.Loaded, onCopy: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
    ) {
        Text(
            text = stringResource(Res.string.deposit_address),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(Res.string.coins_sent_here_move_into_lightning_after_n, state.minConfirmations),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        state.firstDeposit?.let { first ->
            Text(
                text = first.sentence(),
                style = MaterialTheme.typography.bodyMedium,
                color = if (first.overLimit != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // BIP21, so a scanning wallet knows what the text is.
        QRCodeView(bip21Uri(state.current.address))
        SelectionContainer {
            Text(text = state.current.address, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
        }
        TextButton(onClick = onCopy) {
            Icon(Icons.Default.ContentCopy, contentDescription = Decorative)
            Spacer(modifier = Modifier.width(MaterialTheme.spacing.relatedGap))
            Text(stringResource(Res.string.copy_address))
        }
    }
}

@Composable
private fun FirstDeposit.sentence(): String = when (val limit = overLimit) {
    null -> stringResource(Res.string.first_deposit_opens_a_channel, fee.grouped(), (minimum ?: fee).grouped())
    else -> stringResource(Res.string.first_deposit_over_your_limit, fee.grouped(), limit.grouped())
}

/**
 * Why the ready coins have not moved (docs/personal-swap-in-wallet.md, Phase 4). One line for all of
 * them, since the node moves every ready coin in one request and refuses them together.
 */
@Composable
private fun WaitingLine(waiting: SwapInWaiting) {
    val refused = waiting is SwapInWaiting.Refused && waiting.refusal != SwapInRefusal.Other
    Text(
        text = waiting.sentence(),
        style = MaterialTheme.typography.bodyMedium,
        color = if (refused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SwapInWaiting.sentence(): String = when (this) {
    SwapInWaiting.NotConnected -> stringResource(Res.string.waiting_for_your_node_to_connect_to_the_lsp)
    SwapInWaiting.ForNode -> stringResource(Res.string.waiting_for_your_node_to_move_them)
    is SwapInWaiting.Refused -> when (val refusal = refusal) {
        is SwapInRefusal.OverLimit -> stringResource(Res.string.waiting_the_fee_is_over_your_limit, refusal.fee.grouped(), refusal.limit.grouped())
        is SwapInRefusal.OverShare -> stringResource(
            Res.string.waiting_the_fee_is_over_your_share,
            refusal.fee.grouped(),
            "${refusal.basisPoints / 100}%",
            refusal.amount.grouped(),
        )
        SwapInRefusal.Disabled -> stringResource(Res.string.moving_deposits_into_lightning_is_turned_off)
        SwapInRefusal.Other -> stringResource(Res.string.waiting_the_lsp_couldn_t_take_them_yet)
    }
}

/** One coin: its amount, and where it is on its way into Lightning. A tap shows its transaction. */
@Composable
private fun CoinRow(coin: SwapInCoin, onShow: () -> Unit) {
    val showDetails = stringResource(Res.string.show_details)
    ListItem(
        modifier = Modifier.clickable(onClickLabel = showDetails, onClick = onShow).minimumInteractiveComponentSize(),
        headlineContent = { Text(stringResource(Res.string.s_sats, coin.amount.grouped())) },
        supportingContent = { Text(coin.status.label()) },
    )
}

/** A coin's transaction, to be copied and checked elsewhere, and how deep it is. */
@Composable
private fun CoinSheet(coin: SwapInCoin, notify: (String) -> Unit, onDismiss: () -> Unit) {
    val clipboardManager = LocalClipboardManager.current
    val copied = stringResource(Res.string.transaction_copied)
    val outPoint = "${coin.txId}:${coin.outPoint.index}"

    ModalBottomSheet(onDismiss = onDismiss, skipPartiallyExpanded = true, horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
        ) {
            Text(text = stringResource(Res.string.deposit), style = MaterialTheme.typography.titleMedium)
            Text(text = stringResource(Res.string.s_sats, coin.amount.grouped()), style = MaterialTheme.typography.headlineSmall)
            Text(
                text = coin.status.label(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(Res.string.s_confirmations, coin.confirmations.toString()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainer {
                Text(text = outPoint, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            }
            TextButton(
                onClick = {
                    clipboardManager.setText(buildAnnotatedString { append(coin.txId.toString()) })
                    notify(copied)
                }
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = Decorative)
                Spacer(modifier = Modifier.width(MaterialTheme.spacing.relatedGap))
                Text(stringResource(Res.string.copy_transaction))
            }
        }
    }
}

/** A paid address: there to check and copy, never to be shown as a QR code again. */
@Composable
private fun UsedAddressRow(address: SwapInAddress, onCopy: () -> Unit) {
    val name = address.index?.toString() ?: stringResource(Res.string.legacy)
    ListItem(
        leadingContent = { Text(text = name, style = MaterialTheme.typography.labelLarge) },
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
                Icon(Icons.Default.ContentCopy, contentDescription = stringResource(Res.string.copy_address_s, name))
            }
        },
    )
}

/** Where a coin is on its way into Lightning, in the catalogue's words. */
@Composable
internal fun SwapInCoin.Status.label(): String = when (this) {
    SwapInCoin.Status.Unconfirmed -> stringResource(Res.string.waiting_for_its_first_confirmation)
    is SwapInCoin.Status.Confirming -> stringResource(Res.string.n_of_n_confirmations, confirmations, needed)
    SwapInCoin.Status.Ready -> stringResource(Res.string.ready_to_move_into_lightning)
    SwapInCoin.Status.Swapping -> stringResource(Res.string.moving_into_lightning)
    is SwapInCoin.Status.ExpiringSoon -> stringResource(Res.string.must_move_into_lightning_within_s, approximateDuration(blocksLeft.toLong()))
    is SwapInCoin.Status.Locked -> stringResource(Res.string.can_be_refunded_in_s, approximateDuration(blocksUntilRefund.toLong()))
    SwapInCoin.Status.Refundable -> stringResource(Res.string.can_be_refunded_now)
}
