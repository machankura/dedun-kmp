package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.GroupWavelengthRepository
import com.machankura.dedun.compose.text.grouped
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.composable.widgets.EmptyState
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.composable.widgets.QRCodeView
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.composable.widgets.rememberNotifier
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.GroupWavelengthReceiveViewModel
import com.machankura.dedun.compose.ui.view.state.GroupWavelengthReceiveUIState
import com.machankura.dedun.compose.wallet.bip21Uri
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.address_copied
import dedun.composeapp.generated.resources.copy_address
import dedun.composeapp.generated.resources.group_wavelength_receive_address
import dedun.composeapp.generated.resources.group_wavelength_receive_ask
import dedun.composeapp.generated.resources.group_wavelength_receive_at_cap
import dedun.composeapp.generated.resources.group_wavelength_receive_checked
import dedun.composeapp.generated.resources.group_wavelength_receive_confirmations_s
import dedun.composeapp.generated.resources.group_wavelength_receive_couldnt_ask
import dedun.composeapp.generated.resources.group_wavelength_receive_fee_s
import dedun.composeapp.generated.resources.group_wavelength_receive_intro
import dedun.composeapp.generated.resources.group_wavelength_receive_limits_s_s
import dedun.composeapp.generated.resources.group_wavelength_receive_nobody_runs_it
import dedun.composeapp.generated.resources.group_wavelength_receive_not_a_depositor
import dedun.composeapp.generated.resources.group_wavelength_receive_room_s
import dedun.composeapp.generated.resources.group_wavelength_receive_title
import dedun.composeapp.generated.resources.group_wavelength_receive_waiting
import dedun.composeapp.generated.resources.group_wavelength_test_network_s
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Satoshi
import org.jetbrains.compose.resources.stringResource

/** A member's address for paying the group's Wavelength wallet, with what it takes and what it costs said before anything's sent. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupWavelengthReceiveScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    chain: Chain,
    chatRepository: ChatRepository,
    repository: GroupWavelengthRepository,
    onNavigateBack: () -> Unit,
) {
    val viewModel: GroupWavelengthReceiveViewModel = viewModel(
        key = "wavelength-receive:$chatRoomId:${GroupWalletStatementEvent.chainName(chain)}",
        factory = GroupWavelengthReceiveViewModel.factory(activeUserPublicKey, chatRoomId, chain, chatRepository, repository),
    )
    LaunchedEffect(true) { viewModel.initiate() }
    val notify = rememberNotifier(rememberCoroutineScope())
    val couldNotAsk = stringResource(Res.string.group_wavelength_receive_couldnt_ask)
    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.group_wavelength_receive_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { NavigateBackButton(onNavigateBack) },
            )
        }
    ) { padding ->
        ScreenStateTransition(viewModel.uiState) { uiState ->
            GroupWavelengthReceiveContent(Modifier.padding(padding), uiState, chain, notify, onAsk = { viewModel.ask(onFailure = { notify(couldNotAsk) }) })
        }
    }
}

@Composable
internal fun GroupWavelengthReceiveContent(modifier: Modifier, state: GroupWavelengthReceiveUIState, chain: Chain, notify: (String) -> Unit = {}, onAsk: () -> Unit = {}) {
    when (state) {
        GroupWavelengthReceiveUIState.Loading -> LoadingDataIndicator(modifier = modifier.fillMaxWidth())
        is GroupWavelengthReceiveUIState.Error -> ErrorState(modifier = modifier, message = state.message, onRetry = null)
        GroupWavelengthReceiveUIState.NotADepositor -> EmptyState(modifier = modifier.readableContent(), message = stringResource(Res.string.group_wavelength_receive_not_a_depositor), icon = Icons.Default.AccountBalanceWallet)
        GroupWavelengthReceiveUIState.NobodyRunsIt -> EmptyState(modifier = modifier.readableContent(), message = stringResource(Res.string.group_wavelength_receive_nobody_runs_it), icon = Icons.Default.Waves)
        GroupWavelengthReceiveUIState.AtTheCap -> EmptyState(modifier = modifier.readableContent(), message = stringResource(Res.string.group_wavelength_receive_at_cap), icon = Icons.Default.Waves)
        GroupWavelengthReceiveUIState.Waiting -> EmptyState(modifier = modifier.readableContent(), message = stringResource(Res.string.group_wavelength_receive_waiting), icon = Icons.Default.HourglassEmpty)
        GroupWavelengthReceiveUIState.CanAsk -> EmptyState(
            modifier = modifier.readableContent(),
            message = stringResource(Res.string.group_wavelength_receive_intro),
            icon = Icons.Default.Waves,
            action = { Button(onClick = onAsk) { Text(stringResource(Res.string.group_wavelength_receive_ask)) } },
        )
        is GroupWavelengthReceiveUIState.Ready -> Address(modifier, state, chain, notify)
    }
}

@Composable
private fun Address(modifier: Modifier, state: GroupWavelengthReceiveUIState.Ready, chain: Chain, notify: (String) -> Unit) {
    val clipboardManager = LocalClipboardManager.current
    val copied = stringResource(Res.string.address_copied)
    LazyColumn(
        modifier = modifier.readableContent().fillMaxSize().padding(MaterialTheme.spacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!chain.isMainnet()) {
            item { Text(stringResource(Res.string.group_wavelength_test_network_s, GroupWalletStatementEvent.chainName(chain)), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary) }
        }
        item { Text(stringResource(Res.string.group_wavelength_receive_address), style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth()) }
        item { Text(stringResource(Res.string.group_wavelength_receive_checked), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth()) }
        item { QRCodeView(bip21Uri(state.address)) }
        item { SelectionContainer { Text(state.address, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)) } }
        item {
            TextButton(onClick = {
                clipboardManager.setText(buildAnnotatedString { append(state.address) })
                notify(copied)
            }) {
                Icon(Icons.Default.ContentCopy, contentDescription = Decorative)
                Spacer(modifier = Modifier.width(MaterialTheme.spacing.relatedGap))
                Text(stringResource(Res.string.copy_address))
            }
        }
        state.terms?.let { terms ->
            item { Line(stringResource(Res.string.group_wavelength_receive_confirmations_s, terms.boardingConfirmations.toString())) }
            item { Line(stringResource(Res.string.group_wavelength_receive_limits_s_s, Satoshi(terms.minBoardingSat).grouped(), Satoshi(terms.maxVtxoSat).grouped())) }
        }
        state.roomLeftSat?.let { item { Line(stringResource(Res.string.group_wavelength_receive_room_s, Satoshi(it).grouped())) } }
        item { Line(stringResource(Res.string.group_wavelength_receive_fee_s, Satoshi(state.maxRoundFeeSat).grouped())) }
    }
}

@Composable
private fun Line(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
}
