package com.machankura.dedun.compose.ui.composable

import dedun.composeapp.generated.resources.payout_destination_saved_the_group_is_asked_to_open_your_deposits
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.composable.navigation.routes.FrostSigningRoute
import com.machankura.dedun.compose.repository.DepositRepository
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.dedun.compose.nostr.wallet.Sendable
import com.machankura.dedun.compose.repository.PayoutDestinationRepository
import com.machankura.dedun.compose.text.grouped
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.composable.widgets.rememberNotifier
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.GroupPayoutDestinationViewModel
import com.machankura.dedun.compose.ui.view.model.GroupPayoutDestinationViewModel.LightningProblem
import com.machankura.dedun.compose.ui.view.model.GroupPayoutDestinationViewModel.OnchainProblem
import com.machankura.dedun.compose.ui.view.state.GroupPayoutDestinationUIState
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.accepts_s_to_s_sats
import dedun.composeapp.generated.resources.cancel
import dedun.composeapp.generated.resources.couldn_t_check_the_address
import dedun.composeapp.generated.resources.couldn_t_reach_that_address_s_server
import dedun.composeapp.generated.resources.couldn_t_remove_the_payout_destination
import dedun.composeapp.generated.resources.couldn_t_save_the_payout_destination
import dedun.composeapp.generated.resources.enter_a_lightning_address
import dedun.composeapp.generated.resources.lightning_address
import dedun.composeapp.generated.resources.lightning_address_from_your_profile
import dedun.composeapp.generated.resources.members_will_see_you_haven_t_said
import dedun.composeapp.generated.resources.onchain_address_optional
import dedun.composeapp.generated.resources.only_a_group_pays_its_members
import dedun.composeapp.generated.resources.payout_destination_removed
import dedun.composeapp.generated.resources.payout_destination_saved
import dedun.composeapp.generated.resources.payout_destination_saved_not_checked
import dedun.composeapp.generated.resources.remove
import dedun.composeapp.generated.resources.remove_where_this_group_pays_you_question
import dedun.composeapp.generated.resources.save
import dedun.composeapp.generated.resources.that_address_doesn_t_take_payments
import dedun.composeapp.generated.resources.that_address_s_server_answered_with_something_that_can_t_be_paid
import dedun.composeapp.generated.resources.that_address_s_server_doesn_t_know_this_name
import dedun.composeapp.generated.resources.that_address_s_server_refused
import dedun.composeapp.generated.resources.that_address_s_server_said_s
import dedun.composeapp.generated.resources.that_isn_t_a_lightning_address
import dedun.composeapp.generated.resources.that_isn_t_an_address_on_s
import dedun.composeapp.generated.resources.that_s_a_bip_353_name
import dedun.composeapp.generated.resources.that_s_an_address_on_another_network
import dedun.composeapp.generated.resources.that_s_an_invoice_not_a_lightning_address
import dedun.composeapp.generated.resources.that_s_an_lnurl_not_a_lightning_address
import dedun.composeapp.generated.resources.that_s_an_offer_not_a_lightning_address
import dedun.composeapp.generated.resources.that_s_one_of_this_group_s_own_addresses
import dedun.composeapp.generated.resources.the_group_pays_you_here_any_number_of_times
import dedun.composeapp.generated.resources.the_group_sees_this_address
import dedun.composeapp.generated.resources.this_address_has_been_used_before
import dedun.composeapp.generated.resources.this_device_can_t_reach_an_onion_address
import dedun.composeapp.generated.resources.this_profile_can_t_say_where_to_be_paid
import dedun.composeapp.generated.resources.where_this_group_pays_you
import fr.acinq.bitcoin.Chain
import org.jetbrains.compose.resources.stringResource

/**
 * Where the group pays this profile: a Lightning address, required, and an onchain address
 * that has never been paid, if they want one. `docs/group-payout-destination.md`, Phase 4.
 *
 * *Save* checks both before anything leaves the device, and a refusal is said under the
 * field it is about. A profile that cannot sign sees where it stands and nothing to press.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupPayoutDestinationScreen(
    activeUserPublicKey: String,
    chatRoomId: String,
    chain: Chain,
    initialUIState: GroupPayoutDestinationUIState = GroupPayoutDestinationUIState.Loading,
    repository: PayoutDestinationRepository,
    depositRepository: DepositRepository = DepositRepository.NO_OP,
    /** Onto the signing session that opens the member's deposits, in place of this screen. */
    onNavigateToRouteAndPopUpInclusive: (Route) -> Unit = {},
    onNavigateBack: () -> Unit,
) {
    val viewModel: GroupPayoutDestinationViewModel = viewModel(
        factory = GroupPayoutDestinationViewModel.factory(
            chatRoomId = chatRoomId,
            chain = chain,
            initialUIState = initialUIState,
            repository = repository,
            depositRepository = depositRepository,
        )
    )

    LaunchedEffect(chatRoomId, chain) {
        if (initialUIState == GroupPayoutDestinationUIState.Loading) viewModel.initiate()
    }

    // Read above the handlers, which are not composable; the caller's scope, so a message
    // shown as the member leaves the screen is not cancelled with it.
    val notify = rememberNotifier(rememberCoroutineScope())
    val saved = stringResource(Res.string.payout_destination_saved)
    val savedNotChecked = stringResource(Res.string.payout_destination_saved_not_checked)
    val savedAndAsked = stringResource(Res.string.payout_destination_saved_the_group_is_asked_to_open_your_deposits)
    val removed = stringResource(Res.string.payout_destination_removed)
    val couldNotSave = stringResource(Res.string.couldn_t_save_the_payout_destination)
    val couldNotRemove = stringResource(Res.string.couldn_t_remove_the_payout_destination)
    var confirmingRemove by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.where_this_group_pays_you)) },
                navigationIcon = { NavigateBackButton(onNavigateBack) },
            )
        }
    ) { padding ->
        ScreenStateTransition(viewModel.uiState) { uiState ->
            when (val state = uiState) {
                GroupPayoutDestinationUIState.Loading -> LoadingDataIndicator(modifier = Modifier.fillMaxWidth().padding(padding))

                is GroupPayoutDestinationUIState.Error -> ErrorState(
                    modifier = Modifier.padding(padding),
                    message = state.message,
                    // Reloading reads the group again, which is what failed.
                    onRetry = viewModel::initiate,
                )

                is GroupPayoutDestinationUIState.Loaded -> Column(
                    modifier = Modifier.padding(padding).readableContent().fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(MaterialTheme.spacing.screenMargin),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
                ) {
                    val standing = state.standing
                    when {
                        !standing.isGroup -> Text(stringResource(Res.string.only_a_group_pays_its_members), style = MaterialTheme.typography.bodyLarge)

                        else -> {
                            val editable = standing.canSet && !viewModel.isWorking
                            OutlinedTextField(
                                modifier = Modifier.fillMaxWidth(),
                                state = viewModel.lightningField,
                                readOnly = !editable,
                                lineLimits = TextFieldLineLimits.SingleLine,
                                isError = viewModel.lightningProblem != null,
                                label = {
                                    Text(
                                        stringResource(
                                            if (viewModel.lightningFromProfile) Res.string.lightning_address_from_your_profile else Res.string.lightning_address
                                        )
                                    )
                                },
                                supportingText = {
                                    Text(viewModel.lightningProblem?.let { lightningProblemText(it) } ?: stringResource(Res.string.the_group_pays_you_here_any_number_of_times))
                                },
                            )
                            standing.current?.sendable?.let { sendable ->
                                Text(
                                    text = sendableText(sendable),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            OutlinedTextField(
                                modifier = Modifier.fillMaxWidth(),
                                state = viewModel.onchainField,
                                readOnly = !editable,
                                lineLimits = TextFieldLineLimits.SingleLine,
                                isError = viewModel.onchainProblem != null,
                                label = { Text(stringResource(Res.string.onchain_address_optional)) },
                                supportingText = {
                                    Text(viewModel.onchainProblem?.let { onchainProblemText(it, chain) } ?: stringResource(Res.string.the_group_sees_this_address))
                                },
                            )

                            if (!standing.canSet) {
                                Text(
                                    text = stringResource(Res.string.this_profile_can_t_say_where_to_be_paid),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                if (viewModel.isWorking) LoadingDataIndicator(fillScreen = false)
                                Button(
                                    onClick = {
                                        viewModel.save(
                                            onSaved = { notChecked, depositsSession ->
                                                if (depositsSession != null) {
                                                    notify(if (notChecked) savedNotChecked else savedAndAsked)
                                                    onNavigateToRouteAndPopUpInclusive(
                                                        FrostSigningRoute(activeUserPublicKey = activeUserPublicKey, chatRoomId = chatRoomId, sessionId = depositsSession)
                                                    )
                                                } else {
                                                    notify(if (notChecked) savedNotChecked else saved)
                                                }
                                            },
                                            onFailure = { notify(couldNotSave) },
                                        )
                                    },
                                    enabled = viewModel.canSave(),
                                ) { Text(stringResource(Res.string.save)) }
                                if (viewModel.canRemove()) {
                                    OutlinedButton(
                                        onClick = { confirmingRemove = true },
                                        enabled = !viewModel.isWorking,
                                    ) { Text(stringResource(Res.string.remove)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmingRemove) {
        AlertDialog(
            onDismissRequest = { confirmingRemove = false },
            title = { Text(stringResource(Res.string.remove_where_this_group_pays_you_question)) },
            text = { Text(stringResource(Res.string.members_will_see_you_haven_t_said)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingRemove = false
                    viewModel.remove(onRemoved = { notify(removed) }, onFailure = { notify(couldNotRemove) })
                }) { Text(stringResource(Res.string.remove)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingRemove = false }) { Text(stringResource(Res.string.cancel)) }
            },
        )
    }
}

/** *Accepts 1 to 100,000 sats.*: what a Lightning address said it takes, in whole sats. */
@Composable
internal fun sendableText(sendable: Sendable): String =
    stringResource(Res.string.accepts_s_to_s_sats, sendable.min.truncateToSatoshi().grouped(), sendable.max.truncateToSatoshi().grouped())

@Composable
private fun lightningProblemText(problem: LightningProblem): String = when (problem) {
    LightningProblem.Empty -> stringResource(Res.string.enter_a_lightning_address)
    LightningProblem.NotAnAddress -> stringResource(Res.string.that_isn_t_a_lightning_address)
    LightningProblem.Lnurl -> stringResource(Res.string.that_s_an_lnurl_not_a_lightning_address)
    LightningProblem.Offer -> stringResource(Res.string.that_s_an_offer_not_a_lightning_address)
    LightningProblem.Invoice -> stringResource(Res.string.that_s_an_invoice_not_a_lightning_address)
    LightningProblem.Bip353 -> stringResource(Res.string.that_s_a_bip_353_name)
    LightningProblem.OnionDomain -> stringResource(Res.string.this_device_can_t_reach_an_onion_address)
    LightningProblem.Unreachable -> stringResource(Res.string.couldn_t_reach_that_address_s_server)
    LightningProblem.NotFound -> stringResource(Res.string.that_address_s_server_doesn_t_know_this_name)
    is LightningProblem.Refused -> problem.reason?.takeIf { it.isNotBlank() }
        ?.let { stringResource(Res.string.that_address_s_server_said_s, it) }
        ?: stringResource(Res.string.that_address_s_server_refused)
    LightningProblem.NotAPayRequest -> stringResource(Res.string.that_address_doesn_t_take_payments)
    LightningProblem.Malformed -> stringResource(Res.string.that_address_s_server_answered_with_something_that_can_t_be_paid)
}

@Composable
private fun onchainProblemText(problem: OnchainProblem, chain: Chain): String = when (problem) {
    OnchainProblem.NotAnAddress -> stringResource(Res.string.that_isn_t_an_address_on_s, GroupWalletStatementEvent.chainName(chain))
    OnchainProblem.OtherNetwork -> stringResource(Res.string.that_s_an_address_on_another_network, GroupWalletStatementEvent.chainName(chain))
    OnchainProblem.Used -> stringResource(Res.string.this_address_has_been_used_before)
    OnchainProblem.Unknown -> stringResource(Res.string.couldn_t_check_the_address)
    OnchainProblem.TheGroups -> stringResource(Res.string.that_s_one_of_this_group_s_own_addresses)
}
