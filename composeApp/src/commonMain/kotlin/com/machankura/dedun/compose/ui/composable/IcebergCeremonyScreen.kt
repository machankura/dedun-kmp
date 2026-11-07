package com.machankura.dedun.compose.ui.composable

import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.a_lightning_wallet_needs_four_signers
import dedun.composeapp.generated.resources.a_wavelength_wallet_needs_four_signers
import dedun.composeapp.generated.resources.the_group_has_a_wavelength_wallet_key
import dedun.composeapp.generated.resources.the_wavelength_wallet_key_is_made_by_everyone
import dedun.composeapp.generated.resources.this_group_has_no_wavelength_wallet_key_yet
import dedun.composeapp.generated.resources.wavelength_wallet_key
import dedun.composeapp.generated.resources.abandon
import dedun.composeapp.generated.resources.abandon_ceremony
import dedun.composeapp.generated.resources.abandon_this_key_ceremony_question
import dedun.composeapp.generated.resources.cancel
import dedun.composeapp.generated.resources.couldn_t_abandon_the_ceremony
import dedun.composeapp.generated.resources.if_it_hasn_t_finished_s_hours_after
import dedun.composeapp.generated.resources.it_ends_for_everyone_in_it_no_key_is_created
import dedun.composeapp.generated.resources.the_ceremony_was_abandoned
import dedun.composeapp.generated.resources.nothing_was_created_and_no_key_exists_it_is
import dedun.composeapp.generated.resources.confirm_what_you_saw
import dedun.composeapp.generated.resources.confirming_publishes_your_public_share
import dedun.composeapp.generated.resources.couldn_t_approve_this_step
import dedun.composeapp.generated.resources.couldn_t_start_the_key_ceremony
import dedun.composeapp.generated.resources.hasn_t_answered_the_proposal_yet
import dedun.composeapp.generated.resources.join_the_ceremony
import dedun.composeapp.generated.resources.joining_publishes_commitments
import dedun.composeapp.generated.resources.lightning_wallet_key
import dedun.composeapp.generated.resources.open_the_group_s_onchain_wallet_first
import dedun.composeapp.generated.resources.s_of_3_steps
import dedun.composeapp.generated.resources.send_your_contributions
import dedun.composeapp.generated.resources.sending_contributions_encrypts_each
import dedun.composeapp.generated.resources.signers_needed_s_of_s
import dedun.composeapp.generated.resources.start_a_key_ceremony
import dedun.composeapp.generated.resources.the_group_has_a_lightning_wallet_key
import dedun.composeapp.generated.resources.the_lightning_wallet_key_is_made_by_everyone
import dedun.composeapp.generated.resources.this_group_has_no_lightning_wallet_key_yet
import dedun.composeapp.generated.resources.waiting_for_contributions
import dedun.composeapp.generated.resources.waiting_for_everyone_to_confirm
import dedun.composeapp.generated.resources.waiting_for_everyone_to_join
import dedun.composeapp.generated.resources.your_share_of_this_key_is_stored_unencrypted
import com.machankura.dedun.compose.database.model.IcebergKeySession
import com.machankura.dedun.compose.database.model.types.IcebergApprovalStep
import com.machankura.dedun.compose.database.model.types.IcebergRitualStage
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.managers.IcebergRitualManager
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.GroupLightningRepository
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
import com.machankura.dedun.compose.ui.view.model.IcebergCeremonyViewModel
import com.machankura.dedun.compose.ui.view.state.IcebergCeremonyUIState
import com.machankura.dedun.compose.wallet.GroupLightningRollout
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.stringResource

/**
 * A group's wallet key ceremony: who has done which of its three rounds, whether this device
 * has been asked to join, the way to abandon it, and -- for a group without one -- the way to start it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IcebergCeremonyScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    sessionId: String?,
    activeIdentityStateFlow: StateFlow<Identity?>,
    chatRepository: ChatRepository,
    lightningRepository: GroupLightningRepository,
    purpose: String = IcebergKeySession.PURPOSE_LIGHTNING,
    chain: Chain = GroupLightningRollout.NETWORK,
    onNavigateBack: () -> Unit,
) {
    val viewModel: IcebergCeremonyViewModel = viewModel(
        factory = IcebergCeremonyViewModel.factory(
            activeUserPublicKey = activeUserPublicKey,
            chatRoomId = chatRoomId,
            sessionId = sessionId,
            chain = chain,
            activeIdentityStateFlow = activeIdentityStateFlow,
            chatRepository = chatRepository,
            lightningRepository = lightningRepository,
            purpose = purpose,
        )
    )
    LaunchedEffect(true) { viewModel.initiate() }

    val notify = rememberNotifier(rememberCoroutineScope())
    val couldNotStart = stringResource(Res.string.couldn_t_start_the_key_ceremony)
    val couldNotApprove = stringResource(Res.string.couldn_t_approve_this_step)
    val couldNotAbandon = stringResource(Res.string.couldn_t_abandon_the_ceremony)
    // A ceremony reached from a transcript line says which wallet it's for; one being started, what the screen was asked for.
    val isWavelength = ((viewModel.uiState as? IcebergCeremonyUIState.Loaded)?.session?.purpose ?: purpose) == IcebergKeySession.PURPOSE_WAVELENGTH

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (isWavelength) Res.string.wavelength_wallet_key else Res.string.lightning_wallet_key), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { NavigateBackButton(onNavigateBack) }
            )
        }
    ) { padding ->
        ScreenStateTransition(viewModel.uiState) { uiState ->
            when (val state = uiState) {
                is IcebergCeremonyUIState.Error -> ErrorState(modifier = Modifier.padding(padding), message = state.message, onRetry = null)

                IcebergCeremonyUIState.Loading -> LoadingDataIndicator(modifier = Modifier.fillMaxWidth().padding(padding))

                is IcebergCeremonyUIState.Loaded -> if (state.session == null) {
                    EmptyState(
                        modifier = Modifier.padding(padding).readableContent(),
                        message = when {
                            !state.hasOnchainWallet -> stringResource(Res.string.open_the_group_s_onchain_wallet_first)
                            state.thresholds.isEmpty() -> stringResource(if (isWavelength) Res.string.a_wavelength_wallet_needs_four_signers else Res.string.a_lightning_wallet_needs_four_signers)
                            else -> stringResource(if (isWavelength) Res.string.this_group_has_no_wavelength_wallet_key_yet else Res.string.this_group_has_no_lightning_wallet_key_yet)
                        },
                        icon = if (isWavelength) Icons.Default.Waves else Icons.Default.Bolt,
                        action = if (viewModel.canPropose()) {
                            {
                                StartCeremony(
                                    isWavelength = isWavelength,
                                    thresholds = state.thresholds,
                                    participants = state.holderCount,
                                    chosen = viewModel.threshold.value,
                                    onChoose = { viewModel.threshold.value = it },
                                    isPending = viewModel.isActionPending.value,
                                    onStart = { viewModel.propose(onFailure = { notify(couldNotStart) }) },
                                )
                            }
                        } else null
                    )
                } else {
                    Ceremony(
                        modifier = Modifier.padding(padding),
                        state = state,
                        isPending = viewModel.isActionPending.value,
                        onApprove = { viewModel.approve(onFailure = { notify(couldNotApprove) }) },
                        canAbandon = viewModel.canAbandon(),
                        onAbandon = { viewModel.abandon(onFailure = { notify(couldNotAbandon) }) },
                    )
                }
            }
        }
    }
}

@Composable
private fun StartCeremony(isWavelength: Boolean, thresholds: List<Int>, participants: Int, chosen: Int?, onChoose: (Int) -> Unit, isPending: Boolean, onStart: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap)) {
        Text(stringResource(if (isWavelength) Res.string.the_wavelength_wallet_key_is_made_by_everyone else Res.string.the_lightning_wallet_key_is_made_by_everyone, (2 * (chosen ?: 2) - 1).toString()), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)) {
            thresholds.forEach { t ->
                FilterChip(
                    selected = t == chosen,
                    onClick = { onChoose(t) },
                    label = { Text(stringResource(Res.string.signers_needed_s_of_s, t.toString(), participants.toString())) },
                )
            }
        }
        Text(stringResource(Res.string.your_share_of_this_key_is_stored_unencrypted), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onStart, enabled = !isPending && chosen != null) {
            Icon(Icons.Default.Key, contentDescription = Decorative)
            Spacer(modifier = Modifier.width(MaterialTheme.spacing.relatedGap))
            Text(stringResource(Res.string.start_a_key_ceremony))
        }
    }
}

@Composable
private fun Ceremony(
    modifier: Modifier,
    state: IcebergCeremonyUIState.Loaded,
    isPending: Boolean,
    onApprove: () -> Unit,
    canAbandon: Boolean,
    onAbandon: () -> Unit,
) {
    val session = state.session ?: return
    val members = state.localChatRoom.localParticipants.associate { it.participant.participantPublicKey.lowercase() to it }
    var isConfirmingAbandon by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = modifier.readableContent().fillMaxSize().padding(MaterialTheme.spacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
    ) {
        item {
            Text(
                text = when (session.stage) {
                    IcebergRitualStage.COLLECTING_COMMITMENTS -> stringResource(Res.string.waiting_for_everyone_to_join)
                    IcebergRitualStage.COLLECTING_REVEALS -> stringResource(Res.string.waiting_for_contributions)
                    IcebergRitualStage.COLLECTING_CONFIRMATIONS -> stringResource(Res.string.waiting_for_everyone_to_confirm)
                    IcebergRitualStage.COMPLETE -> stringResource(if (session.purpose == IcebergKeySession.PURPOSE_WAVELENGTH) Res.string.the_group_has_a_wavelength_wallet_key else Res.string.the_group_has_a_lightning_wallet_key)
                    IcebergRitualStage.FAILED -> stringResource(Res.string.the_ceremony_was_abandoned)
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(Res.string.signers_needed_s_of_s, session.threshold.toString(), session.participantCount.toString()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Said while it runs, so a member who has not joined knows the room is not held forever.
            if (canAbandon) {
                Text(
                    text = stringResource(Res.string.if_it_hasn_t_finished_s_hours_after, IcebergRitualManager.DEADLINE.inWholeHours.toString()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // As the shared key's ceremony says it: why, in the sentence the device that
        // stopped it wrote, and that nothing was made.
        if (session.stage == IcebergRitualStage.FAILED) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.containerPadding),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = Decorative)
                        Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)) {
                            session.failureReason?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                            Text(stringResource(Res.string.nothing_was_created_and_no_key_exists_it_is), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
        state.pending?.let { step ->
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(MaterialTheme.spacing.containerPadding), verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)) {
                        Text(
                            text = when (step) {
                                IcebergApprovalStep.JOIN -> stringResource(Res.string.joining_publishes_commitments)
                                IcebergApprovalStep.REVEAL -> stringResource(Res.string.sending_contributions_encrypts_each)
                                IcebergApprovalStep.CONFIRM -> stringResource(Res.string.confirming_publishes_your_public_share)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(onClick = onApprove, enabled = !isPending) {
                            Text(
                                when (step) {
                                    IcebergApprovalStep.JOIN -> stringResource(Res.string.join_the_ceremony)
                                    IcebergApprovalStep.REVEAL -> stringResource(Res.string.send_your_contributions)
                                    IcebergApprovalStep.CONFIRM -> stringResource(Res.string.confirm_what_you_saw)
                                }
                            )
                        }
                    }
                }
            }
        }
        items(session.participants()) { participant ->
            ListItem(
                headlineContent = { Text(members[participant]?.profile?.humanReadableNameOrPubkey() ?: participant.take(16)) },
                supportingContent = {
                    // Phase 15: a member whose build does not know these kinds never answers, and the
                    // group would be smaller for it, so the screen says who has not.
                    val steps = state.progress[participant] ?: 0
                    val failed = session.stage == IcebergRitualStage.FAILED
                    Text(
                        text = if (steps == 0 && !failed) stringResource(Res.string.hasn_t_answered_the_proposal_yet) else stringResource(Res.string.s_of_3_steps, steps.toString()),
                        color = if (steps == 0 && !failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
        // Below the members rather than beside the join button: the consequence is the whole
        // ceremony's, and reads against who is in it.
        if (canAbandon) {
            item {
                TextButton(
                    onClick = { isConfirmingAbandon = true },
                    enabled = !isPending,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(Res.string.abandon_ceremony))
                }
            }
        }
    }

    if (isConfirmingAbandon) {
        AlertDialog(
            onDismissRequest = { isConfirmingAbandon = false },
            title = { Text(stringResource(Res.string.abandon_this_key_ceremony_question)) },
            text = { Text(stringResource(Res.string.it_ends_for_everyone_in_it_no_key_is_created)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        isConfirmingAbandon = false
                        onAbandon()
                    }
                ) {
                    Text(stringResource(Res.string.abandon))
                }
            },
            dismissButton = {
                TextButton(onClick = { isConfirmingAbandon = false }) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }
}
