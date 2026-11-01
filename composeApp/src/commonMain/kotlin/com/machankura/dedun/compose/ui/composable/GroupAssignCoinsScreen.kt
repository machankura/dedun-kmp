package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.viewmodel.compose.viewModel
import com.machankura.dedun.compose.managers.MemberBalances
import com.machankura.dedun.compose.nostr.wallet.CoinAssignmentEvent
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.PayoutRepository
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
import com.machankura.dedun.compose.ui.view.model.GroupAssignCoinsViewModel
import com.machankura.dedun.compose.ui.view.state.GroupAssignCoinsUIState
import com.machankura.dedun.compose.wallet.CoinOwnership
import com.machankura.dedun.compose.wallet.GroupOnchainWallets
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.a_share_is_at_least_s_sats
import dedun.composeapp.generated.resources.asked_the_group_to_assign_this_coin
import dedun.composeapp.generated.resources.assign
import dedun.composeapp.generated.resources.assign_coins
import dedun.composeapp.generated.resources.before_deposits_were_attributed_or_from_a_closed_channel
import dedun.composeapp.generated.resources.change_from_an_earlier_spend
import dedun.composeapp.generated.resources.couldn_t_ask_the_group
import dedun.composeapp.generated.resources.every_coin_is_somebody_s
import dedun.composeapp.generated.resources.nobody_deposits_with_this_group_yet
import dedun.composeapp.generated.resources.on_a_branch_this_device_can_t_place
import dedun.composeapp.generated.resources.s_s_share
import dedun.composeapp.generated.resources.s_sats
import dedun.composeapp.generated.resources.s_sats_left_to_assign
import dedun.composeapp.generated.resources.s_sats_too_many_assigned
import dedun.composeapp.generated.resources.sats
import dedun.composeapp.generated.resources.the_group_said_it_is_no_member_s
import dedun.composeapp.generated.resources.until_the_group_says_whose_it_is
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Satoshi
import org.jetbrains.compose.resources.stringResource

/**
 * The coins of the group's wallet nobody owns yet -- from before deposits were attributed, from a
 * closed channel, or change from an earlier spend -- each with a share for every depositor, and
 * asking the group to sign them. The wallet signs only payouts, so until the group says whose a
 * coin is nobody can be paid it. `docs/payouts-only.md`, Phase 3.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupAssignCoinsScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    chain: Chain,
    initialUIState: GroupAssignCoinsUIState = GroupAssignCoinsUIState.Loading,
    chatRepository: ChatRepository,
    payoutRepository: PayoutRepository,
    wallets: GroupOnchainWallets?,
    onNavigateToRoute: (Route) -> Unit = {},
    onNavigateBack: () -> Unit,
) {
    val viewModel: GroupAssignCoinsViewModel = viewModel(
        factory = GroupAssignCoinsViewModel.factory(chatRoomId, chain, initialUIState, chatRepository, payoutRepository, wallets)
    )
    LaunchedEffect(chatRoomId, chain) {
        if (initialUIState == GroupAssignCoinsUIState.Loading) viewModel.initiate()
    }
    val notify = rememberNotifier(rememberCoroutineScope())
    val asked = stringResource(Res.string.asked_the_group_to_assign_this_coin)
    val couldNotAsk = stringResource(Res.string.couldn_t_ask_the_group)

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.assign_coins)) },
                navigationIcon = { NavigateBackButton(onNavigateBack) },
            )
        }
    ) { padding ->
        ScreenStateTransition(viewModel.uiState) { uiState ->
            when (val state = uiState) {
                GroupAssignCoinsUIState.Loading -> LoadingDataIndicator(modifier = Modifier.fillMaxWidth().padding(padding))
                is GroupAssignCoinsUIState.Error -> ErrorState(modifier = Modifier.padding(padding), message = state.message, onRetry = viewModel::initiate)
                is GroupAssignCoinsUIState.Loaded -> when {
                    state.unowned.isEmpty() -> EmptyState(
                        modifier = Modifier.padding(padding),
                        message = stringResource(Res.string.every_coin_is_somebody_s),
                        icon = Icons.Default.Savings,
                    )
                    state.depositors.isEmpty() -> EmptyState(
                        modifier = Modifier.padding(padding),
                        message = stringResource(Res.string.nobody_deposits_with_this_group_yet),
                        icon = Icons.Default.Savings,
                    )
                    else -> LazyColumn(
                        modifier = Modifier.padding(padding).readableContent().fillMaxSize().padding(MaterialTheme.spacing.screenMargin),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
                    ) {
                        item {
                            Text(
                                stringResource(Res.string.until_the_group_says_whose_it_is),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        items(state.unowned, key = { "${it.utxo.utxo.outPoint.txid}:${it.utxo.utxo.outPoint.index}" }) { coin ->
                            UnownedCoinCard(
                                coin = coin,
                                depositors = state.depositors,
                                names = state.names,
                                viewModel = viewModel,
                                onAssign = if (state.canSign && !viewModel.isActionPending.value) {
                                    {
                                        viewModel.assign(
                                            coin = coin,
                                            onSuccess = { sessionId ->
                                                notify(asked)
                                                onNavigateToRoute(FrostSigningRoute(activeUserPublicKey = activeUserPublicKey, chatRoomId = chatRoomId, sessionId = sessionId))
                                            },
                                            onFailure = { notify(couldNotAsk) },
                                        )
                                    }
                                } else null,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One coin nobody owns: its amount, where it came from, a share for each depositor, and what is left. */
@Composable
private fun UnownedCoinCard(
    coin: MemberBalances.UnownedCoin,
    depositors: List<HexKey>,
    names: Map<HexKey, String>,
    viewModel: GroupAssignCoinsViewModel,
    onAssign: (() -> Unit)?,
) {
    val fields = viewModel.fieldsOf(coin)
    val amount = coin.utxo.amount.sat
    val shares = viewModel.sharesOf(coin)
    val problem = shares?.let { GroupAssignCoinsViewModel.problemWith(amount, it) }
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.containerPadding),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap),
        ) {
            Text(stringResource(Res.string.s_sats, coin.utxo.amount.grouped()), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(
                    when (coin.why) {
                        CoinOwnership.Why.RECEIVE -> Res.string.before_deposits_were_attributed_or_from_a_closed_channel
                        CoinOwnership.Why.CHANGE -> Res.string.change_from_an_earlier_spend
                        CoinOwnership.Why.THE_GROUPS -> Res.string.the_group_said_it_is_no_member_s
                        CoinOwnership.Why.UNKNOWN_BRANCH -> Res.string.on_a_branch_this_device_can_t_place
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            depositors.forEach { member ->
                fields[member]?.let { field ->
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        state = field,
                        outputTransformation = GroupedSats,
                        lineLimits = TextFieldLineLimits.SingleLine,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        label = { Text(stringResource(Res.string.s_s_share, "@" + (names[member] ?: member.take(8)))) },
                        suffix = { Text(stringResource(Res.string.sats)) },
                    )
                }
            }
            when (problem) {
                is GroupAssignCoinsViewModel.Problem.LeftToAssign ->
                    Text(stringResource(Res.string.s_sats_left_to_assign, Satoshi(problem.sats).grouped()), style = MaterialTheme.typography.bodyMedium)
                is GroupAssignCoinsViewModel.Problem.TooMany ->
                    Text(stringResource(Res.string.s_sats_too_many_assigned, Satoshi(problem.sats).grouped()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                GroupAssignCoinsViewModel.Problem.BelowDust ->
                    Text(stringResource(Res.string.a_share_is_at_least_s_sats, Satoshi(CoinAssignmentEvent.MIN_SHARE_SAT).grouped()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                null -> Unit
            }
            onAssign?.let { assign ->
                Button(onClick = assign, enabled = shares != null && problem == null) { Text(stringResource(Res.string.assign)) }
            }
        }
    }
}
