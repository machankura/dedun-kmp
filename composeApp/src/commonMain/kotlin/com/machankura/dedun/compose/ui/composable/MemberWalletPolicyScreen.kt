package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import bennu.composeapp.generated.resources.Res
import bennu.composeapp.generated.resources.a_coin_the_member_moves_starts_waiting_again
import bennu.composeapp.generated.resources.after_s
import bennu.composeapp.generated.resources.ask_the_group
import bennu.composeapp.generated.resources.cancel
import bennu.composeapp.generated.resources.couldn_t_ask_the_group
import bennu.composeapp.generated.resources.doesn_t_guard_members_wallets
import bennu.composeapp.generated.resources.guards_members_wallets
import bennu.composeapp.generated.resources.how_long_before_this_group_can_take_a_member_s_coin
import bennu.composeapp.generated.resources.members_guarded_wallets
import bennu.composeapp.generated.resources.members_on_older_apps_can_only_be_guarded_at_12
import bennu.composeapp.generated.resources.stop
import bennu.composeapp.generated.resources.stop_guarding_new_members_wallets_question
import bennu.composeapp.generated.resources.stop_guarding_new_wallets
import bennu.composeapp.generated.resources.stopped_guarding_new_members_wallets
import bennu.composeapp.generated.resources.wallets_already_guarded_keep_their_delay
import bennu.composeapp.generated.resources.wallets_already_guarded_stay_guarded
import com.machankura.dedun.compose.nostr.wallet.MemberWalletPolicyEvent
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.FrostSigningRepository
import com.machankura.dedun.compose.repository.MemberWalletRepository
import com.machankura.dedun.compose.ui.composable.navigation.routes.FrostSigningRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import com.machankura.dedun.compose.ui.composable.widgets.GuardianDelayChooser
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.composable.widgets.delayText
import com.machankura.dedun.compose.ui.composable.widgets.rememberNotifier
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.MemberWalletPolicyViewModel
import com.machankura.dedun.compose.ui.view.state.MemberWalletPolicyUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import org.jetbrains.compose.resources.stringResource

/**
 * Whether the group guards its members' wallets, at what delay, and closing it
 * again -- `docs/member-wallet-delay.md`, Phase 6.
 *
 * What a change reaches is said before the button: only wallets asked for from
 * now on, never one already guarded, and closing releases nothing. A device
 * that cannot sign sees where the group stands and nothing to press.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemberWalletPolicyScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    initialUIState: MemberWalletPolicyUIState = MemberWalletPolicyUIState.Loading,
    chatRepository: ChatRepository,
    memberWalletRepository: MemberWalletRepository,
    frostSigningRepository: FrostSigningRepository,
    onNavigateToRoute: (Route) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val viewModel: MemberWalletPolicyViewModel = viewModel(
        factory = MemberWalletPolicyViewModel.factory(
            activeUserPublicKey = activeUserPublicKey,
            chatRoomId = chatRoomId,
            initialUIState = initialUIState,
            chatRepository = chatRepository,
            memberWalletRepository = memberWalletRepository,
            frostSigningRepository = frostSigningRepository,
        )
    )

    LaunchedEffect(chatRoomId) {
        if (initialUIState == MemberWalletPolicyUIState.Loading) viewModel.initiate()
    }

    // Read above the handlers, which are not composable; the caller's scope, so
    // the message outlives the screen moving onto the signing session.
    val notify = rememberNotifier(rememberCoroutineScope())
    val couldNotAsk = stringResource(Res.string.couldn_t_ask_the_group)
    val toSession = { sessionId: String ->
        onNavigateToRoute(FrostSigningRoute(activeUserPublicKey = activeUserPublicKey, chatRoomId = chatRoomId, sessionId = sessionId))
    }
    var confirmingClose by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.members_guarded_wallets)) },
                navigationIcon = { NavigateBackButton(onNavigateBack) },
            )
        }
    ) { padding ->
        ScreenStateTransition(viewModel.uiState) { uiState ->
            when (val state = uiState) {
                MemberWalletPolicyUIState.Loading -> LoadingDataIndicator(modifier = Modifier.fillMaxWidth().padding(padding))

                is MemberWalletPolicyUIState.Error -> ErrorState(
                    modifier = Modifier.padding(padding),
                    message = state.message,
                    // Reloading reads the group again, which is what failed.
                    onRetry = viewModel::initiate,
                )

                is MemberWalletPolicyUIState.Loaded -> Column(
                    modifier = Modifier.padding(padding).readableContent().fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(MaterialTheme.spacing.screenMargin),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
                ) {
                    MemberWalletPolicyState(state.policy)

                    if (state.canSign) {
                        GuardianDelayChooser(
                            title = stringResource(Res.string.how_long_before_this_group_can_take_a_member_s_coin),
                            consequence = stringResource(Res.string.a_coin_the_member_moves_starts_waiting_again),
                            input = viewModel.delay.input.value,
                            delay = viewModel.delay.delay(),
                            isCustom = viewModel.delay.isCustom.value,
                            problem = viewModel.delay.problem(),
                            onPreset = viewModel.delay::pick,
                            onCustom = viewModel.delay::useCustom,
                            onInput = viewModel.delay::type,
                        )
                        Text(
                            text = stringResource(Res.string.wallets_already_guarded_keep_their_delay),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(Res.string.members_on_older_apps_can_only_be_guarded_at_12),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        // Disabled, with the reason, while there is nothing to ask:
                        // a delay out of range, or the one the group already has.
                        val problem = viewModel.openProblem()
                        Button(
                            onClick = { viewModel.proposeOpen(onSuccess = toSession, onFailure = { notify(couldNotAsk) }) },
                            enabled = problem == null && !viewModel.isActionPending.value,
                        ) { Text(stringResource(Res.string.ask_the_group)) }
                        if (problem != null && viewModel.delay.problem() == null) {
                            Text(text = problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        if (viewModel.canClose()) {
                            OutlinedButton(
                                onClick = { confirmingClose = true },
                                enabled = !viewModel.isActionPending.value,
                            ) { Text(stringResource(Res.string.stop_guarding_new_wallets)) }
                        }
                    }
                }
            }
        }
    }

    if (confirmingClose) {
        AlertDialog(
            onDismissRequest = { confirmingClose = false },
            title = { Text(stringResource(Res.string.stop_guarding_new_members_wallets_question)) },
            // What closing leaves alone, said where it is decided: the group's leaf
            // is in every address of every wallet it already guards.
            text = { Text(stringResource(Res.string.wallets_already_guarded_stay_guarded)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingClose = false
                    viewModel.proposeClose(onSuccess = toSession, onFailure = { notify(couldNotAsk) })
                }) { Text(stringResource(Res.string.stop)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingClose = false }) { Text(stringResource(Res.string.cancel)) }
            },
        )
    }
}

/**
 * Where the group stands on its members' wallets: guarding them after a delay,
 * never having said so, or having stopped. Shared with the group's page.
 */
@Composable
internal fun MemberWalletPolicyState(policy: MemberWalletPolicyEvent.Policy?) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)) {
        Text(
            text = stringResource(
                when (policy) {
                    is MemberWalletPolicyEvent.Policy.Open -> Res.string.guards_members_wallets
                    is MemberWalletPolicyEvent.Policy.Closed -> Res.string.stopped_guarding_new_members_wallets
                    null -> Res.string.doesn_t_guard_members_wallets
                }
            ),
            style = MaterialTheme.typography.titleMedium,
        )
        if (policy is MemberWalletPolicyEvent.Policy.Open) {
            Text(
                text = stringResource(Res.string.after_s, delayText(policy.delay)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
