package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
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
import com.machankura.dedun.compose.ui.view.model.ArkAdvancedUIState
import com.machankura.dedun.compose.ui.view.model.ArkAdvancedViewModel
import com.machankura.dedun.compose.wallet.ark.ArkExitStatus
import com.machankura.dedun.compose.wallet.ark.ArkVtxo
import com.machankura.dedun.compose.wallet.ark.IdentityArk
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.ark_advanced
import dedun.composeapp.generated.resources.ark_advanced_explainer
import dedun.composeapp.generated.resources.ark_claim_exits
import dedun.composeapp.generated.resources.ark_claim_failed
import dedun.composeapp.generated.resources.ark_claim_started
import dedun.composeapp.generated.resources.ark_coin_exited
import dedun.composeapp.generated.resources.ark_coin_locked
import dedun.composeapp.generated.resources.ark_coin_s_sats_expires_at_block_s
import dedun.composeapp.generated.resources.ark_coin_spendable
import dedun.composeapp.generated.resources.ark_coin_spent
import dedun.composeapp.generated.resources.ark_coins
import dedun.composeapp.generated.resources.ark_emergency_exit_cannot_be_undone
import dedun.composeapp.generated.resources.ark_emergency_exit_explainer_s
import dedun.composeapp.generated.resources.ark_emergency_exit_not_fundable_s
import dedun.composeapp.generated.resources.ark_exit_already_spent
import dedun.composeapp.generated.resources.ark_exit_awaiting_delay
import dedun.composeapp.generated.resources.ark_exit_broadcasting
import dedun.composeapp.generated.resources.ark_exit_canceled
import dedun.composeapp.generated.resources.ark_exit_claimable
import dedun.composeapp.generated.resources.ark_exit_claimed
import dedun.composeapp.generated.resources.ark_exit_claiming
import dedun.composeapp.generated.resources.ark_exit_failed
import dedun.composeapp.generated.resources.ark_exit_progress
import dedun.composeapp.generated.resources.ark_exit_starting
import dedun.composeapp.generated.resources.ark_exit_swept
import dedun.composeapp.generated.resources.ark_exits_claimable_from_block_s
import dedun.composeapp.generated.resources.ark_move_everything_onchain
import dedun.composeapp.generated.resources.ark_movement_emergency_exit
import dedun.composeapp.generated.resources.ark_offboard_failed
import dedun.composeapp.generated.resources.ark_offboard_moves_s_less_a_fee_of_s
import dedun.composeapp.generated.resources.ark_offboard_started
import dedun.composeapp.generated.resources.ark_onchain_address
import dedun.composeapp.generated.resources.ark_start_emergency_exit
import dedun.composeapp.generated.resources.ark_start_emergency_exit_question
import dedun.composeapp.generated.resources.ark_wallet_could_not_read
import dedun.composeapp.generated.resources.ark_wallet_not_turned_on
import dedun.composeapp.generated.resources.cancel
import org.jetbrains.compose.resources.stringResource

/**
 * Leaving Ark -- `docs/bark-wallet.md`, Phase 9: the coins as Bark holds them, everything
 * moved onchain with the server's help, and the emergency exit without it. An exit under
 * way replaces the exit button with its progress, which opening this screen advances.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArkAdvancedScreen(
    identityArk: IdentityArk,
    onNavigateBack: () -> Unit,
) {
    val viewModel: ArkAdvancedViewModel = viewModel(factory = ArkAdvancedViewModel.factory(identityArk))
    LaunchedEffect(true) { viewModel.load() }

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.ark_advanced)) },
                navigationIcon = { NavigateBackButton(onNavigateBack) },
            )
        },
    ) { padding ->
        ScreenStateTransition(viewModel.uiState) { uiState ->
            when (val state = uiState) {
                ArkAdvancedUIState.Loading -> LoadingDataIndicator(modifier = Modifier.fillMaxWidth().padding(padding))
                ArkAdvancedUIState.NoWallet -> EmptyState(
                    modifier = Modifier.padding(padding).readableContent(),
                    message = stringResource(Res.string.ark_wallet_not_turned_on),
                    icon = Icons.Default.AccountBalanceWallet,
                )
                is ArkAdvancedUIState.Failed -> ErrorState(
                    modifier = Modifier.padding(padding),
                    message = stringResource(Res.string.ark_wallet_could_not_read),
                    onRetry = viewModel::load,
                )
                is ArkAdvancedUIState.Loaded -> ArkAdvancedLoaded(state, viewModel, Modifier.padding(padding))
            }
        }
    }
}

@Composable
private fun ArkAdvancedLoaded(state: ArkAdvancedUIState.Loaded, viewModel: ArkAdvancedViewModel, modifier: Modifier) {
    val notify = rememberNotifier(rememberCoroutineScope())
    val offboardStarted = stringResource(Res.string.ark_offboard_started)
    val offboardFailed = stringResource(Res.string.ark_offboard_failed)
    val exitFailed = stringResource(Res.string.ark_exit_failed)
    val claimStarted = stringResource(Res.string.ark_claim_started)
    val claimFailed = stringResource(Res.string.ark_claim_failed)
    var confirmingExit by remember { mutableStateOf(false) }
    if (confirmingExit) {
        AlertDialog(
            onDismissRequest = { confirmingExit = false },
            title = { Text(stringResource(Res.string.ark_start_emergency_exit_question)) },
            text = { Text(stringResource(Res.string.ark_emergency_exit_cannot_be_undone)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingExit = false
                    viewModel.startEmergencyExit(onStarted = {}, onFailure = { notify(exitFailed) })
                }) { Text(stringResource(Res.string.ark_start_emergency_exit)) }
            },
            dismissButton = { TextButton(onClick = { confirmingExit = false }) { Text(stringResource(Res.string.cancel)) } },
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().readableContent().padding(horizontal = MaterialTheme.spacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
    ) {
        item { Text(stringResource(Res.string.ark_advanced_explainer), style = MaterialTheme.typography.bodyMedium) }

        item {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                state = viewModel.addressField,
                lineLimits = TextFieldLineLimits.SingleLine,
                label = { Text(stringResource(Res.string.ark_onchain_address)) },
            )
        }

        if (!state.exiting) {
            item {
                viewModel.offboardFee?.let { fee ->
                    Text(stringResource(Res.string.ark_offboard_moves_s_less_a_fee_of_s, fee.gross.grouped(), fee.fee.grouped()), style = MaterialTheme.typography.bodyMedium)
                }
            }
            item {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        if (viewModel.offboardFee == null) viewModel.estimateOffboard(onFailure = { notify(offboardFailed) })
                        else viewModel.offboardAll(onStarted = { notify(offboardStarted) }, onFailure = { notify(offboardFailed) })
                    },
                ) { Text(stringResource(Res.string.ark_move_everything_onchain)) }
            }
            item { HorizontalDivider() }
            item { Text(stringResource(Res.string.ark_movement_emergency_exit), style = MaterialTheme.typography.titleMedium) }
            state.exitFee?.let { fee ->
                item { Text(stringResource(Res.string.ark_emergency_exit_explainer_s, fee.total.grouped()), style = MaterialTheme.typography.bodyMedium) }
                if (!fee.fundable) {
                    item {
                        Text(
                            stringResource(Res.string.ark_emergency_exit_not_fundable_s, state.fundingAddress),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                item {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { confirmingExit = true },
                        enabled = fee.fundable,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    ) { Text(stringResource(Res.string.ark_start_emergency_exit)) }
                }
            }
        } else {
            item { Text(stringResource(Res.string.ark_exit_progress), style = MaterialTheme.typography.titleMedium) }
            items(state.exits, key = { "exit-" + it.vtxoId }) { exit ->
                ListItem(
                    headlineContent = { Text(exit.vtxoId, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
                    supportingContent = { Text(exit.error ?: stringResource(exitStateLabel(exit.state))) },
                )
            }
            state.claimableAt?.let { height ->
                item { Text(stringResource(Res.string.ark_exits_claimable_from_block_s, height.toString()), style = MaterialTheme.typography.bodyMedium) }
            }
            item {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { viewModel.claim(onStarted = { notify(claimStarted) }, onFailure = { notify(claimFailed) }) },
                    enabled = state.claimable,
                ) { Text(stringResource(Res.string.ark_claim_exits)) }
            }
        }

        item { HorizontalDivider() }
        item { Text(stringResource(Res.string.ark_coins), style = MaterialTheme.typography.titleMedium) }
        items(state.vtxos, key = { "vtxo-" + it.id }) { vtxo ->
            ListItem(
                headlineContent = { Text(stringResource(Res.string.ark_coin_s_sats_expires_at_block_s, vtxo.amount.grouped(), vtxo.expiryHeight.toString())) },
                supportingContent = { Text(stringResource(vtxoStateLabel(vtxo.state))) },
            )
        }
    }
}

private fun exitStateLabel(state: ArkExitStatus.State) = when (state) {
    ArkExitStatus.State.Starting -> Res.string.ark_exit_starting
    ArkExitStatus.State.Broadcasting -> Res.string.ark_exit_broadcasting
    ArkExitStatus.State.AwaitingDelay -> Res.string.ark_exit_awaiting_delay
    ArkExitStatus.State.Claimable -> Res.string.ark_exit_claimable
    ArkExitStatus.State.Claiming -> Res.string.ark_exit_claiming
    ArkExitStatus.State.Claimed -> Res.string.ark_exit_claimed
    ArkExitStatus.State.Canceled -> Res.string.ark_exit_canceled
    ArkExitStatus.State.AlreadySpent -> Res.string.ark_exit_already_spent
    ArkExitStatus.State.Swept -> Res.string.ark_exit_swept
}

private fun vtxoStateLabel(state: ArkVtxo.State) = when (state) {
    ArkVtxo.State.Spendable -> Res.string.ark_coin_spendable
    ArkVtxo.State.Locked -> Res.string.ark_coin_locked
    ArkVtxo.State.Spent -> Res.string.ark_coin_spent
    ArkVtxo.State.Exited -> Res.string.ark_coin_exited
}
