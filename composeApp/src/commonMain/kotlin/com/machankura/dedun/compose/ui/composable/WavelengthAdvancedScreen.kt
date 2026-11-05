package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.machankura.dedun.compose.text.grouped
import com.machankura.dedun.compose.ui.composable.widgets.EmptyState
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.WavelengthAdvancedNotice
import com.machankura.dedun.compose.ui.view.model.WavelengthAdvancedProblem
import com.machankura.dedun.compose.ui.view.model.WavelengthAdvancedUIState
import com.machankura.dedun.compose.ui.view.model.WavelengthAdvancedViewModel
import com.machankura.dedun.compose.ui.view.model.WavelengthExitStep
import com.machankura.dedun.compose.wallet.wavelength.IdentityWavelength
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.cancel
import dedun.composeapp.generated.resources.s_sats
import dedun.composeapp.generated.resources.wavelength_advanced
import dedun.composeapp.generated.resources.wavelength_advanced_address
import dedun.composeapp.generated.resources.wavelength_advanced_coin_s
import dedun.composeapp.generated.resources.wavelength_advanced_coins
import dedun.composeapp.generated.resources.wavelength_advanced_could_not_exit
import dedun.composeapp.generated.resources.wavelength_advanced_could_not_move
import dedun.composeapp.generated.resources.wavelength_advanced_could_not_read
import dedun.composeapp.generated.resources.wavelength_advanced_could_not_sweep
import dedun.composeapp.generated.resources.wavelength_advanced_emergency
import dedun.composeapp.generated.resources.wavelength_advanced_emergency_blocked_s_s
import dedun.composeapp.generated.resources.wavelength_advanced_emergency_explainer
import dedun.composeapp.generated.resources.wavelength_advanced_emergency_question
import dedun.composeapp.generated.resources.wavelength_advanced_emergency_warning
import dedun.composeapp.generated.resources.wavelength_advanced_exit_claiming
import dedun.composeapp.generated.resources.wavelength_advanced_exit_done
import dedun.composeapp.generated.resources.wavelength_advanced_exit_failed
import dedun.composeapp.generated.resources.wavelength_advanced_exit_s
import dedun.composeapp.generated.resources.wavelength_advanced_exit_starting
import dedun.composeapp.generated.resources.wavelength_advanced_exit_waiting
import dedun.composeapp.generated.resources.wavelength_advanced_exit_started
import dedun.composeapp.generated.resources.wavelength_advanced_exit_stopped
import dedun.composeapp.generated.resources.wavelength_advanced_exits
import dedun.composeapp.generated.resources.wavelength_advanced_exits_note
import dedun.composeapp.generated.resources.wavelength_advanced_explainer
import dedun.composeapp.generated.resources.wavelength_advanced_look
import dedun.composeapp.generated.resources.wavelength_advanced_look_explainer
import dedun.composeapp.generated.resources.wavelength_advanced_move_all
import dedun.composeapp.generated.resources.wavelength_advanced_move_question
import dedun.composeapp.generated.resources.wavelength_advanced_move_started
import dedun.composeapp.generated.resources.wavelength_advanced_needs_address
import dedun.composeapp.generated.resources.wavelength_advanced_no_coins
import dedun.composeapp.generated.resources.wavelength_advanced_start_exit
import dedun.composeapp.generated.resources.wavelength_advanced_sweep
import dedun.composeapp.generated.resources.wavelength_advanced_sweep_question
import dedun.composeapp.generated.resources.wavelength_advanced_swept
import dedun.composeapp.generated.resources.wavelength_not_turned_on
import dedun.composeapp.generated.resources.wavelength_send_amount_and_fee_s_s_s
import dedun.composeapp.generated.resources.wavelength_advanced_go_ahead
import engineering.lightning.wavelength.model.ExitJobStatus
import fr.acinq.bitcoin.Satoshi
import org.jetbrains.compose.resources.stringResource

/**
 * Leaving Ark -- `docs/wavelength-wallet.md`, Phase 9: the coins as they stand, everything moved
 * onchain with the server's help, the emergency exit without it, and the onchain side swept.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WavelengthAdvancedScreen(
    identityWavelength: IdentityWavelength,
    onNavigateBack: () -> Unit,
) {
    val viewModel: WavelengthAdvancedViewModel = viewModel(factory = WavelengthAdvancedViewModel.factory(identityWavelength))
    LaunchedEffect(true) { viewModel.load() }
    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.wavelength_advanced)) },
                navigationIcon = { NavigateBackButton(onNavigateBack) },
            )
        },
    ) { padding ->
        WavelengthAdvancedContent(
            state = viewModel.uiState,
            modifier = Modifier.padding(padding),
            onAddressChange = viewModel::onAddressChange,
            onMoveAll = viewModel::priceMoveAll,
            onEmergency = viewModel::planEmergency,
            onSweep = viewModel::priceSweep,
            onConfirm = viewModel::confirm,
            onDismiss = viewModel::dismissStep,
            onLookForMoreCoins = {
                viewModel.lookForMoreCoins()
                onNavigateBack()
            },
        )
    }
}

/** The screen's body, for a state, so a test can draw each one without a holder. */
@Composable
fun WavelengthAdvancedContent(
    state: WavelengthAdvancedUIState,
    modifier: Modifier = Modifier,
    onAddressChange: (String) -> Unit,
    onMoveAll: () -> Unit,
    onEmergency: () -> Unit,
    onSweep: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onLookForMoreCoins: () -> Unit = {},
) {
    ScreenStateTransition(state) { uiState ->
        when (val current = uiState) {
            WavelengthAdvancedUIState.NoWallet -> EmptyState(
                modifier = modifier.readableContent(),
                message = stringResource(Res.string.wavelength_not_turned_on),
                icon = Icons.Default.AccountBalanceWallet,
            )
            WavelengthAdvancedUIState.Loading -> LoadingDataIndicator(modifier = modifier.fillMaxWidth())
            is WavelengthAdvancedUIState.Loaded -> {
                current.step?.let { step -> StepDialog(step, onConfirm, onDismiss) }
                Column(
                    modifier = modifier.readableContent().fillMaxSize().verticalScroll(rememberScrollState()).padding(MaterialTheme.spacing.containerPadding),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
                ) {
                    Text(stringResource(Res.string.wavelength_advanced_explainer), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = current.address,
                        onValueChange = onAddressChange,
                        label = { Text(stringResource(Res.string.wavelength_advanced_address)) },
                        singleLine = true,
                    )
                    current.problem?.let { Text(problemText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    current.notice?.let { Text(noticeText(it), style = MaterialTheme.typography.bodyMedium) }
                    Button(modifier = Modifier.fillMaxWidth(), onClick = onMoveAll, enabled = !current.working && current.hasCoins) {
                        Text(stringResource(Res.string.wavelength_advanced_move_all))
                    }
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onSweep, enabled = !current.working) {
                        Text(stringResource(Res.string.wavelength_advanced_sweep))
                    }
                    Text(stringResource(Res.string.wavelength_advanced_emergency_explainer), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onEmergency, enabled = !current.working && current.hasCoins) {
                        Text(stringResource(Res.string.wavelength_advanced_emergency))
                    }
                    if (current.working) LoadingDataIndicator(modifier = Modifier.fillMaxWidth())

                    if (current.exits.isNotEmpty()) {
                        Text(stringResource(Res.string.wavelength_advanced_exits), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(Res.string.wavelength_advanced_exits_note), style = MaterialTheme.typography.bodyMedium)
                        current.exits.forEach { exit ->
                            ListItem(
                                headlineContent = { Text(stringResource(Res.string.wavelength_advanced_exit_s, exit.outpoint.take(12))) },
                                supportingContent = { Text(exitStatusText(exit.status)) },
                            )
                        }
                    }

                    Text(stringResource(Res.string.wavelength_advanced_look_explainer), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onLookForMoreCoins, enabled = !current.working) {
                        Text(stringResource(Res.string.wavelength_advanced_look))
                    }

                    Text(stringResource(Res.string.wavelength_advanced_coins), style = MaterialTheme.typography.titleMedium)
                    if (current.coins.isEmpty()) {
                        Text(stringResource(Res.string.wavelength_advanced_no_coins), style = MaterialTheme.typography.bodyMedium)
                    }
                    current.coins.forEach { coin ->
                        ListItem(
                            headlineContent = { Text(stringResource(Res.string.s_sats, Satoshi(coin.amountSat).grouped())) },
                            supportingContent = {
                                Text(stringResource(Res.string.wavelength_advanced_coin_s, coin.batchExpiry.toString()))
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun exitStatusText(status: ExitJobStatus?): String = stringResource(
    when (status) {
        ExitJobStatus.CSV_PENDING -> Res.string.wavelength_advanced_exit_waiting
        ExitJobStatus.SWEEPING -> Res.string.wavelength_advanced_exit_claiming
        ExitJobStatus.COMPLETED -> Res.string.wavelength_advanced_exit_done
        ExitJobStatus.FAILED -> Res.string.wavelength_advanced_exit_failed
        else -> Res.string.wavelength_advanced_exit_starting
    }
)

@Composable
private fun StepDialog(step: WavelengthExitStep, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val canGoAhead = when (step) {
        is WavelengthExitStep.Emergency -> step.plan.canStart
        is WavelengthExitStep.Sweep -> step.preview.canBroadcast
        is WavelengthExitStep.MoveAll -> true
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    when (step) {
                        is WavelengthExitStep.MoveAll -> Res.string.wavelength_advanced_move_question
                        is WavelengthExitStep.Emergency -> Res.string.wavelength_advanced_emergency_question
                        is WavelengthExitStep.Sweep -> Res.string.wavelength_advanced_sweep_question
                    }
                )
            )
        },
        text = {
            when (step) {
                is WavelengthExitStep.MoveAll -> Text(
                    stringResource(
                        Res.string.wavelength_send_amount_and_fee_s_s_s,
                        Satoshi(step.quote.amountSat).grouped(),
                        Satoshi(step.quote.expectedFeeSat).grouped(),
                        Satoshi(step.quote.amountSat + step.quote.expectedFeeSat).grouped(),
                    )
                )
                is WavelengthExitStep.Emergency -> Text(
                    if (step.plan.canStart) {
                        stringResource(Res.string.wavelength_advanced_emergency_warning)
                    } else {
                        stringResource(
                            Res.string.wavelength_advanced_emergency_blocked_s_s,
                            Satoshi(step.plan.totalFundingShortfallSat).grouped(),
                            step.fundingAddress.orEmpty(),
                        )
                    }
                )
                is WavelengthExitStep.Sweep -> Text(
                    if (step.preview.canBroadcast) {
                        stringResource(
                            Res.string.wavelength_send_amount_and_fee_s_s_s,
                            Satoshi(step.preview.netAmountSat).grouped(),
                            Satoshi(step.preview.estimatedFeeSat).grouped(),
                            Satoshi(step.preview.totalInputSat).grouped(),
                        )
                    } else {
                        step.preview.failureReason
                    }
                )
            }
        },
        confirmButton = {
            if (canGoAhead) {
                TextButton(onClick = onConfirm) {
                    Text(
                        stringResource(
                            when (step) {
                                is WavelengthExitStep.Emergency -> Res.string.wavelength_advanced_start_exit
                                else -> Res.string.wavelength_advanced_go_ahead
                            }
                        )
                    )
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

@Composable
private fun problemText(problem: WavelengthAdvancedProblem): String = stringResource(
    when (problem) {
        WavelengthAdvancedProblem.CouldNotRead -> Res.string.wavelength_advanced_could_not_read
        WavelengthAdvancedProblem.NeedsAddress -> Res.string.wavelength_advanced_needs_address
        WavelengthAdvancedProblem.CouldNotMove -> Res.string.wavelength_advanced_could_not_move
        WavelengthAdvancedProblem.CouldNotExit -> Res.string.wavelength_advanced_could_not_exit
        WavelengthAdvancedProblem.CouldNotSweep -> Res.string.wavelength_advanced_could_not_sweep
    }
)

@Composable
private fun noticeText(notice: WavelengthAdvancedNotice): String = stringResource(
    when (notice) {
        WavelengthAdvancedNotice.MoveStarted -> Res.string.wavelength_advanced_move_started
        WavelengthAdvancedNotice.ExitStarted -> Res.string.wavelength_advanced_exit_started
        WavelengthAdvancedNotice.ExitStopped -> Res.string.wavelength_advanced_exit_stopped
        WavelengthAdvancedNotice.Swept -> Res.string.wavelength_advanced_swept
    }
)
