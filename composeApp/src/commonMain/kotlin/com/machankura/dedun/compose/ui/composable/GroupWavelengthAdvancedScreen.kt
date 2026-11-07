package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.machankura.dedun.compose.database.model.GroupArkVtxo
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.GroupWavelengthDaemonSource
import com.machankura.dedun.compose.repository.GroupWavelengthRepository
import com.machankura.dedun.compose.text.grouped
import com.machankura.dedun.compose.text.groupedBlocks
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.GroupWavelengthWalletViewModel
import com.machankura.dedun.compose.ui.view.state.GroupWavelengthWalletUIState
import com.machankura.dedun.compose.wallet.wavelength.WavelengthPlatform
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthRollout
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.group_wavelength_advanced
import dedun.composeapp.generated.resources.group_wavelength_advanced_coins
import dedun.composeapp.generated.resources.group_wavelength_advanced_exit_delay_s
import dedun.composeapp.generated.resources.group_wavelength_advanced_fee_budget_s_s
import dedun.composeapp.generated.resources.group_wavelength_advanced_labels_s
import dedun.composeapp.generated.resources.group_wavelength_advanced_no_coins
import dedun.composeapp.generated.resources.group_wavelength_advanced_operator_s
import dedun.composeapp.generated.resources.group_wavelength_advanced_round_fee_s
import dedun.composeapp.generated.resources.group_wavelength_advanced_terms
import dedun.composeapp.generated.resources.group_wavelength_advanced_untested
import dedun.composeapp.generated.resources.group_wavelength_advanced_untested_exit
import dedun.composeapp.generated.resources.group_wavelength_advanced_untested_phones
import dedun.composeapp.generated.resources.group_wavelength_advanced_untested_refund
import dedun.composeapp.generated.resources.group_wavelength_coin_expires_s
import dedun.composeapp.generated.resources.group_wavelength_coin_held
import dedun.composeapp.generated.resources.group_wavelength_coin_pending
import dedun.composeapp.generated.resources.group_wavelength_coin_s_sats
import dedun.composeapp.generated.resources.group_wavelength_coin_settled
import dedun.composeapp.generated.resources.group_wavelength_coin_spent
import dedun.composeapp.generated.resources.group_wavelength_exit_title
import dedun.composeapp.generated.resources.group_wavelength_only_device_s
import dedun.composeapp.generated.resources.group_wavelength_only_operator_s
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Satoshi
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.stringResource

/** What a device knows of the wallet in detail, including what nobody has tried yet. The one screen that says VTXO. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupWavelengthAdvancedScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    chain: Chain,
    activeIdentityStateFlow: StateFlow<Identity?>,
    chatRepository: ChatRepository,
    repository: GroupWavelengthRepository,
    daemonSource: GroupWavelengthDaemonSource,
    onOpenExit: () -> Unit = {},
    onNavigateBack: () -> Unit,
) {
    val viewModel: GroupWavelengthWalletViewModel = viewModel(
        key = "wavelength-advanced:$chatRoomId:${GroupWalletStatementEvent.chainName(chain)}",
        factory = GroupWavelengthWalletViewModel.factory(activeUserPublicKey, chatRoomId, chain, activeIdentityStateFlow, chatRepository, repository, WavelengthPlatform.client != null, daemonSource),
    )
    LaunchedEffect(true) { viewModel.initiate() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.group_wavelength_advanced), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { NavigateBackButton(onNavigateBack) },
            )
        }
    ) { padding ->
        when (val state = viewModel.uiState) {
            is GroupWavelengthWalletUIState.Opened -> GroupWavelengthAdvancedContent(Modifier.padding(padding), state, chain, onOpenExit)
            is GroupWavelengthWalletUIState.Error -> ErrorState(modifier = Modifier.padding(padding), message = state.message, onRetry = null)
            else -> LoadingDataIndicator(modifier = Modifier.fillMaxWidth().padding(padding))
        }
    }
}

@Composable
internal fun GroupWavelengthAdvancedContent(modifier: Modifier, state: GroupWavelengthWalletUIState.Opened, chain: Chain = state.wallet.statement.chain, onOpenExit: () -> Unit = {}) {
    val statement = state.wallet.statement
    val comparison = state.comparison
    LazyColumn(
        modifier = modifier.readableContent().fillMaxSize().padding(MaterialTheme.spacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
    ) {
        item { Text(stringResource(Res.string.group_wavelength_advanced_coins), style = MaterialTheme.typography.titleSmall) }
        if (state.coins.isEmpty()) {
            item { Text(stringResource(Res.string.group_wavelength_advanced_no_coins), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(state.coins, key = { it.outpoint }) { CoinRow(it) }
        comparison?.onlyTheOperator?.takeIf { it.isNotEmpty() }?.let { only ->
            item { Text(stringResource(Res.string.group_wavelength_only_operator_s, only.joinToString { it.outpoint.take(12) }), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        }
        comparison?.onlyThisDevice?.takeIf { it.isNotEmpty() }?.let { only ->
            item { Text(stringResource(Res.string.group_wavelength_only_device_s, only.joinToString { it.outpoint.take(12) }), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        }
        item { Text(stringResource(Res.string.group_wavelength_advanced_terms), style = MaterialTheme.typography.titleSmall) }
        item { SelectionContainer { Text(stringResource(Res.string.group_wavelength_advanced_operator_s, statement.arkOperatorKey), style = MaterialTheme.typography.bodyMedium) } }
        item { Text(stringResource(Res.string.group_wavelength_advanced_exit_delay_s, statement.policy.exitDelay.toString()), style = MaterialTheme.typography.bodyMedium) }
        item { Text(stringResource(Res.string.group_wavelength_advanced_round_fee_s, Satoshi(statement.policy.maxRoundFeeSat).grouped()), style = MaterialTheme.typography.bodyMedium) }
        item { Text(stringResource(Res.string.group_wavelength_advanced_fee_budget_s_s, Satoshi(state.feesSat).grouped(), Satoshi(statement.policy.maxMonthlyFeeSat).grouped()), style = MaterialTheme.typography.bodyMedium) }
        item { Text(stringResource(Res.string.group_wavelength_advanced_labels_s, state.labelsLeft.toString()), style = MaterialTheme.typography.bodyMedium) }
        item { Text(stringResource(Res.string.group_wavelength_advanced_untested), style = MaterialTheme.typography.titleSmall) }
        item { Text(stringResource(Res.string.group_wavelength_advanced_untested_exit), style = MaterialTheme.typography.bodyMedium) }
        item { Text(stringResource(Res.string.group_wavelength_advanced_untested_refund), style = MaterialTheme.typography.bodyMedium) }
        item { Text(stringResource(Res.string.group_wavelength_advanced_untested_phones), style = MaterialTheme.typography.bodyMedium) }
        if (GroupWavelengthRollout.offersUnilateralExit(chain) && state.isOperator) {
            item { TextButton(onClick = onOpenExit) { Text(stringResource(Res.string.group_wavelength_exit_title)) } }
        }
    }
}

@Composable
private fun CoinRow(coin: GroupArkVtxo) {
    ListItem(
        headlineContent = { Text(stringResource(Res.string.group_wavelength_coin_s_sats, Satoshi(coin.amountSat).grouped())) },
        supportingContent = {
            Text(
                buildString {
                    append(
                        stringResource(
                            when {
                                coin.spent -> Res.string.group_wavelength_coin_spent
                                !coin.settled -> Res.string.group_wavelength_coin_pending
                                coin.reservedBy != null -> Res.string.group_wavelength_coin_held
                                else -> Res.string.group_wavelength_coin_settled
                            }
                        )
                    )
                    coin.expiresAtHeight?.takeIf { !coin.spent }?.let { append(". ").append(stringResource(Res.string.group_wavelength_coin_expires_s, it.groupedBlocks())) }
                }
            )
        },
        overlineContent = { SelectionContainer { Text(coin.outpoint, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) } },
    )
}
