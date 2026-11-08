package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.GroupWavelengthRepository
import com.machankura.dedun.compose.text.grouped
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.composable.widgets.EmptyState
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.LocalAmount
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.composable.widgets.InvoiceExpired
import com.machankura.dedun.compose.ui.composable.widgets.PaymentReceived
import com.machankura.dedun.compose.ui.composable.widgets.QRCodeView
import com.machankura.dedun.compose.ui.composable.widgets.RaiseBrightnessWhileVisible
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.composable.widgets.rememberInvoiceExpired
import com.machankura.dedun.compose.ui.composable.widgets.rememberNotifier
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.composable.widgets.wallet.GroupWavelengthGlossaryButton
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.GroupWavelengthReceiveViewModel
import com.machankura.dedun.compose.ui.view.state.GroupWavelengthReceiveUIState
import com.machankura.dedun.compose.wallet.bip21Uri
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.address_copied
import dedun.composeapp.generated.resources.an_expired_invoice_cannot_be_paid_ask_for_another
import dedun.composeapp.generated.resources.ask_for_another_address
import dedun.composeapp.generated.resources.copy_address
import dedun.composeapp.generated.resources.group_wavelength_invoice_route
import dedun.composeapp.generated.resources.group_wavelength_invoice_route_why
import dedun.composeapp.generated.resources.qr_group_wavelength_address
import dedun.composeapp.generated.resources.qr_group_wavelength_invoice
import dedun.composeapp.generated.resources.this_address_was_paid_ask_for_another
import dedun.composeapp.generated.resources.this_invoice_was_paid_and_should_not_be_paid_again
import dedun.composeapp.generated.resources.group_wavelength_invoice_amount
import dedun.composeapp.generated.resources.group_wavelength_invoice_ask
import dedun.composeapp.generated.resources.group_wavelength_invoice_ask_another
import dedun.composeapp.generated.resources.group_wavelength_invoice_checked_s
import dedun.composeapp.generated.resources.group_wavelength_invoice_copied
import dedun.composeapp.generated.resources.group_wavelength_invoice_copy
import dedun.composeapp.generated.resources.group_wavelength_invoice_couldnt_ask
import dedun.composeapp.generated.resources.group_wavelength_invoice_devices_needed
import dedun.composeapp.generated.resources.group_wavelength_invoice_limits_s_s
import dedun.composeapp.generated.resources.group_wavelength_invoice_waiting
import dedun.composeapp.generated.resources.group_wavelength_invoice_warning
import dedun.composeapp.generated.resources.group_wavelength_receive_address
import dedun.composeapp.generated.resources.group_wavelength_receive_ask
import dedun.composeapp.generated.resources.group_wavelength_receive_at_cap
import dedun.composeapp.generated.resources.group_wavelength_receive_checked
import dedun.composeapp.generated.resources.group_wavelength_receive_confirmations_s
import dedun.composeapp.generated.resources.group_wavelength_receive_couldnt_ask
import dedun.composeapp.generated.resources.group_wavelength_receive_fee_s
import dedun.composeapp.generated.resources.group_wavelength_receive_intro
import dedun.composeapp.generated.resources.group_wavelength_receive_limits_s_s
import dedun.composeapp.generated.resources.group_wavelength_receive_nobody_runs_it
import dedun.composeapp.generated.resources.group_wavelength_receive_not_a_depositor
import dedun.composeapp.generated.resources.group_wavelength_receive_title
import dedun.composeapp.generated.resources.group_wavelength_receive_waiting
import dedun.composeapp.generated.resources.group_wavelength_test_network_s
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Satoshi
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** A member's address for paying the group's Wavelength wallet, with what it takes and what it costs said before anything's sent. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupWavelengthReceiveScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    chain: Chain,
    chatRepository: ChatRepository,
    repository: GroupWavelengthRepository,
    onNavigateBack: () -> Unit,
    onOpenInvoice: () -> Unit = {},
) {
    val viewModel: GroupWavelengthReceiveViewModel = viewModel(
        key = "wavelength-receive:$chatRoomId:${GroupWalletStatementEvent.chainName(chain)}",
        factory = GroupWavelengthReceiveViewModel.factory(activeUserPublicKey, chatRoomId, chain, chatRepository, repository),
    )
    LaunchedEffect(true) { viewModel.initiate() }
    val notify = rememberNotifier(rememberCoroutineScope())
    val couldNotAsk = stringResource(Res.string.group_wavelength_receive_couldnt_ask)
    val badAmount = stringResource(Res.string.group_wavelength_invoice_couldnt_ask)
    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.group_wavelength_receive_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { NavigateBackButton(onNavigateBack) },
                actions = { GroupWavelengthGlossaryButton() },
            )
        }
    ) { padding ->
        ScreenStateTransition(viewModel.uiState) { uiState ->
            GroupWavelengthReceiveContent(
                Modifier.padding(padding), uiState, chain, notify,
                onAsk = { viewModel.ask(onFailure = { notify(couldNotAsk) }) },
                onOpenInvoice = onOpenInvoice,
            )
        }
    }
}

@Composable
internal fun GroupWavelengthReceiveContent(modifier: Modifier, state: GroupWavelengthReceiveUIState, chain: Chain, notify: (String) -> Unit = {}, onAsk: () -> Unit = {}, onOpenInvoice: () -> Unit = {}) {
    when (state) {
        GroupWavelengthReceiveUIState.Loading -> LoadingDataIndicator(modifier = modifier.fillMaxWidth())
        is GroupWavelengthReceiveUIState.Error -> ErrorState(modifier = modifier, message = state.message, onRetry = null)
        GroupWavelengthReceiveUIState.NotADepositor -> EmptyState(modifier = modifier.readableContent(), message = stringResource(Res.string.group_wavelength_receive_not_a_depositor), icon = Icons.Default.AccountBalanceWallet)
        GroupWavelengthReceiveUIState.NobodyRunsIt -> EmptyState(modifier = modifier.readableContent(), message = stringResource(Res.string.group_wavelength_receive_nobody_runs_it), icon = Icons.Default.Waves)
        GroupWavelengthReceiveUIState.AtTheCap -> EmptyState(modifier = modifier.readableContent(), message = stringResource(Res.string.group_wavelength_receive_at_cap), icon = Icons.Default.Waves)
        is GroupWavelengthReceiveUIState.Loaded -> TheWayIn(modifier, state, chain, notify, onAsk, onOpenInvoice)
    }
}

/**
 * One way in, and a disclosed route to the other (B4).
 *
 * This was a `FilterChip` pair, which made the choice look like a preference between two equivalent
 * rails. It is not: an address is one **this device recomputed from the group's key** and a Lightning
 * invoice is one **whoever runs the wallet made, which no device here can check**. The address is the
 * screen; the invoice is a labelled route away from it, with the reason on the button rather than
 * behind it.
 */
@Composable
private fun TheWayIn(modifier: Modifier, state: GroupWavelengthReceiveUIState.Loaded, chain: Chain, notify: (String) -> Unit, onAsk: () -> Unit, onOpenInvoice: () -> Unit) {
    // Turned up while this screen is open: a code held out in a dim room is a code a camera cannot
    // resolve, and the member holding it has no way to know that is why nothing is happening.
    RaiseBrightnessWhileVisible()
    LazyColumn(
        modifier = modifier.readableContent().fillMaxSize().padding(MaterialTheme.spacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!chain.isMainnet()) {
            item { Text(stringResource(Res.string.group_wavelength_test_network_s, GroupWalletStatementEvent.chainName(chain)), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary) }
        }
        onchainItems(state, notify, onAsk)
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onOpenInvoice) { Text(stringResource(Res.string.group_wavelength_invoice_route)) }
                Line(stringResource(Res.string.group_wavelength_invoice_route_why))
            }
        }
    }
}

private fun LazyListScope.onchainItems(state: GroupWavelengthReceiveUIState.Loaded, notify: (String) -> Unit, onAsk: () -> Unit) {
    val address = state.address
    if (address == null) {
        item { Line(stringResource(if (state.addressAsked) Res.string.group_wavelength_receive_waiting else Res.string.group_wavelength_receive_intro)) }
        if (!state.addressAsked) item { Button(onClick = onAsk) { Text(stringResource(Res.string.group_wavelength_receive_ask)) } }
        return
    }
    item { Text(stringResource(Res.string.group_wavelength_receive_address), style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth()) }
    val paid = state.addressPaid
    if (paid != null) {
        // No operator fallback on this rail, so this is always the device's own word.
        item {
            PaymentReceived(
                amount = paid.amount,
                note = stringResource(Res.string.this_address_was_paid_ask_for_another),
                action = { Button(onClick = onAsk) { Text(stringResource(Res.string.ask_for_another_address)) } },
            )
        }
    } else {
        item { Text(stringResource(Res.string.group_wavelength_receive_checked), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth()) }
        item { QRCodeView(bip21Uri(address), stringResource(Res.string.qr_group_wavelength_address)) }
    }
    item { Copyable(address, Res.string.copy_address, Res.string.address_copied, notify) }
    state.terms?.let { terms ->
        item { Line(stringResource(Res.string.group_wavelength_receive_confirmations_s, terms.boardingConfirmations.toString())) }
        item { Line(stringResource(Res.string.group_wavelength_receive_limits_s_s, Satoshi(terms.minBoardingSat).grouped(), Satoshi(terms.maxVtxoSat).grouped())) }
    }
    item {
        Column {
            Line(stringResource(Res.string.group_wavelength_receive_fee_s, Satoshi(state.maxRoundFeeSat).grouped()))
            LocalAmount(Satoshi(state.maxRoundFeeSat))
        }
    }
}

@Composable
internal fun Copyable(text: String, copyLabel: StringResource, copiedLabel: StringResource, notify: (String) -> Unit) {
    val clipboardManager = LocalClipboardManager.current
    val copied = stringResource(copiedLabel)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)) {
        SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)) }
        TextButton(onClick = {
            clipboardManager.setText(buildAnnotatedString { append(text) })
            notify(copied)
        }) {
            Icon(Icons.Default.ContentCopy, contentDescription = Decorative)
            Spacer(modifier = Modifier.width(MaterialTheme.spacing.relatedGap))
            Text(stringResource(copyLabel))
        }
    }
}

@Composable
internal fun Line(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
}
