package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.GroupWavelengthRepository
import com.machankura.dedun.compose.text.grouped
import com.machankura.dedun.compose.ui.composable.widgets.EmptyState
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import com.machankura.dedun.compose.ui.composable.widgets.InvoiceExpired
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.composable.widgets.PaymentReceived
import com.machankura.dedun.compose.ui.composable.widgets.QRCodeView
import com.machankura.dedun.compose.ui.composable.widgets.RaiseBrightnessWhileVisible
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.composable.widgets.rememberInvoiceExpired
import com.machankura.dedun.compose.ui.composable.widgets.rememberNotifier
import com.machankura.dedun.compose.ui.composable.widgets.wallet.GroupWavelengthGlossaryButton
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.GroupWavelengthReceiveViewModel
import com.machankura.dedun.compose.ui.view.state.GroupWavelengthReceiveUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.an_expired_invoice_cannot_be_paid_ask_for_another
import dedun.composeapp.generated.resources.group_wavelength_invoice_amount
import dedun.composeapp.generated.resources.group_wavelength_invoice_ask
import dedun.composeapp.generated.resources.group_wavelength_invoice_ask_another
import dedun.composeapp.generated.resources.group_wavelength_invoice_checked_s
import dedun.composeapp.generated.resources.group_wavelength_invoice_copied
import dedun.composeapp.generated.resources.group_wavelength_invoice_copy
import dedun.composeapp.generated.resources.group_wavelength_invoice_couldnt_ask
import dedun.composeapp.generated.resources.group_wavelength_invoice_devices_needed
import dedun.composeapp.generated.resources.group_wavelength_invoice_limits_s_s
import dedun.composeapp.generated.resources.group_wavelength_invoice_title
import dedun.composeapp.generated.resources.group_wavelength_invoice_waiting
import dedun.composeapp.generated.resources.group_wavelength_invoice_warning
import dedun.composeapp.generated.resources.group_wavelength_receive_at_cap
import dedun.composeapp.generated.resources.group_wavelength_receive_nobody_runs_it
import dedun.composeapp.generated.resources.group_wavelength_receive_not_a_depositor
import dedun.composeapp.generated.resources.qr_group_wavelength_invoice
import dedun.composeapp.generated.resources.this_invoice_was_paid_and_should_not_be_paid_again
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Satoshi
import org.jetbrains.compose.resources.stringResource

/**
 * An invoice for paying the group's Wavelength wallet over Lightning.
 *
 * A screen of its own, reached by a labelled button from the receive screen, rather than the second
 * half of a chip pair (B4 of `docs/group-wavelength-redesign.md`). The warning is the first thing on
 * it and there is no way past it: this is the one place in this wallet where a member takes somebody
 * else's word for where their money went, and a toggle that looked like a choice of rail hid the only
 * thing worth deciding.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupWavelengthInvoiceScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    chain: Chain,
    chatRepository: ChatRepository,
    repository: GroupWavelengthRepository,
    onNavigateBack: () -> Unit,
) {
    val viewModel: GroupWavelengthReceiveViewModel = viewModel(
        key = "wavelength-invoice:$chatRoomId:${GroupWalletStatementEvent.chainName(chain)}",
        factory = GroupWavelengthReceiveViewModel.factory(activeUserPublicKey, chatRoomId, chain, chatRepository, repository),
    )
    LaunchedEffect(true) { viewModel.initiate() }
    val notify = rememberNotifier(rememberCoroutineScope())
    val badAmount = stringResource(Res.string.group_wavelength_invoice_couldnt_ask)
    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.group_wavelength_invoice_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { NavigateBackButton(onNavigateBack) },
                actions = { GroupWavelengthGlossaryButton() },
            )
        }
    ) { padding ->
        ScreenStateTransition(viewModel.uiState) { uiState ->
            GroupWavelengthInvoiceContent(
                Modifier.padding(padding), uiState, notify,
                onAskInvoice = { viewModel.askInvoice(it, onFailure = { notify(badAmount) }) },
            )
        }
    }
}

@Composable
internal fun GroupWavelengthInvoiceContent(
    modifier: Modifier,
    state: GroupWavelengthReceiveUIState,
    notify: (String) -> Unit = {},
    onAskInvoice: (Long) -> Unit = {},
) {
    when (state) {
        GroupWavelengthReceiveUIState.Loading -> LoadingDataIndicator(modifier = modifier.fillMaxWidth())
        is GroupWavelengthReceiveUIState.Error -> ErrorState(modifier = modifier, message = state.message, onRetry = null)
        GroupWavelengthReceiveUIState.NotADepositor -> EmptyState(modifier = modifier.readableContent(), message = stringResource(Res.string.group_wavelength_receive_not_a_depositor), icon = Icons.Default.AccountBalanceWallet)
        GroupWavelengthReceiveUIState.NobodyRunsIt -> EmptyState(modifier = modifier.readableContent(), message = stringResource(Res.string.group_wavelength_receive_nobody_runs_it), icon = Icons.Default.Waves)
        GroupWavelengthReceiveUIState.AtTheCap -> EmptyState(modifier = modifier.readableContent(), message = stringResource(Res.string.group_wavelength_receive_at_cap), icon = Icons.Default.Waves)
        is GroupWavelengthReceiveUIState.Loaded -> Invoice(modifier, state, notify, onAskInvoice)
    }
}

@Composable
private fun Invoice(modifier: Modifier, state: GroupWavelengthReceiveUIState.Loaded, notify: (String) -> Unit, onAskInvoice: (Long) -> Unit) {
    RaiseBrightnessWhileVisible()
    // Worked out here because `invoiceItems` is a LazyListScope extension and cannot remember
    // anything; it decides which items exist, so it has to be known before they are emitted.
    val invoiceExpired = rememberInvoiceExpired(state.invoice)
    LazyColumn(
        modifier = modifier.readableContent().fillMaxSize().padding(MaterialTheme.spacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        invoiceItems(state, invoiceExpired, notify, onAskInvoice)
    }
}

/** The one place in this wallet a member takes somebody else's word for something, so it says so first. */
private fun LazyListScope.invoiceItems(state: GroupWavelengthReceiveUIState.Loaded, invoiceExpired: Boolean, notify: (String) -> Unit, onAskInvoice: (Long) -> Unit) {
    item { Text(stringResource(Res.string.group_wavelength_invoice_warning), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth()) }
    if (!state.enoughSeen) {
        item { Text(stringResource(Res.string.group_wavelength_invoice_devices_needed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth()) }
    }
    val invoice = state.invoice
    val paid = state.invoicePaid
    if (invoice != null) {
        if (paid != null) {
            item {
                PaymentReceived(
                    amount = paid.amount,
                    note = stringResource(Res.string.this_invoice_was_paid_and_should_not_be_paid_again),
                    onTheWordOf = state.operatorName.takeIf { paid.onTheOperatorsWord },
                )
            }
        } else if (invoiceExpired) {
            // The ask below already reads "ask for another", so the panel needs no action.
            item { InvoiceExpired(note = stringResource(Res.string.an_expired_invoice_cannot_be_paid_ask_for_another)) }
        } else {
            item { Text(stringResource(Res.string.group_wavelength_invoice_checked_s, Satoshi(state.invoiceAmountSat ?: 0).grouped()), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth()) }
            item { QRCodeView(invoice, stringResource(Res.string.qr_group_wavelength_invoice)) }
        }
        item { Copyable(invoice, Res.string.group_wavelength_invoice_copy, Res.string.group_wavelength_invoice_copied, notify) }
    }
    if (state.invoiceAsked) {
        item { Line(stringResource(Res.string.group_wavelength_invoice_waiting)) }
    } else {
        item { InvoiceAsk(state, isAnother = invoice != null, onAskInvoice) }
    }
}

@Composable
private fun InvoiceAsk(state: GroupWavelengthReceiveUIState.Loaded, isAnother: Boolean, onAskInvoice: (Long) -> Unit) {
    var amount by rememberSaveable { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)) {
        OutlinedTextField(
            value = amount,
            onValueChange = { typed -> amount = typed.filter(Char::isDigit) },
            label = { Text(stringResource(Res.string.group_wavelength_invoice_amount)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        val least = state.terms?.minVtxoSat
        val most = state.maxInvoiceSat
        if (least != null && most != null) {
            Text(stringResource(Res.string.group_wavelength_invoice_limits_s_s, Satoshi(least).grouped(), Satoshi(most).grouped()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(onClick = { amount.toLongOrNull()?.let(onAskInvoice) }, enabled = amount.toLongOrNull() != null) {
            Text(stringResource(if (isAnother) Res.string.group_wavelength_invoice_ask_another else Res.string.group_wavelength_invoice_ask))
        }
    }
}
