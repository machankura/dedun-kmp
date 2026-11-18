package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.viewmodel.compose.viewModel
import com.machankura.dedun.compose.nostr.wallet.TransferRequestEvent
import com.machankura.dedun.compose.nostr.wallet.TransferRequestEvent.Rail
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.TransferRepository
import com.machankura.dedun.compose.text.GroupedSats
import com.machankura.dedun.compose.text.grouped
import com.machankura.dedun.compose.ui.composable.navigation.routes.FrostSigningRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.composable.widgets.EmptyState
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.composable.widgets.rememberNotifier
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.GroupTransferViewModel
import com.machankura.dedun.compose.ui.view.state.GroupTransferUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.a_transfer_is_at_least_s_sats
import dedun.composeapp.generated.resources.ask_the_group_to_record_it
import dedun.composeapp.generated.resources.asked_the_group_to_record_your_transfer
import dedun.composeapp.generated.resources.couldn_t_ask_the_group
import dedun.composeapp.generated.resources.hasn_t_started_depositing_here
import dedun.composeapp.generated.resources.note_optional
import dedun.composeapp.generated.resources.on_the_group_s_ledger_that_is_s_sats
import dedun.composeapp.generated.resources.rail_lightning
import dedun.composeapp.generated.resources.rail_onchain
import dedun.composeapp.generated.resources.rail_wavelength
import dedun.composeapp.generated.resources.s_sats_more_than_you_have
import dedun.composeapp.generated.resources.s_will_hold_s_sats_more_in_the_s_wallet
import dedun.composeapp.generated.resources.sats
import dedun.composeapp.generated.resources.this_profile_can_t_sign_a_transfer
import dedun.composeapp.generated.resources.transfer_amount
import dedun.composeapp.generated.resources.transfer_everything
import dedun.composeapp.generated.resources.transfer_moves_part_of_your_s_balance
import dedun.composeapp.generated.resources.transfer_to
import dedun.composeapp.generated.resources.transfer_to_a_member
import dedun.composeapp.generated.resources.you_have_nothing_to_transfer_in_this_wallet
import dedun.composeapp.generated.resources.you_have_s_sats_to_transfer
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Satoshi
import org.jetbrains.compose.resources.stringResource

/**
 * A member transferring part of their balance on one of the group's wallets to another member: whom, how much and why, and
 * asking the group to record it. Nothing leaves the wallet and no fee is paid, and the screen says so before it asks
 * (`docs/member-transfers.md`, T13). The form allows only what this device would itself sign.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupTransferScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    chain: Chain,
    rail: Rail,
    initialUIState: GroupTransferUIState = GroupTransferUIState.Loading,
    chatRepository: ChatRepository,
    transferRepository: TransferRepository,
    onNavigateToRoute: (Route) -> Unit = {},
    onNavigateBack: () -> Unit,
) {
    val viewModel: GroupTransferViewModel = viewModel(
        factory = GroupTransferViewModel.factory(chatRoomId, chain, rail, initialUIState, chatRepository, transferRepository)
    )
    LaunchedEffect(chatRoomId, chain, rail) {
        if (initialUIState == GroupTransferUIState.Loading) viewModel.initiate()
    }
    val notify = rememberNotifier(rememberCoroutineScope())
    val asked = stringResource(Res.string.asked_the_group_to_record_your_transfer)
    val couldNotAsk = stringResource(Res.string.couldn_t_ask_the_group)
    val railName = stringResource(railNameOf(rail))

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.transfer_to_a_member)) },
                navigationIcon = { NavigateBackButton(onNavigateBack) },
            )
        }
    ) { padding ->
        ScreenStateTransition(viewModel.uiState) { uiState ->
            when (val state = uiState) {
                GroupTransferUIState.Loading -> LoadingDataIndicator(modifier = Modifier.fillMaxWidth().padding(padding))
                is GroupTransferUIState.Error -> ErrorState(modifier = Modifier.padding(padding), message = state.message, onRetry = viewModel::initiate)
                is GroupTransferUIState.Loaded -> when {
                    !state.canAsk -> EmptyState(
                        modifier = Modifier.padding(padding),
                        message = stringResource(Res.string.this_profile_can_t_sign_a_transfer),
                        icon = Icons.Default.SwapHoriz,
                    )
                    state.givable.givableSat < TransferRequestEvent.MIN_TRANSFER_SAT -> EmptyState(
                        modifier = Modifier.padding(padding),
                        message = stringResource(Res.string.you_have_nothing_to_transfer_in_this_wallet),
                        icon = Icons.Default.SwapHoriz,
                    )
                    else -> TransferForm(
                        state = state,
                        railName = railName,
                        viewModel = viewModel,
                        modifier = Modifier.padding(padding),
                        onSend = {
                            viewModel.send(
                                onSent = { sessionId ->
                                    notify(asked)
                                    if (sessionId != null) {
                                        onNavigateToRoute(FrostSigningRoute(activeUserPublicKey = activeUserPublicKey, chatRoomId = chatRoomId, sessionId = sessionId))
                                    } else {
                                        onNavigateBack()
                                    }
                                },
                                onFailure = { notify(couldNotAsk) },
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TransferForm(
    state: GroupTransferUIState.Loaded,
    railName: String,
    viewModel: GroupTransferViewModel,
    modifier: Modifier,
    onSend: () -> Unit,
) {
    fun name(member: HexKey) = "@" + (state.names[member] ?: member.take(8))
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val ledger = viewModel.ledgerAmount()
    val problem = viewModel.problem()

    LazyColumn(
        modifier = modifier.readableContent().fillMaxSize().padding(MaterialTheme.spacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)) {
                Text(stringResource(Res.string.transfer_moves_part_of_your_s_balance, railName), style = MaterialTheme.typography.bodyMedium, color = muted)
                Text(
                    stringResource(Res.string.you_have_s_sats_to_transfer, Satoshi(state.givable.heldSat).grouped()),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        item { Text(stringResource(Res.string.transfer_to), style = MaterialTheme.typography.titleSmall) }
        items(state.receivers, key = { it.member }) { receiver ->
            Row(
                modifier = Modifier.fillMaxWidth()
                    .selectable(
                        selected = viewModel.receiver == receiver.member,
                        enabled = receiver.canReceive,
                        role = Role.RadioButton,
                        onClick = { viewModel.receiver = receiver.member },
                    )
                    .minimumInteractiveComponentSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = viewModel.receiver == receiver.member, onClick = null, enabled = receiver.canReceive)
                Column(modifier = Modifier.weight(1f).padding(start = MaterialTheme.spacing.relatedGap)) {
                    Text(name(receiver.member), style = MaterialTheme.typography.bodyLarge)
                    if (!receiver.canReceive) {
                        Text(stringResource(Res.string.hasn_t_started_depositing_here), style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .toggleable(value = viewModel.everything, role = Role.Checkbox, onValueChange = { viewModel.everything = it })
                    .minimumInteractiveComponentSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = viewModel.everything, onCheckedChange = null)
                Text(stringResource(Res.string.transfer_everything), modifier = Modifier.padding(start = MaterialTheme.spacing.relatedGap))
            }
        }
        if (!viewModel.everything) {
            item {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    state = viewModel.amount,
                    outputTransformation = GroupedSats,
                    lineLimits = TextFieldLineLimits.SingleLine,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text(stringResource(Res.string.transfer_amount)) },
                    suffix = { Text(stringResource(Res.string.sats)) },
                )
            }
        }
        item {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                state = viewModel.note,
                inputTransformation = InputTransformation.maxLength(TransferRequestEvent.MAX_NOTE_CHARS),
                lineLimits = TextFieldLineLimits.SingleLine,
                label = { Text(stringResource(Res.string.note_optional)) },
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)) {
                when (problem) {
                    GroupTransferViewModel.Problem.BelowFloor -> Text(
                        stringResource(Res.string.a_transfer_is_at_least_s_sats, Satoshi(TransferRequestEvent.MIN_TRANSFER_SAT).grouped()),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                    )
                    is GroupTransferViewModel.Problem.OverBalance -> Text(
                        stringResource(Res.string.s_sats_more_than_you_have, Satoshi(state.givable.arrivesAs(problem.bySat)).grouped()),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                    )
                    else -> Unit
                }
                val receiver = viewModel.receiver
                if (problem == null && ledger != null && receiver != null) {
                    // T13: a co-signer and the giver both have to see that this is bookkeeping, not a spend.
                    // What arrives by this device's count today; the ledger figure the group signs, where the wallet holds less
                    // than its members' balances add up to and the two differ (T5).
                    val arrives = state.givable.arrivesAs(ledger)
                    Text(
                        stringResource(Res.string.s_will_hold_s_sats_more_in_the_s_wallet, name(receiver), Satoshi(arrives).grouped(), railName),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (arrives != ledger) {
                        Text(
                            stringResource(Res.string.on_the_group_s_ledger_that_is_s_sats, Satoshi(ledger).grouped()),
                            style = MaterialTheme.typography.bodySmall, color = muted,
                        )
                    }
                }
                Button(onClick = onSend, enabled = problem == null && !viewModel.isActionPending.value) {
                    Text(stringResource(Res.string.ask_the_group_to_record_it))
                }
            }
        }
    }
}

/** A rail's name as the screens say it. */
internal fun railNameOf(rail: Rail) = when (rail) {
    Rail.WAVELENGTH -> Res.string.rail_wavelength
    Rail.LIGHTNING -> Res.string.rail_lightning
    Rail.ONCHAIN -> Res.string.rail_onchain
}
