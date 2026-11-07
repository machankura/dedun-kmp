package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.viewmodel.compose.viewModel
import com.machankura.dedun.compose.text.grouped
import com.machankura.dedun.compose.ui.composable.widgets.EmptyState
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.composable.widgets.PaymentReceived
import com.machankura.dedun.compose.ui.composable.widgets.QRCodeView
import com.machankura.dedun.compose.ui.composable.widgets.rememberNotifier
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.WavelengthReceiveProblem
import com.machankura.dedun.compose.ui.view.model.WavelengthReceiveUIState
import com.machankura.dedun.compose.ui.view.model.WavelengthReceiveViewModel
import com.machankura.dedun.compose.wallet.wavelength.IdentityWavelength
import com.machankura.dedun.compose.wallet.wavelength.WavelengthWalletRollout
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.amount_in_sats
import dedun.composeapp.generated.resources.ask_for_another_address
import dedun.composeapp.generated.resources.ask_for_another_invoice
import dedun.composeapp.generated.resources.copied
import dedun.composeapp.generated.resources.copy
import dedun.composeapp.generated.resources.receive
import dedun.composeapp.generated.resources.this_address_was_paid_ask_for_another
import dedun.composeapp.generated.resources.this_invoice_was_paid_and_should_not_be_paid_again
import dedun.composeapp.generated.resources.wavelength_not_turned_on
import dedun.composeapp.generated.resources.wavelength_receive_above_balance_limit
import dedun.composeapp.generated.resources.wavelength_receive_above_cap_s_sats
import dedun.composeapp.generated.resources.wavelength_receive_above_coin_limit
import dedun.composeapp.generated.resources.wavelength_receive_could_not_make
import dedun.composeapp.generated.resources.wavelength_receive_credit
import dedun.composeapp.generated.resources.wavelength_receive_keep_open
import dedun.composeapp.generated.resources.wavelength_receive_lightning
import dedun.composeapp.generated.resources.wavelength_receive_make_address
import dedun.composeapp.generated.resources.wavelength_receive_make_invoice
import dedun.composeapp.generated.resources.wavelength_receive_making
import dedun.composeapp.generated.resources.wavelength_receive_needs_amount
import dedun.composeapp.generated.resources.wavelength_receive_onchain
import dedun.composeapp.generated.resources.wavelength_receive_onchain_explainer
import dedun.composeapp.generated.resources.wavelength_receive_uncertain
import org.jetbrains.compose.resources.stringResource

/**
 * Being paid into the profile's wallet -- `docs/wavelength-wallet.md`, Phase 6. Two tabs, since
 * the daemon has no single request that carries both: an invoice for an amount, and an address to
 * board from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WavelengthReceiveScreen(
    identityWavelength: IdentityWavelength,
    onNavigateBack: () -> Unit,
) {
    val viewModel: WavelengthReceiveViewModel = viewModel(factory = WavelengthReceiveViewModel.factory(identityWavelength))
    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.receive)) },
                navigationIcon = { NavigateBackButton(onNavigateBack) },
            )
        },
    ) { padding ->
        WavelengthReceiveContent(
            state = viewModel.uiState,
            modifier = Modifier.padding(padding),
            onAmountChange = viewModel::onAmountChange,
            onMakeInvoice = viewModel::makeInvoice,
            onMakeAddress = viewModel::makeAddress,
        )
    }
}

/** The screen's body, for a state, so a test can draw each one without a holder. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WavelengthReceiveContent(
    state: WavelengthReceiveUIState,
    modifier: Modifier = Modifier,
    onAmountChange: (String) -> Unit,
    onMakeInvoice: () -> Unit,
    onMakeAddress: () -> Unit,
) {
    if (!state.hasWallet) {
        EmptyState(
            modifier = modifier.readableContent(),
            message = stringResource(Res.string.wavelength_not_turned_on),
            icon = Icons.Default.AccountBalanceWallet,
        )
        return
    }
    var tab by remember { mutableIntStateOf(0) }
    Column(modifier = modifier.fillMaxSize().readableContent()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(Res.string.wavelength_receive_lightning)) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(Res.string.wavelength_receive_onchain)) })
        }
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(MaterialTheme.spacing.containerPadding),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
        ) {
            if (tab == 0) LightningTab(state, onAmountChange, onMakeInvoice) else OnchainTab(state, onMakeAddress)
            state.problem?.let { problem ->
                Text(receiveProblemText(state, problem), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            if (state.working) LoadingDataIndicator(modifier = Modifier.fillMaxWidth(), text = stringResource(Res.string.wavelength_receive_making))
        }
    }
}

@Composable
private fun LightningTab(state: WavelengthReceiveUIState, onAmountChange: (String) -> Unit, onMakeInvoice: () -> Unit) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = state.amount,
        onValueChange = onAmountChange,
        label = { Text(stringResource(Res.string.amount_in_sats)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
    Button(onClick = onMakeInvoice, enabled = !state.working) {
        Text(stringResource(if (state.invoicePaid != null) Res.string.ask_for_another_invoice else Res.string.wavelength_receive_make_invoice))
    }
    state.invoice?.let { invoice ->
        val paid = state.invoicePaid
        if (paid != null) {
            // The button above is the way to another invoice, so the panel needs no action of its own.
            PaymentReceived(amount = paid, note = stringResource(Res.string.this_invoice_was_paid_and_should_not_be_paid_again))
            RequestText(invoice)
        } else {
            RequestCode(invoice)
            if (state.invoiceIsCredit) Text(stringResource(Res.string.wavelength_receive_credit), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(Res.string.wavelength_receive_keep_open), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun OnchainTab(state: WavelengthReceiveUIState, onMakeAddress: () -> Unit) {
    Text(stringResource(Res.string.wavelength_receive_onchain_explainer), style = MaterialTheme.typography.bodyMedium)
    val address = state.address
    if (address == null) {
        Button(onClick = onMakeAddress, enabled = !state.working) { Text(stringResource(Res.string.wavelength_receive_make_address)) }
        return
    }
    val paid = state.addressPaid
    if (paid != null) {
        PaymentReceived(
            amount = paid,
            note = stringResource(Res.string.this_address_was_paid_ask_for_another),
            action = {
                Button(onClick = onMakeAddress, enabled = !state.working) { Text(stringResource(Res.string.ask_for_another_address)) }
            },
        )
        RequestText(address)
    } else {
        RequestCode(address)
    }
}

@Composable
private fun RequestCode(text: String) {
    QRCodeView(text)
    RequestText(text)
}

/**
 * The request as text, with a copy button: what a paid request keeps when its QR code goes
 * (`docs/paid-requests.md`, D6). Still worth reading against what a payer says they paid, and
 * never worth scanning again.
 */
@Composable
private fun RequestText(text: String) {
    val clipboard = LocalClipboardManager.current
    val notify = rememberNotifier(rememberCoroutineScope())
    val copied = stringResource(Res.string.copied)
    Text(text, style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { clipboard.setText(AnnotatedString(text)); notify(copied) }) { Text(stringResource(Res.string.copy)) }
}

@Composable
private fun receiveProblemText(state: WavelengthReceiveUIState, problem: WavelengthReceiveProblem): String = when (problem) {
    WavelengthReceiveProblem.NeedsAmount -> stringResource(Res.string.wavelength_receive_needs_amount)
    WavelengthReceiveProblem.AboveCap -> stringResource(
        Res.string.wavelength_receive_above_cap_s_sats,
        (WavelengthWalletRollout.capFor(state.network) ?: WavelengthWalletRollout.MAINNET_CAP).grouped(),
    )
    WavelengthReceiveProblem.AboveCoinLimit -> stringResource(Res.string.wavelength_receive_above_coin_limit)
    WavelengthReceiveProblem.AboveBalanceLimit -> stringResource(Res.string.wavelength_receive_above_balance_limit)
    WavelengthReceiveProblem.Uncertain -> stringResource(Res.string.wavelength_receive_uncertain)
    WavelengthReceiveProblem.CouldNotMake -> stringResource(Res.string.wavelength_receive_could_not_make)
}
