package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.viewmodel.compose.viewModel
import com.machankura.dedun.compose.network.LightningAddressResolver
import com.machankura.dedun.compose.text.grouped
import com.machankura.dedun.compose.ui.composable.widgets.EmptyState
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.composable.widgets.rememberNotifier
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.WavelengthSendProblem
import com.machankura.dedun.compose.ui.view.model.WavelengthSendUIState
import com.machankura.dedun.compose.ui.view.model.WavelengthSendViewModel
import com.machankura.dedun.compose.wallet.wavelength.IdentityWavelength
import com.machankura.dedun.compose.wallet.wavelength.WavelengthWalletRollout
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.amount_in_sats
import dedun.composeapp.generated.resources.send
import dedun.composeapp.generated.resources.wavelength_not_turned_on
import dedun.composeapp.generated.resources.wavelength_send_above_cap_s_sats
import dedun.composeapp.generated.resources.wavelength_send_above_coin_limit
import dedun.composeapp.generated.resources.wavelength_send_address_failed
import dedun.composeapp.generated.resources.wavelength_send_amount_and_fee_s_s_s
import dedun.composeapp.generated.resources.wavelength_send_amount_fee_unknown_s
import dedun.composeapp.generated.resources.wavelength_send_change
import dedun.composeapp.generated.resources.wavelength_send_continue
import dedun.composeapp.generated.resources.wavelength_send_could_not_send
import dedun.composeapp.generated.resources.wavelength_send_leaves_s_sats
import dedun.composeapp.generated.resources.wavelength_send_needs_amount
import dedun.composeapp.generated.resources.wavelength_send_not_enough
import dedun.composeapp.generated.resources.wavelength_send_not_payable
import dedun.composeapp.generated.resources.wavelength_send_over_credit
import dedun.composeapp.generated.resources.wavelength_send_over_lightning
import dedun.composeapp.generated.resources.wavelength_send_onchain
import dedun.composeapp.generated.resources.wavelength_send_pay_to
import dedun.composeapp.generated.resources.wavelength_send_pay_to_hint
import dedun.composeapp.generated.resources.wavelength_send_payment_sent
import dedun.composeapp.generated.resources.wavelength_send_quote_expired
import dedun.composeapp.generated.resources.wavelength_send_review_title
import dedun.composeapp.generated.resources.wavelength_send_sending
import dedun.composeapp.generated.resources.wavelength_send_unrepresentable
import dedun.composeapp.generated.resources.wavelength_send_unsupported
import engineering.lightning.wavelength.Network
import engineering.lightning.wavelength.model.PrepareSendResult
import engineering.lightning.wavelength.model.SendRail
import fr.acinq.bitcoin.Satoshi
import org.jetbrains.compose.resources.stringResource

/**
 * Paying from the profile's wallet -- `docs/wavelength-wallet.md`, Phase 7. Paste, review, send.
 * Paste only: the app has no camera path yet. A sent payment goes back to the wallet, where it
 * shows as pending until it settles.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WavelengthSendScreen(
    identityWavelength: IdentityWavelength,
    resolver: LightningAddressResolver,
    onSent: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val viewModel: WavelengthSendViewModel = viewModel(factory = WavelengthSendViewModel.factory(identityWavelength, resolver))
    val notify = rememberNotifier(rememberCoroutineScope())
    val sent = stringResource(Res.string.wavelength_send_payment_sent)
    val state = viewModel.uiState
    LaunchedEffect(state) {
        if (state == WavelengthSendUIState.Sent) {
            notify(sent)
            onSent()
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.send)) },
                navigationIcon = { NavigateBackButton(onNavigateBack) },
            )
        },
    ) { padding ->
        WavelengthSendContent(
            state = state,
            modifier = Modifier.padding(padding),
            onDestinationChange = viewModel::onDestinationChange,
            onAmountChange = viewModel::onAmountChange,
            onReview = viewModel::review,
            onSend = viewModel::send,
            onChange = viewModel::change,
        )
    }
}

/** The screen's body, for a state, so a test can draw each one without a holder. */
@Composable
fun WavelengthSendContent(
    state: WavelengthSendUIState,
    modifier: Modifier = Modifier,
    onDestinationChange: (String) -> Unit,
    onAmountChange: (String) -> Unit,
    onReview: () -> Unit,
    onSend: () -> Unit,
    onChange: () -> Unit,
) {
    ScreenStateTransition(state) { uiState ->
        when (val current = uiState) {
            WavelengthSendUIState.NoWallet -> EmptyState(
                modifier = modifier.readableContent(),
                message = stringResource(Res.string.wavelength_not_turned_on),
                icon = Icons.Default.AccountBalanceWallet,
            )
            WavelengthSendUIState.Working, WavelengthSendUIState.Sent ->
                LoadingDataIndicator(modifier = modifier.fillMaxWidth(), text = stringResource(Res.string.wavelength_send_sending))
            is WavelengthSendUIState.Entering -> Column(
                modifier = modifier.readableContent().fillMaxSize().verticalScroll(rememberScrollState()).padding(MaterialTheme.spacing.containerPadding),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
            ) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = current.destination,
                    onValueChange = onDestinationChange,
                    label = { Text(stringResource(Res.string.wavelength_send_pay_to)) },
                    supportingText = { Text(stringResource(Res.string.wavelength_send_pay_to_hint)) },
                )
                if (current.needsAmount) {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = current.amount,
                        onValueChange = onAmountChange,
                        label = { Text(stringResource(Res.string.amount_in_sats)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                current.problem?.let { problem ->
                    Text(sendProblemText(problem), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                Button(onClick = onReview, enabled = current.destination.isNotBlank()) { Text(stringResource(Res.string.wavelength_send_continue)) }
            }
            is WavelengthSendUIState.Reviewing -> Column(
                modifier = modifier.readableContent().fillMaxSize().verticalScroll(rememberScrollState()).padding(MaterialTheme.spacing.containerPadding),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
            ) {
                Text(stringResource(Res.string.wavelength_send_review_title), style = MaterialTheme.typography.headlineSmall)
                current.quote.destinationSummary.ifEmpty { null }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                current.quote.invoiceDescription.ifEmpty { null }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Text(reviewAmounts(current.quote), style = MaterialTheme.typography.titleMedium)
                Text(railText(current.quote.rail), style = MaterialTheme.typography.bodyMedium)
                current.quote.warning.ifEmpty { null }?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                Text(stringResource(Res.string.wavelength_send_leaves_s_sats, Satoshi(current.remainingSat.coerceAtLeast(0)).grouped()), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)) {
                    TextButton(onClick = onChange) { Text(stringResource(Res.string.wavelength_send_change)) }
                    Button(onClick = onSend) { Text(stringResource(Res.string.send)) }
                }
            }
        }
    }
}

@Composable
private fun reviewAmounts(quote: PrepareSendResult): String {
    val amount = Satoshi(quote.amountSat).grouped()
    return if (quote.feeKnown) {
        stringResource(
            Res.string.wavelength_send_amount_and_fee_s_s_s,
            amount,
            Satoshi(quote.expectedFeeSat).grouped(),
            Satoshi(quote.amountSat + quote.expectedFeeSat).grouped(),
        )
    } else {
        stringResource(Res.string.wavelength_send_amount_fee_unknown_s, amount)
    }
}

/** The rail is named because the fees and the settlement time differ. */
@Composable
private fun railText(rail: SendRail?): String = stringResource(
    when (rail) {
        SendRail.ONCHAIN -> Res.string.wavelength_send_onchain
        SendRail.CREDIT -> Res.string.wavelength_send_over_credit
        else -> Res.string.wavelength_send_over_lightning
    }
)

@Composable
private fun sendProblemText(problem: WavelengthSendProblem): String = when (problem) {
    WavelengthSendProblem.NotPayable -> stringResource(Res.string.wavelength_send_not_payable)
    WavelengthSendProblem.Unsupported -> stringResource(Res.string.wavelength_send_unsupported)
    WavelengthSendProblem.Unrepresentable -> stringResource(Res.string.wavelength_send_unrepresentable)
    WavelengthSendProblem.NeedsAmount -> stringResource(Res.string.wavelength_send_needs_amount)
    WavelengthSendProblem.AboveCap -> stringResource(Res.string.wavelength_send_above_cap_s_sats, WavelengthWalletRollout.MAINNET_CAP.grouped())
    WavelengthSendProblem.AboveCoinLimit -> stringResource(Res.string.wavelength_send_above_coin_limit)
    WavelengthSendProblem.NotEnough -> stringResource(Res.string.wavelength_send_not_enough)
    WavelengthSendProblem.AddressFailed -> stringResource(Res.string.wavelength_send_address_failed)
    WavelengthSendProblem.QuoteExpired -> stringResource(Res.string.wavelength_send_quote_expired)
    WavelengthSendProblem.CouldNotSend -> stringResource(Res.string.wavelength_send_could_not_send)
}
