package com.machankura.dedun.compose.ui.composable

import dedun.composeapp.generated.resources.the_group_s_own
import dedun.composeapp.generated.resources.whose_deposit_is_this_question
import dedun.composeapp.generated.resources.couldn_t_ask_the_group
import dedun.composeapp.generated.resources.cancel
import dedun.composeapp.generated.resources.correct
import com.machankura.dedun.compose.ui.composable.widgets.rememberNotifier
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.composable.navigation.routes.FrostSigningRoute
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.machankura.dedun.compose.database.model.DepositRow
import com.machankura.dedun.compose.managers.DepositLedger
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.DepositRepository
import com.machankura.dedun.compose.text.grouped
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.composable.widgets.EmptyState
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.DepositsViewModel
import com.machankura.dedun.compose.ui.view.state.DepositsUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.altogether_s_sats
import dedun.composeapp.generated.resources.as_the_operator_reported
import dedun.composeapp.generated.resources.by_their_address
import dedun.composeapp.generated.resources.by_their_invoice
import dedun.composeapp.generated.resources.deposits
import dedun.composeapp.generated.resources.from_s
import dedun.composeapp.generated.resources.nothing_has_been_deposited_yet
import dedun.composeapp.generated.resources.paid_to_them_s_sats
import dedun.composeapp.generated.resources.s_dot_s
import dedun.composeapp.generated.resources.s_put_in_s_sats
import dedun.composeapp.generated.resources.s_sats
import dedun.composeapp.generated.resources.attribute
import dedun.composeapp.generated.resources.attributed_by_the_group
import dedun.composeapp.generated.resources.unattributed
import dedun.composeapp.generated.resources.unattributed_s_sats
import dedun.composeapp.generated.resources.waiting_for_a_confirmation
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Satoshi
import org.jetbrains.compose.resources.stringResource

/**
 * Every deposit into the group, newest first: how much, whose, and how that is known -- by the
 * member's own address, by their invoice, as the operator reported, or signed by the group -- under
 * each member's tally and the total. `docs/member-deposits.md`, Phase 8.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DepositsScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    chain: Chain,
    initialUIState: DepositsUIState = DepositsUIState.Loading,
    chatRepository: ChatRepository,
    depositRepository: DepositRepository,
    onNavigateToRoute: (Route) -> Unit = {},
    onNavigateBack: () -> Unit,
) {
    val viewModel: DepositsViewModel = viewModel(
        factory = DepositsViewModel.factory(chatRoomId, chain, initialUIState, chatRepository, depositRepository)
    )
    LaunchedEffect(chatRoomId, chain) {
        if (initialUIState == DepositsUIState.Loading) viewModel.initiate()
    }
    val notify = rememberNotifier(rememberCoroutineScope())
    val couldNotAsk = stringResource(Res.string.couldn_t_ask_the_group)
    // The deposit whose attribution is being chosen, by key.
    var attributing by remember { mutableStateOf<String?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.deposits)) },
                navigationIcon = { NavigateBackButton(onNavigateBack) },
            )
        }
    ) { padding ->
        ScreenStateTransition(viewModel.uiState) { uiState ->
            when (val state = uiState) {
                DepositsUIState.Loading -> LoadingDataIndicator(modifier = Modifier.fillMaxWidth().padding(padding))
                is DepositsUIState.Error -> ErrorState(modifier = Modifier.padding(padding), message = state.message, onRetry = viewModel::initiate)
                is DepositsUIState.Loaded -> LazyColumn(
                    modifier = Modifier.padding(padding).readableContent().fillMaxSize().padding(MaterialTheme.spacing.screenMargin),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap),
                ) {
                    item { Tallies(state.tallies, state.names) }
                    item { HorizontalDivider() }
                    if (state.deposits.isEmpty()) {
                        item { EmptyState(message = stringResource(Res.string.nothing_has_been_deposited_yet), icon = Icons.Default.Savings) }
                    }
                    items(state.deposits, key = { it.depositKey }) { deposit ->
                        DepositListRow(
                            deposit = deposit,
                            from = deposit.member?.let { state.names[it] ?: it.take(8) },
                            // A device that holds a share can ask the group to say whose a deposit
                            // is, or to correct what it said. docs/member-deposits.md, Phase 9.
                            onAttribute = if (state.canSign && !viewModel.isActionPending.value) {
                                { attributing = deposit.depositKey }
                            } else null,
                        )
                    }
                }
            }
        }
    }

    val loaded = viewModel.uiState as? DepositsUIState.Loaded
    val deposit = attributing?.let { key -> loaded?.deposits?.firstOrNull { it.depositKey == key } }
    if (loaded != null && deposit != null) {
        AttributeDialog(
            depositors = loaded.depositors,
            names = loaded.names,
            current = deposit.member,
            onDismiss = { attributing = null },
            onChoose = { member ->
                attributing = null
                viewModel.attribute(
                    depositKey = deposit.depositKey,
                    member = member,
                    onSuccess = { sessionId -> onNavigateToRoute(FrostSigningRoute(activeUserPublicKey = activeUserPublicKey, chatRoomId = chatRoomId, sessionId = sessionId)) },
                    onFailure = { notify(couldNotAsk) },
                )
            },
        )
    }
}

/** Whose a deposit is: one of the depositors, or the group's own. The current answer is marked. */
@Composable
private fun AttributeDialog(
    depositors: List<HexKey>,
    names: Map<HexKey, String>,
    current: HexKey?,
    onDismiss: () -> Unit,
    onChoose: (HexKey?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.whose_deposit_is_this_question)) },
        text = {
            androidx.compose.foundation.layout.Column {
                depositors.forEach { member ->
                    TextButton(onClick = { onChoose(member) }, enabled = member != current) {
                        Text("@" + (names[member] ?: member.take(8)))
                    }
                }
                TextButton(onClick = { onChoose(null) }) { Text(stringResource(Res.string.the_group_s_own)) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

/** Each member's tally, then the unattributed, then the total they add up to. */
@Composable
internal fun Tallies(tallies: DepositLedger.Tallies, names: Map<HexKey, String>) {
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)) {
        tallies.members.values.sortedByDescending { it.depositedMsat }.forEach { tally ->
            val name = "@" + (names[tally.member] ?: tally.member.take(8))
            Text(stringResource(Res.string.s_put_in_s_sats, name, sats(tally.depositedMsat)), style = MaterialTheme.typography.bodyLarge)
            if (tally.paidOutMsat > 0) {
                Text(
                    stringResource(Res.string.paid_to_them_s_sats, sats(tally.paidOutMsat)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (tallies.unattributedMsat > 0) {
            Text(
                stringResource(Res.string.unattributed_s_sats, sats(tallies.unattributedMsat)),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(stringResource(Res.string.altogether_s_sats, sats(tallies.totalMsat)), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun DepositListRow(deposit: DepositRow, from: String?, onAttribute: (() -> Unit)?) {
    ListItem(
        trailingContent = onAttribute?.let { attribute ->
            {
                TextButton(onClick = attribute) {
                    Text(stringResource(if (deposit.member == null) Res.string.attribute else Res.string.correct))
                }
            }
        },
        leadingContent = {
            Icon(if (deposit.source == DepositRow.LIGHTNING) Icons.Default.Bolt else Icons.Default.Savings, contentDescription = Decorative)
        },
        headlineContent = { Text(stringResource(Res.string.s_sats, sats(deposit.amountMsat))) },
        supportingContent = {
            val whose = from?.let { stringResource(Res.string.from_s, "@$it") } ?: stringResource(Res.string.unattributed)
            val how = when {
                deposit.source == DepositRow.ONCHAIN && deposit.confirmedAt == null -> stringResource(Res.string.waiting_for_a_confirmation)
                else -> when (deposit.attributedBy) {
                    DepositRow.BY_BRANCH -> stringResource(Res.string.by_their_address)
                    DepositRow.BY_INVOICE -> stringResource(Res.string.by_their_invoice)
                    DepositRow.BY_OPERATOR -> stringResource(Res.string.as_the_operator_reported)
                    DepositRow.BY_GROUP -> stringResource(Res.string.attributed_by_the_group)
                    else -> null
                }
            }
            Text(how?.let { stringResource(Res.string.s_dot_s, whose, it) } ?: whose)
        },
    )
}

/** Msat in whole sats, grouped: a tally is read in sats, as the wallet is. */
internal fun sats(msat: Long): String = Satoshi(msat / 1000).grouped()
