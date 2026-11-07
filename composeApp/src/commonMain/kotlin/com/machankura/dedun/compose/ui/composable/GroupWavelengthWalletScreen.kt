package com.machankura.dedun.compose.ui.composable

import dedun.composeapp.generated.resources.group_wavelength_onchain_payout_expired
import dedun.composeapp.generated.resources.group_wavelength_onchain_payout_in_a_round
import dedun.composeapp.generated.resources.group_wavelength_onchain_payout_paid_s
import dedun.composeapp.generated.resources.group_wavelength_onchain_payout_s_s
import com.machankura.dedun.compose.repository.GroupWavelengthOnchainPayout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.machankura.dedun.compose.database.model.IcebergKeySession
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.managers.GroupWavelengthDaemon
import com.machankura.dedun.compose.managers.GroupWavelengthDaemonStatus
import com.machankura.dedun.compose.managers.GroupWavelengthReports
import com.machankura.dedun.compose.managers.GroupWavelengthWalletManager
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthPaymentEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthSnapshot
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.GroupWavelengthDaemonSource
import com.machankura.dedun.compose.repository.GroupWavelengthRepository
import com.machankura.dedun.compose.text.BlockDuration
import com.machankura.dedun.compose.text.describe
import com.machankura.dedun.compose.text.grouped
import com.machankura.dedun.compose.ui.composable.navigation.routes.FrostSigningRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.GroupPayoutsRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.GroupWalletRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.GroupWavelengthAdvancedRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.GroupWavelengthReceiveRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.IcebergCeremonyRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.composable.widgets.EmptyState
import androidx.compose.ui.graphics.vector.ImageVector
import com.machankura.dedun.compose.extensions.toFormattedTimeAndDateString
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.composable.widgets.rememberNotifier
import com.machankura.dedun.compose.ui.theme.breakpoint
import com.machankura.dedun.compose.ui.theme.listPaneWidthFor
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.GroupWavelengthWalletViewModel
import com.machankura.dedun.compose.ui.view.state.GroupWavelengthWalletUIState
import com.machankura.dedun.compose.wallet.wavelength.WalletExpiry
import com.machankura.dedun.compose.wallet.wavelength.WavelengthPlatform
import com.machankura.dedun.compose.ui.composable.widgets.wallet.memberName
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthEntry
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthRollout
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.a_wavelength_wallet_needs_four_signers
import dedun.composeapp.generated.resources.couldn_t_propose_this
import dedun.composeapp.generated.resources.cancel
import dedun.composeapp.generated.resources.group_wallet
import dedun.composeapp.generated.resources.group_wavelength_departed_s
import dedun.composeapp.generated.resources.group_wavelength_earlier_empty
import dedun.composeapp.generated.resources.group_wavelength_earlier_holds_s
import dedun.composeapp.generated.resources.group_wavelength_earlier_leave
import dedun.composeapp.generated.resources.group_wavelength_earlier_s
import dedun.composeapp.generated.resources.group_wavelength_leave
import dedun.composeapp.generated.resources.open_a_new_wavelength_wallet
import dedun.composeapp.generated.resources.group_wavelength_leave_ask
import dedun.composeapp.generated.resources.group_wavelength_leave_asked
import dedun.composeapp.generated.resources.group_wavelength_leave_explainer
import dedun.composeapp.generated.resources.group_wavelength_leave_question
import dedun.composeapp.generated.resources.group_wavelength_run_it_yourself
import dedun.composeapp.generated.resources.group_wavelength_activity_fee_s
import dedun.composeapp.generated.resources.group_wavelength_coin_pending
import dedun.composeapp.generated.resources.group_wavelength_coin_settled
import dedun.composeapp.generated.resources.group_wavelength_history
import dedun.composeapp.generated.resources.group_wavelength_history_exit_s
import dedun.composeapp.generated.resources.group_wavelength_history_in_s
import dedun.composeapp.generated.resources.group_wavelength_history_in_s_s
import dedun.composeapp.generated.resources.group_wavelength_history_none
import dedun.composeapp.generated.resources.group_wavelength_history_operator_only
import dedun.composeapp.generated.resources.group_wavelength_history_ran_out
import dedun.composeapp.generated.resources.group_wavelength_history_round
import dedun.composeapp.generated.resources.group_wavelength_history_when_unknown
import dedun.composeapp.generated.resources.wavelength_entry_failed
import dedun.composeapp.generated.resources.wavelength_entry_s_dot_s
import dedun.composeapp.generated.resources.wavelength_entry_succeeded
import dedun.composeapp.generated.resources.wavelength_entry_waiting_for_payment
import dedun.composeapp.generated.resources.group_wavelength_activity_line_s_s
import dedun.composeapp.generated.resources.group_wavelength_advanced
import dedun.composeapp.generated.resources.group_wavelength_alarm_title
import dedun.composeapp.generated.resources.group_wavelength_balance_label
import dedun.composeapp.generated.resources.group_wavelength_balance_none
import dedun.composeapp.generated.resources.group_wavelength_credit_s
import dedun.composeapp.generated.resources.group_wavelength_deadline_expired
import dedun.composeapp.generated.resources.group_wavelength_deadline_fine_s
import dedun.composeapp.generated.resources.group_wavelength_deadline_renewing_s
import dedun.composeapp.generated.resources.group_wavelength_deadline_urgent_s
import dedun.composeapp.generated.resources.group_wavelength_held_s
import dedun.composeapp.generated.resources.group_wavelength_no_report
import dedun.composeapp.generated.resources.group_wavelength_operator_says_s
import dedun.composeapp.generated.resources.group_wavelength_payout_approved
import dedun.composeapp.generated.resources.group_wavelength_payout_failed_s
import dedun.composeapp.generated.resources.group_wavelength_payout_in_flight
import dedun.composeapp.generated.resources.group_wavelength_payout_paid
import dedun.composeapp.generated.resources.group_wavelength_payout_s_s
import dedun.composeapp.generated.resources.group_wavelength_pending_s
import dedun.composeapp.generated.resources.pay_out
import dedun.composeapp.generated.resources.group_wavelength_report_cannot_confirm
import dedun.composeapp.generated.resources.group_wavelength_report_differs
import dedun.composeapp.generated.resources.group_wavelength_report_matches
import dedun.composeapp.generated.resources.group_wavelength_seen_earlier
import dedun.composeapp.generated.resources.group_wavelength_seen_enough_s_s
import dedun.composeapp.generated.resources.group_wavelength_seen_never
import dedun.composeapp.generated.resources.group_wavelength_seen_recently
import dedun.composeapp.generated.resources.group_wavelength_seen_too_few_s_s
import dedun.composeapp.generated.resources.group_wavelength_signers
import dedun.composeapp.generated.resources.s_sats
import dedun.composeapp.generated.resources.group_wavelength_cap_s
import dedun.composeapp.generated.resources.group_wavelength_commitment_cap_s
import dedun.composeapp.generated.resources.group_wavelength_commitment_devices
import dedun.composeapp.generated.resources.group_wavelength_commitment_fees
import dedun.composeapp.generated.resources.group_wavelength_commitment_operator
import dedun.composeapp.generated.resources.group_wavelength_commitment_unreviewed
import dedun.composeapp.generated.resources.group_wavelength_daemon_busy_s
import dedun.composeapp.generated.resources.group_wavelength_daemon_catching_up
import dedun.composeapp.generated.resources.group_wavelength_daemon_failed
import dedun.composeapp.generated.resources.group_wavelength_daemon_keys_disagree
import dedun.composeapp.generated.resources.group_wavelength_daemon_no_seed
import dedun.composeapp.generated.resources.group_wavelength_daemon_not_on_device
import dedun.composeapp.generated.resources.group_wavelength_daemon_opened_as_another
import dedun.composeapp.generated.resources.group_wavelength_daemon_opening
import dedun.composeapp.generated.resources.group_wavelength_daemon_paused
import dedun.composeapp.generated.resources.group_wavelength_daemon_recovering
import dedun.composeapp.generated.resources.group_wavelength_daemon_running
import dedun.composeapp.generated.resources.group_wavelength_daemon_signers_not_ready
import dedun.composeapp.generated.resources.group_wavelength_daemon_waiting_for_signers
import dedun.composeapp.generated.resources.group_wavelength_leave_this_open
import dedun.composeapp.generated.resources.group_wavelength_no_operator_known
import dedun.composeapp.generated.resources.group_wavelength_no_runtime
import dedun.composeapp.generated.resources.group_wavelength_one_wallet_a_device
import dedun.composeapp.generated.resources.group_wavelength_operator_explainer
import dedun.composeapp.generated.resources.group_wavelength_refused_ceremony
import dedun.composeapp.generated.resources.group_wavelength_refused_chain
import dedun.composeapp.generated.resources.group_wavelength_refused_holders
import dedun.composeapp.generated.resources.group_wavelength_refused_keys
import dedun.composeapp.generated.resources.group_wavelength_refused_other
import dedun.composeapp.generated.resources.group_wavelength_refused_size
import dedun.composeapp.generated.resources.group_wavelength_signers_s_of_s
import dedun.composeapp.generated.resources.group_wavelength_terms_changed
import dedun.composeapp.generated.resources.group_wavelength_test_network_s
import dedun.composeapp.generated.resources.group_wavelength_waiting_for_seed
import dedun.composeapp.generated.resources.group_wavelength_wallet
import dedun.composeapp.generated.resources.make_a_key
import dedun.composeapp.generated.resources.nobody_runs_this_wallet
import dedun.composeapp.generated.resources.open_a_wavelength_wallet
import dedun.composeapp.generated.resources.open_the_group_s_onchain_wallet_first
import dedun.composeapp.generated.resources.receive
import dedun.composeapp.generated.resources.run_by_s_term_s
import dedun.composeapp.generated.resources.run_this_wallet_on_my_device
import dedun.composeapp.generated.resources.see_the_key_ceremony
import dedun.composeapp.generated.resources.see_the_proposal
import dedun.composeapp.generated.resources.step_down
import dedun.composeapp.generated.resources.the_group_is_making_its_wavelength_wallet_key
import dedun.composeapp.generated.resources.this_group_has_no_wavelength_wallet_key_yet
import dedun.composeapp.generated.resources.you
import dedun.composeapp.generated.resources.your_share_of_this_key_is_stored_unencrypted
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Satoshi
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The group's Wavelength wallet: what it needs before it can exist, the statement that opened it, and who runs it
 * (`docs/group-wavelength-ui.md`). What it holds is this device's own record; every action is a proposal the group signs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupWavelengthWalletScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    chain: Chain,
    activeIdentityStateFlow: StateFlow<Identity?>,
    chatRepository: ChatRepository,
    repository: GroupWavelengthRepository,
    daemonSource: GroupWavelengthDaemonSource,
    onNavigateToRoute: (Route) -> Unit,
    onNavigateBack: () -> Unit,
    /** One entry of the history, in full. Wired to its route in Phase 4 of the transaction-details plan. */
    onOpenEntry: (String) -> Unit = {},
) {
    val viewModel: GroupWavelengthWalletViewModel = viewModel(
        key = "wavelength:$chatRoomId:${GroupWalletStatementEvent.chainName(chain)}",
        factory = GroupWavelengthWalletViewModel.factory(
            activeUserPublicKey = activeUserPublicKey,
            chatRoomId = chatRoomId,
            chain = chain,
            activeIdentityStateFlow = activeIdentityStateFlow,
            chatRepository = chatRepository,
            repository = repository,
            canRunHere = WavelengthPlatform.client != null,
            daemonSource = daemonSource,
        )
    )
    LaunchedEffect(true) { viewModel.initiate() }

    val notify = rememberNotifier(rememberCoroutineScope())
    val couldNotPropose = stringResource(Res.string.couldn_t_propose_this)
    val chainName = GroupWalletStatementEvent.chainName(chain)
    val toSession = { sessionId: String -> onNavigateToRoute(FrostSigningRoute(activeUserPublicKey = activeUserPublicKey, chatRoomId = chatRoomId, sessionId = sessionId)) }

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.group_wavelength_wallet), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { NavigateBackButton(onNavigateBack) }
            )
        }
    ) { padding ->
        ScreenStateTransition(viewModel.uiState) { uiState ->
            GroupWavelengthWalletContent(
                modifier = Modifier.padding(padding),
                state = uiState,
                chain = chain,
                isPending = viewModel.isActionPending.value,
                onOpenOnchainWallet = { onNavigateToRoute(GroupWalletRoute(activeUserPublicKey = activeUserPublicKey, chatRoomId = chatRoomId, relayHint = null, chain = chainName)) },
                onOpenCeremony = { sessionId -> onNavigateToRoute(IcebergCeremonyRoute(activeUserPublicKey = activeUserPublicKey, chatRoomId = chatRoomId, sessionId = sessionId, purpose = IcebergKeySession.PURPOSE_WAVELENGTH, chain = chainName)) },
                onOpenWallet = { viewModel.openWallet(onSession = toSession, onFailure = { notify(couldNotPropose) }) },
                onOpenSession = toSession,
                onNominate = { viewModel.nominateSelf(onSession = toSession, onFailure = { notify(couldNotPropose) }) },
                onStepDown = { viewModel.stepDown(onSession = toSession, onFailure = { notify(couldNotPropose) }) },
                onOpenAdvanced = { onNavigateToRoute(GroupWavelengthAdvancedRoute(activeUserPublicKey = activeUserPublicKey, chatRoomId = chatRoomId, chain = chainName)) },
                onReceive = { onNavigateToRoute(GroupWavelengthReceiveRoute(activeUserPublicKey = activeUserPublicKey, chatRoomId = chatRoomId, chain = chainName)) },
                onPayOut = { onNavigateToRoute(GroupPayoutsRoute(activeUserPublicKey = activeUserPublicKey, chatRoomId = chatRoomId, chain = chainName)) },
                onLeave = { viewModel.proposeLeave(onSession = toSession, onFailure = { notify(couldNotPropose) }) },
                onLeaveEarlier = { viewModel.proposeLeave(onSession = toSession, onFailure = { notify(couldNotPropose) }, earlier = it.wallet) },
                onRunEarlier = { viewModel.nominateSelf(onSession = toSession, onFailure = { notify(couldNotPropose) }, wallet = it.wallet.statement.wallet) },
                onOpenEntry = onOpenEntry,
            )
        }
    }
}

/** Each state of the wallet screen, apart from the view model so that a test can show it one. */
@Composable
internal fun GroupWavelengthWalletContent(
    modifier: Modifier,
    state: GroupWavelengthWalletUIState,
    chain: Chain,
    isPending: Boolean = false,
    onOpenOnchainWallet: () -> Unit = {},
    onOpenCeremony: (sessionId: String?) -> Unit = {},
    onOpenWallet: () -> Unit = {},
    onOpenSession: (sessionId: String) -> Unit = {},
    onNominate: () -> Unit = {},
    onStepDown: () -> Unit = {},
    onOpenAdvanced: () -> Unit = {},
    onReceive: () -> Unit = {},
    onPayOut: () -> Unit = {},
    onLeave: () -> Unit = {},
    onLeaveEarlier: (GroupWavelengthWalletUIState.Earlier) -> Unit = {},
    onRunEarlier: (GroupWavelengthWalletUIState.Earlier) -> Unit = {},
    onOpenEntry: (String) -> Unit = {},
) {
    when (state) {
        is GroupWavelengthWalletUIState.Error -> ErrorState(modifier = modifier, message = state.message, onRetry = null)

        GroupWavelengthWalletUIState.Loading -> LoadingDataIndicator(modifier = modifier.fillMaxWidth())

        is GroupWavelengthWalletUIState.NeedsOnchainWallet -> EmptyState(
            modifier = modifier.readableContent(),
            message = stringResource(Res.string.open_the_group_s_onchain_wallet_first),
            icon = Icons.Default.AccountBalanceWallet,
            action = { Button(onClick = onOpenOnchainWallet) { Text(stringResource(Res.string.group_wallet)) } },
        )

        is GroupWavelengthWalletUIState.NeedsKey -> EmptyState(
            modifier = modifier.readableContent(),
            message = when {
                state.ceremony != null -> stringResource(Res.string.the_group_is_making_its_wavelength_wallet_key)
                !state.canMakeKey -> stringResource(Res.string.a_wavelength_wallet_needs_four_signers)
                else -> stringResource(Res.string.this_group_has_no_wavelength_wallet_key_yet)
            },
            icon = Icons.Default.Key,
            action = if (state.ceremony != null || state.canMakeKey) {
                {
                    Button(onClick = { onOpenCeremony(state.ceremony?.id) }) {
                        Text(stringResource(if (state.ceremony != null) Res.string.see_the_key_ceremony else Res.string.make_a_key))
                    }
                }
            } else null,
        )

        is GroupWavelengthWalletUIState.NotOpened -> Commitment(modifier, state, chain, isPending, onOpenWallet, onOpenSession)

        is GroupWavelengthWalletUIState.WaitingForSeed -> EmptyState(
            modifier = modifier.readableContent(),
            message = stringResource(Res.string.group_wavelength_waiting_for_seed),
            icon = Icons.Default.HourglassEmpty,
        )

        is GroupWavelengthWalletUIState.Refused -> EmptyState(
            modifier = modifier.readableContent(),
            message = stringResource(
                when (state.why) {
                    GroupWavelengthWalletManager.Refusal.CHAIN_REFUSED -> Res.string.group_wavelength_refused_chain
                    GroupWavelengthWalletManager.Refusal.SIZE_NOT_DEPLOYABLE -> Res.string.group_wavelength_refused_size
                    GroupWavelengthWalletManager.Refusal.NOT_KEY_HOLDERS -> Res.string.group_wavelength_refused_holders
                    GroupWavelengthWalletManager.Refusal.NO_CEREMONY, GroupWavelengthWalletManager.Refusal.CEREMONY_DISAGREES -> Res.string.group_wavelength_refused_ceremony
                    GroupWavelengthWalletManager.Refusal.KEYS_DISAGREE -> Res.string.group_wavelength_refused_keys
                    else -> Res.string.group_wavelength_refused_other
                }
            ),
            icon = Icons.Default.ErrorOutline,
        )

        is GroupWavelengthWalletUIState.Opened -> OpenedWallet(modifier, state, chain, isPending, onNominate, onStepDown, onOpenAdvanced, onReceive, onPayOut, onLeave, onOpenWallet, { onOpenCeremony(null) }, onLeaveEarlier, onRunEarlier, onOpenEntry)
    }
}

/** What a group is agreeing to, which should decide whether it wants this wallet: said before the button, in full (D11). */
@Composable
private fun Commitment(
    modifier: Modifier,
    state: GroupWavelengthWalletUIState.NotOpened,
    chain: Chain,
    isPending: Boolean,
    onOpenWallet: () -> Unit,
    onOpenSession: (String) -> Unit,
) {
    LazyColumn(
        modifier = modifier.readableContent().fillMaxSize().padding(MaterialTheme.spacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
    ) {
        item { NetworkNote(chain) }
        item {
            Text(
                stringResource(Res.string.group_wavelength_commitment_devices, (2 * state.key.threshold - 1).toString(), state.key.participantCount.toString()),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        item { Text(stringResource(Res.string.group_wavelength_commitment_operator), style = MaterialTheme.typography.bodyMedium) }
        item { Text(stringResource(Res.string.group_wavelength_commitment_fees), style = MaterialTheme.typography.bodyMedium) }
        item { Text(stringResource(Res.string.group_wavelength_commitment_unreviewed), style = MaterialTheme.typography.bodyMedium) }
        GroupWavelengthRollout.capFor(chain)?.let { cap ->
            item { Text(stringResource(Res.string.group_wavelength_commitment_cap_s, cap.grouped()), style = MaterialTheme.typography.bodyMedium) }
        }
        item {
            Text(stringResource(Res.string.your_share_of_this_key_is_stored_unencrypted), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when (state.blocked) {
            GroupWavelengthWalletUIState.Blocked.TERMS_CHANGED -> item {
                Text(stringResource(Res.string.group_wavelength_terms_changed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            GroupWavelengthWalletUIState.Blocked.NO_OPERATOR_KNOWN -> item {
                Text(stringResource(Res.string.group_wavelength_no_operator_known), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            GroupWavelengthWalletUIState.Blocked.NO_KEY -> Unit
            null -> item {
                val session = state.openingSession
                if (session != null) {
                    OutlinedButton(onClick = { onOpenSession(session) }) { Text(stringResource(Res.string.see_the_proposal)) }
                } else {
                    Button(onClick = onOpenWallet, enabled = !isPending) {
                        Icon(Icons.Default.Waves, contentDescription = Decorative)
                        Spacer(modifier = Modifier.width(MaterialTheme.spacing.relatedGap))
                        Text(stringResource(Res.string.open_a_wavelength_wallet))
                    }
                }
            }
        }
    }
}

/** Said on anything but mainnet, where a figure on this screen is real money. */
@Composable
private fun NetworkNote(chain: Chain) {
    if (!chain.isMainnet()) {
        Text(
            stringResource(Res.string.group_wavelength_test_network_s, GroupWalletStatementEvent.chainName(chain)),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}

@Composable
private fun OpenedWallet(
    modifier: Modifier,
    state: GroupWavelengthWalletUIState.Opened,
    chain: Chain,
    isPending: Boolean,
    onNominate: () -> Unit,
    onStepDown: () -> Unit,
    onOpenAdvanced: () -> Unit,
    onReceive: () -> Unit,
    onPayOut: () -> Unit,
    onLeave: () -> Unit,
    onOpenNew: () -> Unit,
    onMakeKey: () -> Unit,
    onLeaveEarlier: (GroupWavelengthWalletUIState.Earlier) -> Unit,
    onRunEarlier: (GroupWavelengthWalletUIState.Earlier) -> Unit,
    onOpenEntry: (String) -> Unit,
) {
    val paneWidth = listPaneWidthFor(MaterialTheme.breakpoint)
    if (paneWidth != null) {
        // What the money is doing on the left, and what the group is doing on the right.
        Row(modifier = modifier.fillMaxSize().padding(MaterialTheme.spacing.screenMargin), horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.paneGap)) {
            LazyColumn(modifier = Modifier.width(paneWidth), verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap)) {
                moneyItems(state, chain, onReceive, onPayOut, onLeave, onNominate, isPending, onOpenNew, onMakeKey, onLeaveEarlier, onRunEarlier, onOpenEntry)
            }
            LazyColumn(modifier = Modifier.weight(1f).readableContent(), verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap)) {
                groupItems(state, chain, isPending, onNominate, onStepDown, onOpenAdvanced)
            }
        }
    } else {
        LazyColumn(
            modifier = modifier.readableContent().fillMaxSize().padding(MaterialTheme.spacing.screenMargin),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
        ) {
            moneyItems(state, chain, onReceive, onPayOut, onLeave, onNominate, isPending, onOpenNew, onMakeKey, onLeaveEarlier, onRunEarlier, onOpenEntry)
            groupItems(state, chain, isPending, onNominate, onStepDown, onOpenAdvanced)
        }
    }
}

private fun LazyListScope.moneyItems(state: GroupWavelengthWalletUIState.Opened, chain: Chain, onReceive: () -> Unit, onPayOut: () -> Unit, onLeave: () -> Unit, onNominate: () -> Unit, isPending: Boolean, onOpenNew: () -> Unit, onMakeKey: () -> Unit, onLeaveEarlier: (GroupWavelengthWalletUIState.Earlier) -> Unit, onRunEarlier: (GroupWavelengthWalletUIState.Earlier) -> Unit, onOpenEntry: (String) -> Unit) {
    item { NetworkNote(chain) }
    if (state.alarms.isNotEmpty()) item { AlarmCard(state.alarms) }
    if (state.departed.isNotEmpty()) item { DepartedCard(state, isPending, onOpenNew, onMakeKey) }
    item { BalanceCard(state, chain, onReceive, onPayOut, onLeave, isPending) }
    if (state.leaveAsked) item { Text(stringResource(Res.string.group_wavelength_leave_asked), style = MaterialTheme.typography.bodyMedium) }
    item { DeadlineLine(state.expiry) }
    // The way out when whoever runs it has gone: another member takes over, and renewal or leaving carries on from them.
    if (state.isParticipant && !state.isOperator && state.canRunHere && (state.expiry is WalletExpiry.Urgent || state.expiry == WalletExpiry.Expired)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)) {
                Text(stringResource(Res.string.group_wavelength_run_it_yourself), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onNominate, enabled = !isPending) { Text(stringResource(Res.string.run_this_wallet_on_my_device)) }
            }
        }
    }
    historyItems(state, onOpenEntry)
    items(state.earlier, key = { "earlier:${it.wallet.statement.wallet}" }) { earlier -> EarlierWallet(earlier, state, isPending, onLeaveEarlier, onRunEarlier) }
}

/**
 * One history, in place of the three lists this screen drew before: approved payouts, onchain payouts and
 * the operator's activity (`docs/group-wavelength-transaction-details.md`, T1).
 *
 * This device's own entries first, and the operator's unjoined lines under their own heading after them
 * (T12). A line carries no outpoint, so a boarding deposit's can't be joined to the device's record of
 * one; interleaved, a single deposit would read as two transactions.
 */
private fun LazyListScope.historyItems(state: GroupWavelengthWalletUIState.Opened, onOpenEntry: (String) -> Unit) {
    val (theirs, mine) = state.entries.partition { it.source == GroupWavelengthEntry.Source.THE_OPERATOR }
    item { Text(stringResource(Res.string.group_wavelength_history), style = MaterialTheme.typography.titleSmall) }
    if (mine.isEmpty() && theirs.isEmpty()) {
        item {
            EmptyState(
                modifier = Modifier.fillMaxWidth(),
                message = stringResource(Res.string.group_wavelength_history_none),
                icon = Icons.AutoMirrored.Filled.ReceiptLong,
            )
        }
    }
    items(mine, key = { it.id }) { HistoryRow(it, state, onOpenEntry) }
    if (theirs.isNotEmpty()) {
        item {
            Text(
                stringResource(Res.string.group_wavelength_history_operator_only),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(theirs, key = { it.id }) { HistoryRow(it, state, onOpenEntry) }
    }
}

private fun LazyListScope.groupItems(
    state: GroupWavelengthWalletUIState.Opened,
    chain: Chain,
    isPending: Boolean,
    onNominate: () -> Unit,
    onStepDown: () -> Unit,
    onOpenAdvanced: () -> Unit,
) {
    val statement = state.wallet.statement
    item { SignersCard(state) }
    item { OperatorCard(state, isPending, onNominate, onStepDown) }
    item {
        ListItem(
            leadingContent = { Icon(Icons.Default.Key, contentDescription = Decorative) },
            headlineContent = {
                Text(stringResource(Res.string.group_wavelength_signers_s_of_s, statement.threshold.toString(), statement.participants.size.toString(), (2 * statement.threshold - 1).toString()))
            },
            supportingContent = GroupWavelengthRollout.capFor(chain)?.let { cap -> { Text(stringResource(Res.string.group_wavelength_cap_s, cap.grouped())) } },
        )
    }
    item { TextButton(onClick = onOpenAdvanced) { Text(stringResource(Res.string.group_wavelength_advanced)) } }
}

/** Someone who left still holds a share, and Iceberg has no resharing: the money has to move, and only the group can move it. */
@Composable
private fun DepartedCard(state: GroupWavelengthWalletUIState.Opened, isPending: Boolean, onOpenNew: () -> Unit, onMakeKey: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(MaterialTheme.spacing.containerPadding), verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)) {
            Text(stringResource(Res.string.group_wavelength_departed_s, state.departed.size.toString()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            Button(onClick = if (state.canOpenNew) onOpenNew else onMakeKey, enabled = !isPending) {
                Text(stringResource(if (state.canOpenNew) Res.string.open_a_new_wavelength_wallet else Res.string.make_a_key))
            }
        }
    }
}

/** A wallet a new one replaced, until what's in it has left. It keeps its operator until then, and anyone can take it over. */
@Composable
private fun EarlierWallet(earlier: GroupWavelengthWalletUIState.Earlier, state: GroupWavelengthWalletUIState.Opened, isPending: Boolean, onLeave: (GroupWavelengthWalletUIState.Earlier) -> Unit, onRun: (GroupWavelengthWalletUIState.Earlier) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(MaterialTheme.spacing.containerPadding), verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)) {
            Text(stringResource(Res.string.group_wavelength_earlier_s, earlier.wallet.statement.wallet.toString()), style = MaterialTheme.typography.titleSmall)
            Text(
                if (earlier.settledSat > 0) stringResource(Res.string.group_wavelength_earlier_holds_s, Satoshi(earlier.settledSat).grouped()) else stringResource(Res.string.group_wavelength_earlier_empty),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (earlier.leaveAsked) {
                Text(stringResource(Res.string.group_wavelength_leave_asked), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (earlier.settledSat > 0) {
                OutlinedButton(onClick = { onLeave(earlier) }, enabled = !isPending) { Text(stringResource(Res.string.group_wavelength_earlier_leave)) }
            }
            val isMine = earlier.operator?.publicKey == state.userPublicKey.lowercase()
            if (earlier.settledSat > 0 && !isMine && state.canRunHere && state.userPublicKey.lowercase() in earlier.wallet.statement.participants) {
                TextButton(onClick = { onRun(earlier) }, enabled = !isPending) { Text(stringResource(Res.string.run_this_wallet_on_my_device)) }
            }
        }
    }
}

/** What this device itself refused, since a person never sees what it signs, and this is the one thing they're told of it (U4). */
@Composable
private fun AlarmCard(alarms: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
    ) {
        Column(modifier = Modifier.padding(MaterialTheme.spacing.containerPadding), verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)) {
            Text(stringResource(Res.string.group_wavelength_alarm_title), style = MaterialTheme.typography.titleSmall)
            alarms.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/** This device's own figure first, and the operator's beside it, since only one of them has been checked (U2). */
@Composable
private fun BalanceCard(state: GroupWavelengthWalletUIState.Opened, chain: Chain, onReceive: () -> Unit, onPayOut: () -> Unit, onLeave: () -> Unit, isPending: Boolean) {
    var isConfirmingLeave by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(MaterialTheme.spacing.containerPadding), verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)) {
            Text(stringResource(Res.string.group_wavelength_balance_label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val ledger = state.ledger
            if (ledger.isEmpty) {
                Text(stringResource(Res.string.group_wavelength_balance_none), style = MaterialTheme.typography.bodyLarge)
            } else {
                Text(stringResource(Res.string.s_sats, Satoshi(ledger.settledSat).grouped()), style = MaterialTheme.typography.headlineMedium)
            }
            if (ledger.pendingSat > 0) {
                Text(stringResource(Res.string.group_wavelength_pending_s, Satoshi(ledger.pendingSat).grouped()), style = MaterialTheme.typography.bodyMedium)
            }
            if (ledger.heldSat > 0) {
                Text(stringResource(Res.string.group_wavelength_held_s, Satoshi(ledger.heldSat).grouped()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val snapshot = state.snapshot
            if (snapshot == null) {
                Text(stringResource(Res.string.group_wavelength_no_report), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(stringResource(Res.string.group_wavelength_operator_says_s, Satoshi(snapshot.confirmedSat).grouped()), style = MaterialTheme.typography.bodyMedium)
                when (state.comparison?.agreement) {
                    GroupWavelengthReports.Agreement.MATCHES -> Text(stringResource(Res.string.group_wavelength_report_matches), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    GroupWavelengthReports.Agreement.DIFFERS -> Text(stringResource(Res.string.group_wavelength_report_differs), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    GroupWavelengthReports.Agreement.CANNOT_CONFIRM -> Text(stringResource(Res.string.group_wavelength_report_cannot_confirm), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    null -> Unit
                }
                if (snapshot.creditSat > 0) {
                    Text(stringResource(Res.string.group_wavelength_credit_s, Satoshi(snapshot.creditSat).grouped()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            // Whoever runs the wallet makes the address, so there's nothing to ask while nobody does.
            if (state.operator?.publicKey != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap)) {
                    OutlinedButton(onClick = onReceive) {
                        Icon(Icons.AutoMirrored.Filled.CallReceived, contentDescription = Decorative)
                        Spacer(modifier = Modifier.width(MaterialTheme.spacing.relatedGap))
                        Text(stringResource(Res.string.receive))
                    }
                    if (GroupWavelengthRollout.paysOverLightning(chain) || GroupWavelengthRollout.paysOnchain(chain)) {
                        OutlinedButton(onClick = onPayOut) {
                            Icon(Icons.AutoMirrored.Filled.CallMade, contentDescription = Decorative)
                            Spacer(modifier = Modifier.width(MaterialTheme.spacing.relatedGap))
                            Text(stringResource(Res.string.pay_out))
                        }
                    }
                }
                if (state.isParticipant && !state.leaveAsked && !state.ledger.isEmpty) {
                    TextButton(onClick = { isConfirmingLeave = true }, enabled = !isPending) { Text(stringResource(Res.string.group_wavelength_leave)) }
                }
            }
            if (isConfirmingLeave) {
                AlertDialog(
                    onDismissRequest = { isConfirmingLeave = false },
                    title = { Text(stringResource(Res.string.group_wavelength_leave_question)) },
                    text = { Text(stringResource(Res.string.group_wavelength_leave_explainer)) },
                    confirmButton = {
                        TextButton(onClick = {
                            isConfirmingLeave = false
                            onLeave()
                        }) { Text(stringResource(Res.string.group_wavelength_leave_ask)) }
                    },
                    dismissButton = { TextButton(onClick = { isConfirmingLeave = false }) { Text(stringResource(Res.string.cancel)) } },
                )
            }
        }
    }
}

/** When the coins run out of time, by this device's own count: said only when it has one. */
@Composable
private fun DeadlineLine(expiry: WalletExpiry) {
    val (text, isUrgent) = when (expiry) {
        WalletExpiry.Nothing -> return
        is WalletExpiry.Fine -> stringResource(Res.string.group_wavelength_deadline_fine_s, BlockDuration.describe(expiry.blocksLeft)) to false
        is WalletExpiry.Renewing -> stringResource(Res.string.group_wavelength_deadline_renewing_s, BlockDuration.describe(expiry.blocksLeft)) to false
        is WalletExpiry.Urgent -> stringResource(Res.string.group_wavelength_deadline_urgent_s, BlockDuration.describe(expiry.blocksLeft)) to true
        WalletExpiry.Expired -> stringResource(Res.string.group_wavelength_deadline_expired) to true
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = if (isUrgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
}

/**
 * One thing the wallet did. What it was and how much on the headline, where it has got to and when
 * underneath, and what it cost at the end -- the words each kind said before the three lists became one
 * (T1), read off the entry instead of off three different shapes.
 */
@Composable
private fun HistoryRow(entry: GroupWavelengthEntry, state: GroupWavelengthWalletUIState.Opened, onOpen: (String) -> Unit) {
    ListItem(
        modifier = Modifier.clickable { onOpen(entry.id) }.minimumInteractiveComponentSize(),
        leadingContent = { Icon(historyIcon(entry.direction), contentDescription = Decorative) },
        headlineContent = { Text(historyHeadline(entry, state), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val status = historyStatus(entry)
            val at = entry.at?.let { Instant.fromEpochSeconds(it).toFormattedTimeAndDateString() }
                ?: stringResource(Res.string.group_wavelength_history_when_unknown)
            Text(
                text = stringResource(Res.string.wavelength_entry_s_dot_s, status, at),
                color = if (entry.status == GroupWavelengthEntry.Status.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = if (entry.feeSat > 0) {
            { Text(stringResource(Res.string.group_wavelength_activity_fee_s, Satoshi(entry.feeSat).grouped())) }
        } else null,
    )
}

/** The direction, since the headline says it in words too: a row carries no state the text doesn't. */
private fun historyIcon(direction: GroupWavelengthEntry.Direction): ImageVector = when (direction) {
    GroupWavelengthEntry.Direction.IN -> Icons.AutoMirrored.Filled.CallReceived
    GroupWavelengthEntry.Direction.OUT -> Icons.AutoMirrored.Filled.CallMade
    GroupWavelengthEntry.Direction.NONE -> Icons.Default.Autorenew
    GroupWavelengthEntry.Direction.UNKNOWN -> Icons.AutoMirrored.Filled.ReceiptLong
}

@Composable
private fun historyHeadline(entry: GroupWavelengthEntry, state: GroupWavelengthWalletUIState.Opened): String {
    val amount = Satoshi(entry.amountSat).grouped()
    val who = memberName(state.localChatRoom, entry.member, state.userPublicKey)
    return when (entry.kind) {
        GroupWavelengthEntry.Kind.DEPOSIT_ONCHAIN, GroupWavelengthEntry.Kind.DEPOSIT_LIGHTNING ->
            if (entry.member == null) stringResource(Res.string.group_wavelength_history_in_s, amount)
            else stringResource(Res.string.group_wavelength_history_in_s_s, amount, who)
        GroupWavelengthEntry.Kind.PAYOUT_LIGHTNING -> stringResource(Res.string.group_wavelength_payout_s_s, amount, who)
        GroupWavelengthEntry.Kind.PAYOUT_ONCHAIN -> stringResource(Res.string.group_wavelength_onchain_payout_s_s, amount, who)
        GroupWavelengthEntry.Kind.ROUND -> stringResource(Res.string.group_wavelength_history_round)
        GroupWavelengthEntry.Kind.EXIT -> stringResource(Res.string.group_wavelength_history_exit_s, amount)
        // The daemon's own word for what it was, which this device has no better name for.
        GroupWavelengthEntry.Kind.OPERATOR -> {
            val line = (entry.detail as? GroupWavelengthEntry.Detail.OperatorLine)?.line
            stringResource(Res.string.group_wavelength_activity_line_s_s, line?.kind?.replaceFirstChar { it.uppercase() }.orEmpty(), amount)
        }
    }
}

/** Where it has got to, in the words each kind already used. */
@Composable
private fun historyStatus(entry: GroupWavelengthEntry): String = when (entry.kind) {
    GroupWavelengthEntry.Kind.DEPOSIT_ONCHAIN, GroupWavelengthEntry.Kind.DEPOSIT_LIGHTNING -> when (entry.status) {
        GroupWavelengthEntry.Status.WAITING -> stringResource(Res.string.wavelength_entry_waiting_for_payment)
        GroupWavelengthEntry.Status.DONE -> stringResource(Res.string.group_wavelength_coin_settled)
        GroupWavelengthEntry.Status.FAILED -> stringResource(Res.string.wavelength_entry_failed)
        else -> stringResource(Res.string.group_wavelength_coin_pending)
    }

    GroupWavelengthEntry.Kind.PAYOUT_LIGHTNING -> when (entry.status) {
        GroupWavelengthEntry.Status.DONE -> stringResource(Res.string.group_wavelength_payout_paid)
        GroupWavelengthEntry.Status.FAILED -> stringResource(
            Res.string.group_wavelength_payout_failed_s,
            (entry.detail as? GroupWavelengthEntry.Detail.LightningPayout)?.line?.failure.orEmpty(),
        )
        GroupWavelengthEntry.Status.IN_FLIGHT -> stringResource(Res.string.group_wavelength_payout_in_flight)
        GroupWavelengthEntry.Status.EXPIRED -> stringResource(Res.string.group_wavelength_onchain_payout_expired)
        GroupWavelengthEntry.Status.WAITING -> stringResource(Res.string.group_wavelength_payout_approved)
    }

    GroupWavelengthEntry.Kind.PAYOUT_ONCHAIN -> when (entry.status) {
        GroupWavelengthEntry.Status.DONE -> stringResource(Res.string.group_wavelength_onchain_payout_paid_s, Satoshi(entry.feeSat).grouped())
        GroupWavelengthEntry.Status.IN_FLIGHT -> stringResource(Res.string.group_wavelength_onchain_payout_in_a_round)
        GroupWavelengthEntry.Status.EXPIRED -> stringResource(Res.string.group_wavelength_onchain_payout_expired)
        else -> stringResource(Res.string.group_wavelength_payout_approved)
    }

    GroupWavelengthEntry.Kind.ROUND, GroupWavelengthEntry.Kind.EXIT -> when (entry.status) {
        GroupWavelengthEntry.Status.DONE -> stringResource(Res.string.wavelength_entry_succeeded)
        GroupWavelengthEntry.Status.EXPIRED -> stringResource(Res.string.group_wavelength_history_ran_out)
        GroupWavelengthEntry.Status.WAITING -> stringResource(Res.string.group_wavelength_payout_approved)
        else -> stringResource(Res.string.group_wavelength_coin_pending)
    }

    GroupWavelengthEntry.Kind.OPERATOR -> {
        val line = (entry.detail as? GroupWavelengthEntry.Detail.OperatorLine)?.line
        buildString {
            append(line?.status?.replaceFirstChar { it.uppercase() }.orEmpty())
            if (line?.failure?.isNotBlank() == true) append(": ").append(line.failure)
        }
    }
}

/** Who has answered lately, which is what can be known: nobody can say who's online (U5). */
@Composable
private fun SignersCard(state: GroupWavelengthWalletUIState.Opened) {
    val needed = 2 * state.wallet.statement.threshold - 1
    val recent = state.seenRecently()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(MaterialTheme.spacing.containerPadding), verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)) {
            Text(stringResource(Res.string.group_wavelength_signers), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(if (state.enoughSeen) Res.string.group_wavelength_seen_enough_s_s else Res.string.group_wavelength_seen_too_few_s_s, recent.toString(), needed.toString()),
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.enoughSeen) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            )
            state.wallet.statement.participants.forEach { participant ->
                val name = if (participant == state.userPublicKey.lowercase()) stringResource(Res.string.you)
                else state.localChatRoom.localParticipants.firstOrNull { it.participant.participantPublicKey.lowercase() == participant }?.profile?.humanReadableNameOrPubkey() ?: participant.take(16)
                val at = state.seen[participant]
                Column {
                    Text(name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(
                            when {
                                at == null -> Res.string.group_wavelength_seen_never
                                Clock.System.now() - at <= GroupWavelengthWalletUIState.Opened.SEEN_WINDOW -> Res.string.group_wavelength_seen_recently
                                else -> Res.string.group_wavelength_seen_earlier
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Who runs the wallet, and for a signer, whether that could be them. */
@Composable
private fun OperatorCard(state: GroupWavelengthWalletUIState.Opened, isPending: Boolean, onNominate: () -> Unit, onStepDown: () -> Unit) {
    val operatorName = state.operator?.publicKey?.let { key ->
        if (state.isOperator) stringResource(Res.string.you)
        else state.localChatRoom.localParticipants.firstOrNull { it.participant.participantPublicKey.lowercase() == key }?.profile?.humanReadableNameOrPubkey() ?: key.take(16)
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(MaterialTheme.spacing.containerPadding), verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)) {
            Text(
                text = if (operatorName == null) stringResource(Res.string.nobody_runs_this_wallet) else stringResource(Res.string.run_by_s_term_s, operatorName, state.operator!!.term.toString()),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(stringResource(Res.string.group_wavelength_operator_explainer), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.isOperator) DaemonStatusLine(state.daemon)
            when {
                !state.isParticipant -> Unit
                state.isOperator -> OutlinedButton(onClick = onStepDown, enabled = !isPending) { Text(stringResource(Res.string.step_down)) }
                state.canRunHere -> {
                    Text(stringResource(Res.string.group_wavelength_one_wallet_a_device), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onNominate, enabled = !isPending) { Text(stringResource(Res.string.run_this_wallet_on_my_device)) }
                }
                else -> {
                    Text(stringResource(Res.string.group_wavelength_no_runtime), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(Res.string.group_wavelength_leave_this_open), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** What this device's own daemon is doing, on the device that runs it. */
@Composable
private fun DaemonStatusLine(status: GroupWavelengthDaemonStatus) {
    val isTrouble = status is GroupWavelengthDaemonStatus.Refused || status == GroupWavelengthDaemonStatus.Failed
    Text(
        text = when (status) {
            GroupWavelengthDaemonStatus.NotOnThisDevice -> stringResource(Res.string.group_wavelength_daemon_not_on_device)
            GroupWavelengthDaemonStatus.CatchingUp -> stringResource(Res.string.group_wavelength_daemon_catching_up)
            GroupWavelengthDaemonStatus.WaitingForSigners -> stringResource(Res.string.group_wavelength_daemon_waiting_for_signers)
            GroupWavelengthDaemonStatus.Opening -> stringResource(Res.string.group_wavelength_daemon_opening)
            GroupWavelengthDaemonStatus.Recovering -> stringResource(Res.string.group_wavelength_daemon_recovering)
            is GroupWavelengthDaemonStatus.Running -> stringResource(Res.string.group_wavelength_daemon_running)
            GroupWavelengthDaemonStatus.PausedForPersonal -> stringResource(Res.string.group_wavelength_daemon_paused)
            is GroupWavelengthDaemonStatus.BusyWithAnother -> stringResource(Res.string.group_wavelength_daemon_busy_s, status.group)
            GroupWavelengthDaemonStatus.Failed -> stringResource(Res.string.group_wavelength_daemon_failed)
            is GroupWavelengthDaemonStatus.Refused -> stringResource(
                when (status.reason) {
                    GroupWavelengthDaemon.Refusal.NO_SEED -> Res.string.group_wavelength_daemon_no_seed
                    GroupWavelengthDaemon.Refusal.KEYS_DISAGREE -> Res.string.group_wavelength_daemon_keys_disagree
                    GroupWavelengthDaemon.Refusal.SIGNERS_NOT_READY -> Res.string.group_wavelength_daemon_signers_not_ready
                    GroupWavelengthDaemon.Refusal.OPENED_AS_ANOTHER -> Res.string.group_wavelength_daemon_opened_as_another
                    GroupWavelengthDaemon.Refusal.NOT_OPERATOR_OR_BEHIND, GroupWavelengthDaemon.Refusal.NOBODY_RUNS_IT -> Res.string.group_wavelength_daemon_catching_up
                    GroupWavelengthDaemon.Refusal.CHAIN_REFUSED -> Res.string.group_wavelength_refused_chain
                }
            )
        },
        style = MaterialTheme.typography.bodyMedium,
        color = if (isTrouble) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}
