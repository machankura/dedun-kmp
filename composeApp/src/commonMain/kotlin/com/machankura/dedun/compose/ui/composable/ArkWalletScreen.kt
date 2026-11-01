package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.machankura.dedun.compose.extensions.toFormattedTimeAndDateString
import com.machankura.dedun.compose.text.grouped
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.composable.widgets.EmptyState
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
import com.machankura.dedun.compose.ui.view.model.ArkWalletUIState
import com.machankura.dedun.compose.ui.view.model.ArkWalletViewModel
import com.machankura.dedun.compose.wallet.ark.ArkDirection
import com.machankura.dedun.compose.wallet.ark.ArkExpiry
import com.machankura.dedun.compose.wallet.ark.ArkMovement
import com.machankura.dedun.compose.wallet.ark.ArkMovementStatus
import com.machankura.dedun.compose.wallet.ark.ArkRail
import com.machankura.dedun.compose.wallet.ark.ArkUnavailability
import com.machankura.dedun.compose.wallet.ark.ArkWalletRollout
import com.machankura.dedun.compose.wallet.ark.IdentityArk
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.ark_movement_amount
import dedun.composeapp.generated.resources.ark_movement_canceled
import dedun.composeapp.generated.resources.ark_movement_emergency_exit
import dedun.composeapp.generated.resources.ark_movement_failed
import dedun.composeapp.generated.resources.ark_movement_fee
import dedun.composeapp.generated.resources.ark_movement_finished
import dedun.composeapp.generated.resources.ark_movement_from
import dedun.composeapp.generated.resources.ark_movement_minus_s_sats
import dedun.composeapp.generated.resources.ark_movement_moved_from_onchain
import dedun.composeapp.generated.resources.ark_movement_moved_onchain
import dedun.composeapp.generated.resources.ark_movement_not_found
import dedun.composeapp.generated.resources.ark_movement_payment
import dedun.composeapp.generated.resources.ark_movement_payment_details
import dedun.composeapp.generated.resources.ark_movement_payment_hash
import dedun.composeapp.generated.resources.ark_movement_pending
import dedun.composeapp.generated.resources.ark_movement_plus_s_sats
import dedun.composeapp.generated.resources.ark_movement_received_onchain
import dedun.composeapp.generated.resources.ark_movement_received_over_ark
import dedun.composeapp.generated.resources.ark_movement_received_over_lightning
import dedun.composeapp.generated.resources.ark_movement_renewed
import dedun.composeapp.generated.resources.ark_movement_s_dot_s
import dedun.composeapp.generated.resources.ark_movement_select_a_payment
import dedun.composeapp.generated.resources.ark_movement_sent_onchain
import dedun.composeapp.generated.resources.ark_movement_sent_over_ark
import dedun.composeapp.generated.resources.ark_movement_sent_over_lightning
import dedun.composeapp.generated.resources.ark_movement_started
import dedun.composeapp.generated.resources.ark_movement_status
import dedun.composeapp.generated.resources.ark_movement_succeeded
import dedun.composeapp.generated.resources.ark_movement_to
import dedun.composeapp.generated.resources.ark_wallet_could_not_open
import dedun.composeapp.generated.resources.ark_wallet_could_not_read
import dedun.composeapp.generated.resources.ark_wallet_mainnet_cap_s_sats
import dedun.composeapp.generated.resources.ark_wallet_no_payments_yet
import dedun.composeapp.generated.resources.ark_wallet_onchain_s_sats
import dedun.composeapp.generated.resources.ark_wallet_opening
import dedun.composeapp.generated.resources.ark_wallet_pending_s_sats
import dedun.composeapp.generated.resources.ark_wallet_restoring
import dedun.composeapp.generated.resources.ark_wallet_some_coins_not_restored
import dedun.composeapp.generated.resources.ark_wallet_test_network_s
import dedun.composeapp.generated.resources.ark_wallet_turn_on
import dedun.composeapp.generated.resources.copied
import dedun.composeapp.generated.resources.copy
import dedun.composeapp.generated.resources.s_sats
import dedun.composeapp.generated.resources.wallet
import org.jetbrains.compose.resources.stringResource

/**
 * The profile's own Ark wallet -- `docs/bark-wallet.md`, Phase 4: the balance, what is
 * pending, the earliest deadline, and the history. From the expanded breakpoint up, a
 * payment opens beside the list; below it, on a screen of its own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArkWalletScreen(
    identityArk: IdentityArk,
    onOpenMovement: (Long) -> Unit,
    onNavigateBack: () -> Unit,
    actions: @Composable (ArkWalletUIState.Loaded) -> Unit = {},
) {
    val viewModel: ArkWalletViewModel = viewModel(factory = ArkWalletViewModel.factory(identityArk))
    LaunchedEffect(true) { viewModel.refresh() }
    var consenting by remember { mutableStateOf(false) }
    if (consenting) {
        ArkConsentSheet(onTurnOn = { consenting = false; viewModel.turnOn() }, onDismiss = { consenting = false })
    }

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.wallet), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { NavigateBackButton(onNavigateBack) },
            )
        },
    ) { padding ->
        ArkWalletContent(
            state = viewModel.uiState,
            modifier = Modifier.padding(padding),
            onOpenMovement = onOpenMovement,
            onRetry = viewModel::retry,
            onTurnOn = { consenting = true },
            actions = actions,
        )
    }
}

/** The screen's body, for a state: split from the screen so a test can draw each state without a holder. */
@Composable
fun ArkWalletContent(
    state: ArkWalletUIState,
    modifier: Modifier = Modifier,
    onOpenMovement: (Long) -> Unit,
    onRetry: () -> Unit,
    onTurnOn: () -> Unit,
    actions: @Composable (ArkWalletUIState.Loaded) -> Unit = {},
) {
    ScreenStateTransition(state) { uiState ->
        when (val current = uiState) {
            ArkWalletUIState.Loading -> LoadingDataIndicator(modifier = modifier.fillMaxWidth())
            ArkWalletUIState.Opening -> LoadingDataIndicator(modifier = modifier.fillMaxWidth(), text = stringResource(Res.string.ark_wallet_opening))
            ArkWalletUIState.Restoring -> LoadingDataIndicator(modifier = modifier.fillMaxWidth(), text = stringResource(Res.string.ark_wallet_restoring))
            is ArkWalletUIState.Failed -> ErrorState(
                modifier = modifier,
                message = stringResource(if (current.opened) Res.string.ark_wallet_could_not_read else Res.string.ark_wallet_could_not_open),
                onRetry = onRetry,
            )
            is ArkWalletUIState.Unavailable -> EmptyState(
                modifier = modifier.readableContent(),
                message = arkUnavailableText(current.reason),
                icon = Icons.Default.AccountBalanceWallet,
                action = if (current.reason == ArkUnavailability.NotConsented) {
                    { Button(onClick = onTurnOn) { Text(stringResource(Res.string.ark_wallet_turn_on)) } }
                } else null,
            )
            is ArkWalletUIState.Loaded -> ArkWalletLoaded(current, modifier, onOpenMovement, actions)
        }
    }
}

@Composable
private fun ArkWalletLoaded(
    state: ArkWalletUIState.Loaded,
    modifier: Modifier,
    onOpenMovement: (Long) -> Unit,
    actions: @Composable (ArkWalletUIState.Loaded) -> Unit,
) {
    val listPaneWidth = listPaneWidthFor(MaterialTheme.breakpoint)
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    val open: (Long) -> Unit = { id -> if (listPaneWidth != null) selected = id else onOpenMovement(id) }

    if (listPaneWidth == null) {
        LazyColumn(
            modifier = modifier.fillMaxSize().readableContent(),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap),
        ) {
            arkWalletHeader(state, actions)
            arkHistoryItems(state.movements, open)
        }
    } else {
        Row(modifier = modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.paneGap)) {
            LazyColumn(
                modifier = Modifier.width(listPaneWidth).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap),
            ) {
                arkWalletHeader(state, actions)
                arkHistoryItems(state.movements, open)
            }
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                val movement = state.movements.firstOrNull { it.id == selected }
                if (movement != null) {
                    ArkMovementDetail(movement)
                } else {
                    EmptyState(message = stringResource(Res.string.ark_movement_select_a_payment), icon = Icons.Default.ReceiptLong)
                }
            }
        }
    }
}

private fun LazyListScope.arkWalletHeader(state: ArkWalletUIState.Loaded, actions: @Composable (ArkWalletUIState.Loaded) -> Unit) {
    item(key = "balance") {
        Column(
            modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.containerPadding),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap),
        ) {
            Text(stringResource(Res.string.s_sats, state.balance.spendable.grouped()), style = MaterialTheme.typography.displaySmall)
            if (state.balance.pending.sat > 0) {
                Text(stringResource(Res.string.ark_wallet_pending_s_sats, state.balance.pending.grouped()), style = MaterialTheme.typography.bodyMedium)
            }
            if (state.balance.onchain.sat > 0) {
                Text(stringResource(Res.string.ark_wallet_onchain_s_sats, state.balance.onchain.grouped()), style = MaterialTheme.typography.bodyMedium)
            }
            arkExpiryText(state.expiry)?.let { text ->
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (state.expiry) {
                        is ArkExpiry.RenewSoon, ArkExpiry.Expired -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (!state.network.isMainnet) {
                Text(stringResource(Res.string.ark_wallet_test_network_s, state.network.segment), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ArkWalletRollout.capFor(state.network)?.let { cap ->
                Text(stringResource(Res.string.ark_wallet_mainnet_cap_s_sats, cap.grouped()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            actions(state)
        }
    }
    if (state.recoveryIncomplete) {
        item(key = "recovery") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
            ) {
                Text(stringResource(Res.string.ark_wallet_some_coins_not_restored), modifier = Modifier.padding(MaterialTheme.spacing.containerPadding))
            }
        }
    }
    item(key = "divider") { HorizontalDivider() }
}

private fun LazyListScope.arkHistoryItems(movements: List<ArkMovement>, onOpen: (Long) -> Unit) {
    if (movements.isEmpty()) {
        item(key = "empty") { EmptyState(message = stringResource(Res.string.ark_wallet_no_payments_yet), icon = Icons.Default.ReceiptLong) }
        return
    }
    items(movements, key = { "ark-movement-" + it.id }) { movement ->
        ListItem(
            modifier = Modifier.clickable { onOpen(movement.id) }.minimumInteractiveComponentSize(),
            headlineContent = { Text(arkMovementTitle(movement), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(arkMovementSubtitle(movement), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingContent = {
                Text(
                    arkSignedAmount(movement),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (movement.status == ArkMovementStatus.Failed || movement.status == ArkMovementStatus.Canceled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else MaterialTheme.colorScheme.onSurface,
                )
            },
        )
    }
}

@Composable
internal fun arkMovementTitle(movement: ArkMovement): String = stringResource(
    when (movement.rail) {
        ArkRail.Ark -> if (movement.direction == ArkDirection.Incoming) Res.string.ark_movement_received_over_ark else Res.string.ark_movement_sent_over_ark
        ArkRail.Lightning -> if (movement.direction == ArkDirection.Incoming) Res.string.ark_movement_received_over_lightning else Res.string.ark_movement_sent_over_lightning
        ArkRail.Onchain -> if (movement.direction == ArkDirection.Incoming) Res.string.ark_movement_received_onchain else Res.string.ark_movement_sent_onchain
        ArkRail.Offboard -> Res.string.ark_movement_moved_onchain
        ArkRail.Board -> Res.string.ark_movement_moved_from_onchain
        ArkRail.Renewal -> Res.string.ark_movement_renewed
        ArkRail.Exit -> Res.string.ark_movement_emergency_exit
        ArkRail.Other -> Res.string.ark_movement_payment
    }
)

@Composable
private fun arkMovementStatusText(status: ArkMovementStatus): String = stringResource(
    when (status) {
        ArkMovementStatus.Pending -> Res.string.ark_movement_pending
        ArkMovementStatus.Succeeded -> Res.string.ark_movement_succeeded
        ArkMovementStatus.Failed -> Res.string.ark_movement_failed
        ArkMovementStatus.Canceled -> Res.string.ark_movement_canceled
    }
)

/** The status, then when -- *Pending · 14:02 2 Nov 2026*. */
@Composable
private fun arkMovementSubtitle(movement: ArkMovement): String {
    val status = arkMovementStatusText(movement.status)
    val time = (movement.completedAt ?: movement.createdAt)?.toFormattedTimeAndDateString() ?: return status
    return stringResource(Res.string.ark_movement_s_dot_s, status, time)
}

@Composable
private fun arkSignedAmount(movement: ArkMovement): String = when (movement.direction) {
    ArkDirection.Incoming -> stringResource(Res.string.ark_movement_plus_s_sats, movement.amount.grouped())
    ArkDirection.Outgoing -> stringResource(Res.string.ark_movement_minus_s_sats, movement.amount.grouped())
    ArkDirection.Internal -> stringResource(Res.string.s_sats, movement.amount.grouped())
}

/** One payment, every field it has, the long ones copyable. */
@Composable
fun ArkMovementDetail(movement: ArkMovement?, modifier: Modifier = Modifier) {
    if (movement == null) {
        EmptyState(modifier = modifier.readableContent(), message = stringResource(Res.string.ark_movement_not_found), icon = Icons.Default.ReceiptLong)
        return
    }
    val clipboard = LocalClipboardManager.current
    val notify = rememberNotifier(rememberCoroutineScope())
    val copied = stringResource(Res.string.copied)
    Column(
        modifier = modifier.fillMaxSize().readableContent().verticalScroll(rememberScrollState()).padding(MaterialTheme.spacing.containerPadding),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
    ) {
        Text(arkMovementTitle(movement), style = MaterialTheme.typography.headlineSmall)
        Text(arkSignedAmount(movement), style = MaterialTheme.typography.displaySmall)
        DetailLine(stringResource(Res.string.ark_movement_status), arkMovementStatusText(movement.status))
        DetailLine(stringResource(Res.string.ark_movement_amount), stringResource(Res.string.s_sats, movement.amount.grouped()))
        if (movement.fee.sat > 0) DetailLine(stringResource(Res.string.ark_movement_fee), stringResource(Res.string.s_sats, movement.fee.grouped()))
        movement.createdAt?.let { DetailLine(stringResource(Res.string.ark_movement_started), it.toFormattedTimeAndDateString()) }
        movement.completedAt?.let { DetailLine(stringResource(Res.string.ark_movement_finished), it.toFormattedTimeAndDateString()) }
        movement.counterparty?.let { counterparty ->
            val label = stringResource(if (movement.direction == ArkDirection.Incoming) Res.string.ark_movement_from else Res.string.ark_movement_to)
            DetailLine(label, counterparty, onCopy = { clipboard.setText(AnnotatedString(counterparty)); notify(copied) })
        }
        movement.paymentHash?.let { hash ->
            DetailLine(stringResource(Res.string.ark_movement_payment_hash), hash, onCopy = { clipboard.setText(AnnotatedString(hash)); notify(copied) })
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String, onCopy: (() -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space25)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
        onCopy?.let { TextButton(onClick = it) { Text(stringResource(Res.string.copy)) } }
    }
}

/** A payment below the expanded breakpoint: [ArkMovementDetail] on a pushed screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArkMovementScreen(
    identityArk: IdentityArk,
    movementId: Long,
    onNavigateBack: () -> Unit,
) {
    val viewModel: ArkWalletViewModel = viewModel(factory = ArkWalletViewModel.factory(identityArk))
    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.ark_movement_payment_details)) },
                navigationIcon = { NavigateBackButton(onNavigateBack) },
            )
        },
    ) { padding ->
        ScreenStateTransition(viewModel.uiState) { uiState ->
            when (val state = uiState) {
                is ArkWalletUIState.Loaded -> ArkMovementDetail(state.movements.firstOrNull { it.id == movementId }, Modifier.padding(padding))
                is ArkWalletUIState.Failed -> ErrorState(modifier = Modifier.padding(padding), onRetry = viewModel::retry)
                else -> LoadingDataIndicator(modifier = Modifier.fillMaxWidth().padding(padding))
            }
        }
    }
}
