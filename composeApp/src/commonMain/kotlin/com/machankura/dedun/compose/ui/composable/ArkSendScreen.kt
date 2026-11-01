package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.machankura.dedun.compose.text.GroupedSats
import com.machankura.dedun.compose.text.SatsInput
import com.machankura.dedun.compose.text.grouped
import com.machankura.dedun.compose.ui.composable.widgets.EmptyState
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.composable.widgets.rememberNotifier
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.ArkSendProblem
import com.machankura.dedun.compose.ui.view.model.ArkSendUIState
import com.machankura.dedun.compose.ui.view.model.ArkSendViewModel
import com.machankura.dedun.compose.wallet.ark.ArkRail
import com.machankura.dedun.compose.wallet.ark.ArkWalletRollout
import com.machankura.dedun.compose.wallet.ark.IdentityArk
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.amount_in_sats
import dedun.composeapp.generated.resources.ark_send_a_note_for_them
import dedun.composeapp.generated.resources.ark_send_change
import dedun.composeapp.generated.resources.ark_send_continue
import dedun.composeapp.generated.resources.ark_send_could_not_send
import dedun.composeapp.generated.resources.ark_send_could_not_work_out_the_fee
import dedun.composeapp.generated.resources.ark_send_leaves_s_sats
import dedun.composeapp.generated.resources.ark_send_not_enough
import dedun.composeapp.generated.resources.ark_send_nothing_to_pay
import dedun.composeapp.generated.resources.ark_send_onchain
import dedun.composeapp.generated.resources.ark_send_over_ark
import dedun.composeapp.generated.resources.ark_send_over_lightning
import dedun.composeapp.generated.resources.ark_send_pay_to
import dedun.composeapp.generated.resources.ark_send_pay_to_hint
import dedun.composeapp.generated.resources.ark_send_payment_sent
import dedun.composeapp.generated.resources.ark_send_s_and_a_fee_of_s_s_in_all
import dedun.composeapp.generated.resources.ark_send_sending
import dedun.composeapp.generated.resources.ark_send_the_request_names_s_sats
import dedun.composeapp.generated.resources.ark_wallet_mainnet_cap_s_sats
import dedun.composeapp.generated.resources.ark_wallet_not_turned_on
import dedun.composeapp.generated.resources.sats
import dedun.composeapp.generated.resources.sats_are_whole_numbers
import dedun.composeapp.generated.resources.send
import dedun.composeapp.generated.resources.that_is_more_sats_than_there_will_ever_be
import dedun.composeapp.generated.resources.that_isn_t_an_amount_in_sats
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import org.jetbrains.compose.resources.stringResource

/**
 * Paying from the profile's Ark wallet -- `docs/bark-wallet.md`, Phase 6. Paste, review,
 * send. Paste only: the app has no camera path yet. A sent payment goes back to the
 * wallet, where it shows as pending until its movement settles.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArkSendScreen(
    identityArk: IdentityArk,
    onSent: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val viewModel: ArkSendViewModel = viewModel(factory = ArkSendViewModel.factory(identityArk))
    val notify = rememberNotifier(rememberCoroutineScope())
    val sent = stringResource(Res.string.ark_send_payment_sent)
    val state = viewModel.uiState
    LaunchedEffect(state) {
        if (state is ArkSendUIState.Sent) {
            notify(sent)
            onSent()
        }
    }
    // A changed amount is estimated again once typing pauses.
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    LaunchedEffect(viewModel) {
        snapshotFlow { viewModel.amountField.text.toString() }.drop(1).debounce(400).collect { viewModel.reestimate() }
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
        ArkSendContent(state, viewModel, Modifier.padding(padding))
    }
}

@Composable
internal fun ArkSendContent(state: ArkSendUIState, viewModel: ArkSendViewModel, modifier: Modifier) {
    ScreenStateTransition(state) { uiState ->
        when (val current = uiState) {
            ArkSendUIState.NoWallet -> EmptyState(
                modifier = modifier.readableContent(),
                message = stringResource(Res.string.ark_wallet_not_turned_on),
                icon = Icons.Default.AccountBalanceWallet,
            )
            ArkSendUIState.Working, is ArkSendUIState.Sent -> LoadingDataIndicator(modifier = modifier.fillMaxWidth(), text = stringResource(Res.string.ark_send_sending))
            is ArkSendUIState.Failed -> ErrorState(modifier = modifier, message = stringResource(Res.string.ark_send_could_not_send), onRetry = viewModel::change)
            is ArkSendUIState.Entering -> Column(
                modifier = modifier.readableContent().fillMaxSize().verticalScroll(rememberScrollState()).padding(MaterialTheme.spacing.screenMargin),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
            ) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    state = viewModel.requestField,
                    lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 4),
                    label = { Text(stringResource(Res.string.ark_send_pay_to)) },
                    isError = current.problem != null,
                    supportingText = {
                        Text(if (current.problem == ArkSendProblem.NothingToPay) stringResource(Res.string.ark_send_nothing_to_pay) else stringResource(Res.string.ark_send_pay_to_hint))
                    },
                )
                Button(modifier = Modifier.fillMaxWidth(), onClick = viewModel::parse) { Text(stringResource(Res.string.ark_send_continue)) }
            }
            is ArkSendUIState.Review -> ArkSendReview(current, viewModel, modifier)
        }
    }
}

@Composable
private fun ArkSendReview(review: ArkSendUIState.Review, viewModel: ArkSendViewModel, modifier: Modifier) {
    val amountProblem = when (viewModel.amount()) {
        SatsInput.Empty, is SatsInput.Sats -> null
        SatsInput.NotWhole -> stringResource(Res.string.sats_are_whole_numbers)
        SatsInput.TooLarge -> stringResource(Res.string.that_is_more_sats_than_there_will_ever_be)
        else -> stringResource(Res.string.that_isn_t_an_amount_in_sats)
    }
    val problem = when (review.problem) {
        ArkSendProblem.AboveCap -> stringResource(Res.string.ark_wallet_mainnet_cap_s_sats, ArkWalletRollout.MAINNET_CAP.grouped())
        ArkSendProblem.NotEnough -> stringResource(Res.string.ark_send_not_enough)
        ArkSendProblem.NoFee -> stringResource(Res.string.ark_send_could_not_work_out_the_fee)
        else -> null
    }
    Column(
        modifier = modifier.readableContent().fillMaxSize().verticalScroll(rememberScrollState()).padding(MaterialTheme.spacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
    ) {
        Text(review.option.method.value, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        review.request.description?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        Text(
            stringResource(
                when (review.option.method.rail) {
                    ArkRail.Lightning -> Res.string.ark_send_over_lightning
                    ArkRail.Onchain -> Res.string.ark_send_onchain
                    else -> Res.string.ark_send_over_ark
                }
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val fixed = review.request.amount
        if (fixed != null) {
            Text(stringResource(Res.string.ark_send_the_request_names_s_sats, fixed.grouped()), style = MaterialTheme.typography.bodyMedium)
        } else {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                state = viewModel.amountField,
                outputTransformation = GroupedSats,
                lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                label = { Text(stringResource(Res.string.amount_in_sats)) },
                suffix = { Text(stringResource(Res.string.sats)) },
                isError = amountProblem != null,
                supportingText = amountProblem?.let { { Text(it) } },
            )
        }
        if (ArkSendViewModel.takesComment(review.option.method)) {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                state = viewModel.commentField,
                lineLimits = TextFieldLineLimits.SingleLine,
                label = { Text(stringResource(Res.string.ark_send_a_note_for_them)) },
            )
        }
        review.fee?.let { fee ->
            Text(stringResource(Res.string.ark_send_s_and_a_fee_of_s_s_in_all, fee.net.grouped(), fee.fee.grouped(), fee.gross.grouped()), style = MaterialTheme.typography.bodyLarge)
            if (review.problem == null) {
                Text(stringResource(Res.string.ark_send_leaves_s_sats, (review.spendable - fee.gross).grouped()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        problem?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        Button(modifier = Modifier.fillMaxWidth(), onClick = viewModel::send, enabled = review.canSend) { Text(stringResource(Res.string.send)) }
        TextButton(modifier = Modifier.fillMaxWidth(), onClick = viewModel::change) { Text(stringResource(Res.string.ark_send_change)) }
    }
}
