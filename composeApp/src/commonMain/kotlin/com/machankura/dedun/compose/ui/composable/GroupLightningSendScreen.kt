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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import bennu.composeapp.generated.resources.Res
import bennu.composeapp.generated.resources.amount_in_sats
import bennu.composeapp.generated.resources.ask_the_group_to_pay
import bennu.composeapp.generated.resources.couldn_t_propose_this
import bennu.composeapp.generated.resources.fee_limit_in_sats
import bennu.composeapp.generated.resources.left_empty_s_sats
import bennu.composeapp.generated.resources.lightning_invoice
import bennu.composeapp.generated.resources.nobody_runs_this_wallet
import bennu.composeapp.generated.resources.paying_asks_the_group_to_sign
import bennu.composeapp.generated.resources.sats
import bennu.composeapp.generated.resources.sats_are_whole_numbers
import bennu.composeapp.generated.resources.send
import bennu.composeapp.generated.resources.that_invoice_has_expired
import bennu.composeapp.generated.resources.that_invoice_is_for_another_network
import bennu.composeapp.generated.resources.that_invoice_names_no_amount
import bennu.composeapp.generated.resources.that_invoice_pays_this_wallet
import bennu.composeapp.generated.resources.that_is_more_sats_than_there_will_ever_be
import bennu.composeapp.generated.resources.that_isn_t_a_lightning_invoice
import bennu.composeapp.generated.resources.that_isn_t_an_amount_in_sats
import bennu.composeapp.generated.resources.the_invoice_names_the_amount_s_sats
import com.machankura.dedun.compose.managers.GroupLightningAuthorisations
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.GroupLightningRepository
import com.machankura.dedun.compose.text.GroupedSats
import com.machankura.dedun.compose.text.SatsInput
import com.machankura.dedun.compose.text.grouped
import com.machankura.dedun.compose.ui.composable.navigation.routes.FrostSigningRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.composable.widgets.EmptyState
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.composable.widgets.rememberNotifier
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.GroupLightningSendViewModel
import com.machankura.dedun.compose.ui.view.state.GroupLightningSendUIState
import com.machankura.dedun.compose.wallet.GroupLightningRollout
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Satoshi
import org.jetbrains.compose.resources.stringResource

/**
 * Proposing a payment from the group's Lightning wallet (`docs/group-lightning-wallet.md`, Phase 12). The
 * proposal opens a signing session; the operator's node pays once the group has signed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupLightningSendScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    chatRepository: ChatRepository,
    lightningRepository: GroupLightningRepository,
    onNavigateToRoute: (Route) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val viewModel: GroupLightningSendViewModel = viewModel(
        factory = GroupLightningSendViewModel.factory(activeUserPublicKey, chatRoomId, GroupLightningRollout.NETWORK, chatRepository, lightningRepository)
    )
    LaunchedEffect(true) { viewModel.initiate() }

    val notify = rememberNotifier(rememberCoroutineScope())
    val couldNotPropose = stringResource(Res.string.couldn_t_propose_this)
    val network = GroupWalletStatementEvent.chainName(GroupLightningRollout.NETWORK)

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.send), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { NavigateBackButton(onNavigateBack) }
            )
        }
    ) { padding ->
        ScreenStateTransition(viewModel.uiState) { uiState ->
            when (val state = uiState) {
                GroupLightningSendUIState.Loading -> LoadingDataIndicator(modifier = Modifier.fillMaxWidth().padding(padding))

                is GroupLightningSendUIState.Error -> ErrorState(modifier = Modifier.padding(padding), message = state.message, onRetry = null)

                GroupLightningSendUIState.Unavailable -> EmptyState(
                    modifier = Modifier.padding(padding).readableContent(),
                    message = stringResource(Res.string.nobody_runs_this_wallet),
                    icon = Icons.Default.Bolt,
                )

                is GroupLightningSendUIState.Form -> {
                    val preview = viewModel.preview()
                    val pay = preview?.getOrNull()
                    val invoiceProblem = (preview?.exceptionOrNull() as? GroupLightningAuthorisations.ProblemException)?.problem
                    val invoiceNamesAmount = viewModel.invoiceAmountMsat() != null
                    Column(
                        modifier = Modifier.padding(padding).readableContent().fillMaxSize().verticalScroll(rememberScrollState()).padding(MaterialTheme.spacing.screenMargin),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
                    ) {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            state = viewModel.invoiceField,
                            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 4),
                            label = { Text(stringResource(Res.string.lightning_invoice)) },
                            isError = invoiceProblem != null && invoiceProblem != GroupLightningAuthorisations.Problem.NeedsAmount,
                            supportingText = invoiceProblem?.let { problem -> { Text(problemWith(problem, network)) } }
                                ?: pay?.takeIf { invoiceNamesAmount }?.let { { Text(stringResource(Res.string.the_invoice_names_the_amount_s_sats, Satoshi(it.amountMsat / 1000).grouped())) } },
                        )
                        if (!invoiceNamesAmount) {
                            OutlinedTextField(
                                modifier = Modifier.fillMaxWidth(),
                                state = viewModel.amountField,
                                outputTransformation = GroupedSats,
                                lineLimits = TextFieldLineLimits.SingleLine,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                                label = { Text(stringResource(Res.string.amount_in_sats)) },
                                suffix = { Text(stringResource(Res.string.sats)) },
                                isError = amountProblem(viewModel.amount()) != null,
                                supportingText = amountProblem(viewModel.amount())?.let { { Text(it) } },
                            )
                        }
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            state = viewModel.maxFeeField,
                            outputTransformation = GroupedSats,
                            lineLimits = TextFieldLineLimits.SingleLine,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            label = { Text(stringResource(Res.string.fee_limit_in_sats)) },
                            suffix = { Text(stringResource(Res.string.sats)) },
                            isError = amountProblem(viewModel.maxFee()) != null,
                            supportingText = {
                                Text(
                                    amountProblem(viewModel.maxFee())
                                        ?: stringResource(Res.string.left_empty_s_sats, Satoshi(viewModel.defaultMaxFeeSat((pay?.amountMsat ?: 0) / 1000)).grouped())
                                )
                            },
                        )
                        Text(stringResource(Res.string.paying_asks_the_group_to_sign), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                viewModel.propose(
                                    onSession = { sessionId -> onNavigateToRoute(FrostSigningRoute(activeUserPublicKey = activeUserPublicKey, chatRoomId = chatRoomId, sessionId = sessionId)) },
                                    onFailure = { notify(couldNotPropose) },
                                )
                            },
                            enabled = pay != null && !viewModel.isActionPending.value,
                        ) {
                            Icon(Icons.Default.Bolt, contentDescription = Decorative)
                            Spacer(modifier = Modifier.width(MaterialTheme.spacing.relatedGap))
                            Text(stringResource(Res.string.ask_the_group_to_pay))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun problemWith(problem: GroupLightningAuthorisations.Problem, network: String): String = when (problem) {
    GroupLightningAuthorisations.Problem.Unreadable -> stringResource(Res.string.that_isn_t_a_lightning_invoice)
    GroupLightningAuthorisations.Problem.OtherNetwork -> stringResource(Res.string.that_invoice_is_for_another_network, network)
    GroupLightningAuthorisations.Problem.Expired -> stringResource(Res.string.that_invoice_has_expired)
    GroupLightningAuthorisations.Problem.NeedsAmount -> stringResource(Res.string.that_invoice_names_no_amount)
    GroupLightningAuthorisations.Problem.PaysTheGroup -> stringResource(Res.string.that_invoice_pays_this_wallet)
}

@Composable
private fun amountProblem(input: SatsInput): String? = when (input) {
    SatsInput.Empty, is SatsInput.Sats -> null
    SatsInput.NotWhole -> stringResource(Res.string.sats_are_whole_numbers)
    SatsInput.TooLarge -> stringResource(Res.string.that_is_more_sats_than_there_will_ever_be)
    else -> stringResource(Res.string.that_isn_t_an_amount_in_sats)
}
