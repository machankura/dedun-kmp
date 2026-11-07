package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.machankura.dedun.compose.database.model.IcebergKeySession
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.managers.GroupWavelengthWalletManager
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.GroupWavelengthRepository
import com.machankura.dedun.compose.text.grouped
import com.machankura.dedun.compose.ui.composable.navigation.routes.FrostSigningRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.GroupWalletRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.IcebergCeremonyRoute
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
import com.machankura.dedun.compose.ui.view.model.GroupWavelengthWalletViewModel
import com.machankura.dedun.compose.ui.view.state.GroupWavelengthWalletUIState
import com.machankura.dedun.compose.wallet.wavelength.WavelengthPlatform
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthRollout
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.a_wavelength_wallet_needs_four_signers
import dedun.composeapp.generated.resources.couldn_t_propose_this
import dedun.composeapp.generated.resources.group_wallet
import dedun.composeapp.generated.resources.group_wavelength_cap_s
import dedun.composeapp.generated.resources.group_wavelength_commitment_cap_s
import dedun.composeapp.generated.resources.group_wavelength_commitment_devices
import dedun.composeapp.generated.resources.group_wavelength_commitment_fees
import dedun.composeapp.generated.resources.group_wavelength_commitment_operator
import dedun.composeapp.generated.resources.group_wavelength_commitment_unreviewed
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
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.stringResource

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
    onNavigateToRoute: (Route) -> Unit,
    onNavigateBack: () -> Unit,
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

        is GroupWavelengthWalletUIState.Opened -> OpenedWallet(modifier, state, chain, isPending, onNominate, onStepDown)
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
) {
    val statement = state.wallet.statement
    LazyColumn(
        modifier = modifier.readableContent().fillMaxSize().padding(MaterialTheme.spacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
    ) {
        item { NetworkNote(chain) }
        item { OperatorCard(state, isPending, onNominate, onStepDown) }
        item {
            ListItem(
                leadingContent = { Icon(Icons.Default.Key, contentDescription = Decorative) },
                headlineContent = {
                    Text(
                        stringResource(
                            Res.string.group_wavelength_signers_s_of_s,
                            statement.threshold.toString(),
                            statement.participants.size.toString(),
                            (2 * statement.threshold - 1).toString(),
                        )
                    )
                },
                supportingContent = GroupWavelengthRollout.capFor(chain)?.let { cap -> { Text(stringResource(Res.string.group_wavelength_cap_s, cap.grouped())) } },
            )
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
