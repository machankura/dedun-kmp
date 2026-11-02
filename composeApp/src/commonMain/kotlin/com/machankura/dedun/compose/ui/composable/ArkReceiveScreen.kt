package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.viewmodel.compose.viewModel
import com.machankura.dedun.compose.text.GroupedSats
import com.machankura.dedun.compose.text.SatsInput
import com.machankura.dedun.compose.text.grouped
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.composable.widgets.EmptyState
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.composable.widgets.QRCodeView
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.composable.widgets.rememberNotifier
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.ArkReceiveUIState
import com.machankura.dedun.compose.ui.view.model.ArkReceiveViewModel
import com.machankura.dedun.compose.wallet.ark.ArkReceiveRequest
import com.machankura.dedun.compose.wallet.ark.ArkWalletRollout
import com.machankura.dedun.compose.wallet.ark.IdentityArk
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.amount_in_sats
import dedun.composeapp.generated.resources.ark_receive_above_cap_s_sats
import dedun.composeapp.generated.resources.ark_receive_all
import dedun.composeapp.generated.resources.ark_receive_ark
import dedun.composeapp.generated.resources.ark_receive_ask_for_this_amount
import dedun.composeapp.generated.resources.ark_receive_could_not_make_a_request
import dedun.composeapp.generated.resources.ark_receive_keep_open
import dedun.composeapp.generated.resources.ark_receive_lightning
import dedun.composeapp.generated.resources.ark_receive_leave_empty_ark_or_onchain
import dedun.composeapp.generated.resources.ark_receive_lightning_fee_s_sats
import dedun.composeapp.generated.resources.ark_receive_lightning_needs_an_amount
import dedun.composeapp.generated.resources.ark_receive_onchain
import dedun.composeapp.generated.resources.ark_receive_onchain_arrives_onchain
import dedun.composeapp.generated.resources.ark_wallet_not_turned_on
import dedun.composeapp.generated.resources.copied
import dedun.composeapp.generated.resources.copy
import dedun.composeapp.generated.resources.receive
import dedun.composeapp.generated.resources.received_s_sats
import dedun.composeapp.generated.resources.sats
import dedun.composeapp.generated.resources.sats_are_whole_numbers
import dedun.composeapp.generated.resources.that_is_more_sats_than_there_will_ever_be
import dedun.composeapp.generated.resources.that_isn_t_an_amount_in_sats
import dedun.composeapp.generated.resources.what_it_is_for
import org.jetbrains.compose.resources.stringResource

/** Which form of the request the QR code and *Copy* carry. */
private enum class ReceiveForm { All, Ark, Lightning, Onchain }

/**
 * Being paid into the profile's Ark wallet -- `docs/bark-wallet.md`, Phase 5. One QR code
 * carries every way in; the tabs show each on its own, for a payer whose wallet reads
 * only one. A Lightning payment is claimed by this device, so the screen says to keep
 * the app open until it arrives.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArkReceiveScreen(
    identityArk: IdentityArk,
    onNavigateBack: () -> Unit,
) {
    val viewModel: ArkReceiveViewModel = viewModel(factory = ArkReceiveViewModel.factory(identityArk))
    val notify = rememberNotifier(rememberCoroutineScope())
    val receivedText = (viewModel.uiState as? ArkReceiveUIState.Ready)?.received?.let { stringResource(Res.string.received_s_sats, it.amount.grouped()) }
    LaunchedEffect(receivedText) { receivedText?.let(notify) }

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.receive)) },
                navigationIcon = { NavigateBackButton(onNavigateBack) },
            )
        },
    ) { padding ->
        ScreenStateTransition(viewModel.uiState) { uiState ->
            when (val state = uiState) {
                ArkReceiveUIState.Loading -> LoadingDataIndicator(modifier = Modifier.fillMaxWidth().padding(padding))
                ArkReceiveUIState.NoWallet -> EmptyState(
                    modifier = Modifier.padding(padding).readableContent(),
                    message = stringResource(Res.string.ark_wallet_not_turned_on),
                    icon = Icons.Default.AccountBalanceWallet,
                )
                is ArkReceiveUIState.Failed -> ErrorState(
                    modifier = Modifier.padding(padding),
                    message = stringResource(Res.string.ark_receive_could_not_make_a_request),
                    onRetry = viewModel::request,
                )
                is ArkReceiveUIState.Ready -> ArkReceiveReady(state, viewModel, Modifier.padding(padding))
            }
        }
    }
}

@Composable
internal fun ArkReceiveReady(state: ArkReceiveUIState.Ready, viewModel: ArkReceiveViewModel, modifier: Modifier) {
    val clipboard = LocalClipboardManager.current
    val notify = rememberNotifier(rememberCoroutineScope())
    val copied = stringResource(Res.string.copied)
    val request = state.request
    val forms = listOfNotNull(
        ReceiveForm.All,
        ReceiveForm.Ark.takeIf { request.arkAddress != null },
        ReceiveForm.Lightning.takeIf { request.bolt11 != null },
        ReceiveForm.Onchain.takeIf { request.onchainAddress != null },
    )
    var form by rememberSaveable { mutableStateOf(ReceiveForm.All) }
    if (form !in forms) form = ReceiveForm.All
    val text = textOf(request, form)

    val amountProblem = when (viewModel.amount()) {
        SatsInput.Empty, is SatsInput.Sats -> null
        SatsInput.NotWhole -> stringResource(Res.string.sats_are_whole_numbers)
        SatsInput.TooLarge -> stringResource(Res.string.that_is_more_sats_than_there_will_ever_be)
        else -> stringResource(Res.string.that_isn_t_an_amount_in_sats)
    }
    val capProblem = ArkWalletRollout.MAINNET_CAP.takeIf { viewModel.aboveCap }?.let { stringResource(Res.string.ark_receive_above_cap_s_sats, it.grouped()) }

    Column(
        modifier = modifier.readableContent().fillMaxSize().verticalScroll(rememberScrollState()).padding(MaterialTheme.spacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
    ) {
        state.received?.let { received ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
            ) {
                Text(
                    stringResource(Res.string.received_s_sats, received.amount.grouped()),
                    modifier = Modifier.padding(MaterialTheme.spacing.containerPadding),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        SecondaryTabRow(selectedTabIndex = forms.indexOf(form)) {
            forms.forEach { candidate ->
                Tab(
                    selected = candidate == form,
                    onClick = { form = candidate },
                    text = { Text(stringResource(labelOf(candidate))) },
                )
            }
        }

        QRCodeView(text)
        Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        FilledTonalButton(modifier = Modifier.fillMaxWidth(), onClick = { clipboard.setText(AnnotatedString(text)); notify(copied) }) {
            Icon(Icons.Default.ContentCopy, contentDescription = Decorative)
            Spacer(modifier = Modifier.width(MaterialTheme.spacing.relatedGap))
            Text(stringResource(Res.string.copy))
        }

        // Bark makes an invoice only for an amount, so a request without one has no Lightning
        // choice to offer: said here, where that choice would be, rather than left to be noticed.
        if (request.bolt11 == null && state.received == null) {
            Text(stringResource(Res.string.ark_receive_lightning_needs_an_amount), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (request.bolt11 != null && state.received == null) {
            Text(stringResource(Res.string.ark_receive_keep_open), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.lightningFee?.takeIf { it.fee.sat > 0 }?.let { fee ->
            Text(stringResource(Res.string.ark_receive_lightning_fee_s_sats, fee.fee.grouped()), style = MaterialTheme.typography.bodyMedium)
        }
        if (form == ReceiveForm.Onchain) {
            Text(stringResource(Res.string.ark_receive_onchain_arrives_onchain), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            state = viewModel.amountField,
            outputTransformation = GroupedSats,
            lineLimits = TextFieldLineLimits.SingleLine,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            label = { Text(stringResource(Res.string.amount_in_sats)) },
            suffix = { Text(stringResource(Res.string.sats)) },
            isError = amountProblem != null || capProblem != null,
            supportingText = { Text(amountProblem ?: capProblem ?: stringResource(Res.string.ark_receive_leave_empty_ark_or_onchain)) },
        )
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            state = viewModel.descriptionField,
            lineLimits = TextFieldLineLimits.SingleLine,
            label = { Text(stringResource(Res.string.what_it_is_for)) },
        )
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = viewModel::request,
            enabled = amountProblem == null && !viewModel.isActionPending,
        ) {
            Text(stringResource(Res.string.ark_receive_ask_for_this_amount))
        }
    }
}

private fun textOf(request: ArkReceiveRequest, form: ReceiveForm): String = when (form) {
    ReceiveForm.All -> request.uri
    ReceiveForm.Ark -> request.arkAddress ?: request.uri
    ReceiveForm.Lightning -> request.bolt11 ?: request.uri
    ReceiveForm.Onchain -> request.onchainAddress ?: request.uri
}

private fun labelOf(form: ReceiveForm) = when (form) {
    ReceiveForm.All -> Res.string.ark_receive_all
    ReceiveForm.Ark -> Res.string.ark_receive_ark
    ReceiveForm.Lightning -> Res.string.ark_receive_lightning
    ReceiveForm.Onchain -> Res.string.ark_receive_onchain
}
