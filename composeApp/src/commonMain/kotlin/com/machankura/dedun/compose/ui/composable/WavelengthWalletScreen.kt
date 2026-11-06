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
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AccountBalanceWallet
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
import com.machankura.dedun.compose.ui.view.model.WavelengthWalletUIState
import com.machankura.dedun.compose.ui.view.model.WavelengthWalletViewModel
import com.machankura.dedun.compose.wallet.wavelength.IdentityWavelength
import com.machankura.dedun.compose.wallet.wavelength.WalletExpiry
import com.machankura.dedun.compose.wallet.wavelength.WalletUnavailability
import com.machankura.dedun.compose.wallet.wavelength.WavelengthWalletRollout
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.copied
import dedun.composeapp.generated.resources.copy
import dedun.composeapp.generated.resources.receive
import dedun.composeapp.generated.resources.s_sats
import dedun.composeapp.generated.resources.send
import dedun.composeapp.generated.resources.wallet
import dedun.composeapp.generated.resources.wavelength_advanced
import dedun.composeapp.generated.resources.wavelength_amount
import dedun.composeapp.generated.resources.wavelength_could_not_open
import dedun.composeapp.generated.resources.wavelength_credit_explainer
import dedun.composeapp.generated.resources.wavelength_credit_s_sats
import dedun.composeapp.generated.resources.wavelength_entry_deposit
import dedun.composeapp.generated.resources.wavelength_entry_exit
import dedun.composeapp.generated.resources.wavelength_entry_failed
import dedun.composeapp.generated.resources.wavelength_entry_failure
import dedun.composeapp.generated.resources.wavelength_entry_fee
import dedun.composeapp.generated.resources.wavelength_entry_finished
import dedun.composeapp.generated.resources.wavelength_entry_minus_s_sats
import dedun.composeapp.generated.resources.wavelength_entry_not_found
import dedun.composeapp.generated.resources.wavelength_entry_payment
import dedun.composeapp.generated.resources.wavelength_entry_payment_details
import dedun.composeapp.generated.resources.wavelength_entry_payment_hash
import dedun.composeapp.generated.resources.wavelength_entry_pending
import dedun.composeapp.generated.resources.wavelength_entry_plus_s_sats
import dedun.composeapp.generated.resources.wavelength_entry_received
import dedun.composeapp.generated.resources.wavelength_entry_s_dot_s
import dedun.composeapp.generated.resources.wavelength_entry_select_a_payment
import dedun.composeapp.generated.resources.wavelength_entry_sent
import dedun.composeapp.generated.resources.wavelength_entry_started
import dedun.composeapp.generated.resources.wavelength_entry_status
import dedun.composeapp.generated.resources.wavelength_entry_succeeded
import dedun.composeapp.generated.resources.wavelength_entry_to
import dedun.composeapp.generated.resources.wavelength_entry_waiting_for_payment
import dedun.composeapp.generated.resources.wavelength_mainnet_cap_s_sats
import dedun.composeapp.generated.resources.wavelength_no_payments_yet
import dedun.composeapp.generated.resources.wavelength_opening
import dedun.composeapp.generated.resources.wavelength_pending_s_sats
import dedun.composeapp.generated.resources.wavelength_preimage
import dedun.composeapp.generated.resources.wavelength_recovered_s_coins
import dedun.composeapp.generated.resources.wavelength_restoring
import dedun.composeapp.generated.resources.wavelength_syncing
import dedun.composeapp.generated.resources.wavelength_test_network_s
import dedun.composeapp.generated.resources.wavelength_turn_on
import dedun.composeapp.generated.resources.wavelength_wrong_identity
import engineering.lightning.wavelength.Network
import engineering.lightning.wavelength.model.Entry
import engineering.lightning.wavelength.model.EntryKind
import engineering.lightning.wavelength.model.EntryPhase
import engineering.lightning.wavelength.model.EntryStatus
import fr.acinq.bitcoin.Satoshi
import org.jetbrains.compose.resources.stringResource

/**
 * The profile's own wallet -- `docs/wavelength-wallet.md`, Phase 5: the balance, what is pending
 * and the history. From the expanded breakpoint up, a payment opens beside the list; below it, on
 * a screen of its own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WavelengthWalletScreen(
    identityWavelength: IdentityWavelength,
    onOpenEntry: (String) -> Unit,
    onReceive: () -> Unit,
    onSend: () -> Unit,
    onAdvanced: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val viewModel: WavelengthWalletViewModel = viewModel(factory = WavelengthWalletViewModel.factory(identityWavelength))
    LaunchedEffect(true) { viewModel.refresh() }
    var consenting by remember { mutableStateOf(false) }
    if (consenting) {
        WavelengthConsentSheet(onTurnOn = { consenting = false; viewModel.turnOn() }, onDismiss = { consenting = false })
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
        WavelengthWalletContent(
            state = viewModel.uiState,
            modifier = Modifier.padding(padding),
            onOpenEntry = onOpenEntry,
            onRetry = viewModel::retry,
            onTurnOn = { consenting = true },
            actions = { state ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap)) {
                    Button(modifier = Modifier.weight(1f), onClick = onReceive) { Text(stringResource(Res.string.receive)) }
                    Button(modifier = Modifier.weight(1f), onClick = onSend, enabled = state.balance.confirmedSat > 0 || state.creditSat > 0) { Text(stringResource(Res.string.send)) }
                }
                TextButton(onClick = onAdvanced) { Text(stringResource(Res.string.wavelength_advanced)) }
            },
        )
    }
}

/** The screen's body, for a state: split from the screen so a test can draw each state without a holder. */
@Composable
fun WavelengthWalletContent(
    state: WavelengthWalletUIState,
    modifier: Modifier = Modifier,
    onOpenEntry: (String) -> Unit,
    onRetry: () -> Unit,
    onTurnOn: () -> Unit,
    actions: @Composable (WavelengthWalletUIState.Loaded) -> Unit = {},
) {
    ScreenStateTransition(state) { uiState ->
        when (val current = uiState) {
            WavelengthWalletUIState.Loading -> LoadingDataIndicator(modifier = modifier.fillMaxWidth())
            WavelengthWalletUIState.Opening -> LoadingDataIndicator(modifier = modifier.fillMaxWidth(), text = stringResource(Res.string.wavelength_opening))
            WavelengthWalletUIState.Restoring -> LoadingDataIndicator(modifier = modifier.fillMaxWidth(), text = stringResource(Res.string.wavelength_restoring))
            WavelengthWalletUIState.Failed -> ErrorState(
                modifier = modifier,
                message = stringResource(Res.string.wavelength_could_not_open),
                onRetry = onRetry,
            )
            WavelengthWalletUIState.WrongIdentity -> ErrorState(
                modifier = modifier,
                message = stringResource(Res.string.wavelength_wrong_identity),
                onRetry = null,
            )
            is WavelengthWalletUIState.Unavailable -> EmptyState(
                modifier = modifier.readableContent(),
                message = wavelengthUnavailableText(current.reason),
                icon = Icons.Default.AccountBalanceWallet,
                action = if (current.reason == WalletUnavailability.NotConsented) {
                    { Button(onClick = onTurnOn) { Text(stringResource(Res.string.wavelength_turn_on)) } }
                } else null,
            )
            is WavelengthWalletUIState.Loaded -> WavelengthWalletLoaded(current, modifier, onOpenEntry, actions)
        }
    }
}

@Composable
private fun WavelengthWalletLoaded(
    state: WavelengthWalletUIState.Loaded,
    modifier: Modifier,
    onOpenEntry: (String) -> Unit,
    actions: @Composable (WavelengthWalletUIState.Loaded) -> Unit,
) {
    val listPaneWidth = listPaneWidthFor(MaterialTheme.breakpoint)
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val open: (String) -> Unit = { id -> if (listPaneWidth != null) selected = id else onOpenEntry(id) }

    if (listPaneWidth == null) {
        LazyColumn(
            modifier = modifier.fillMaxSize().readableContent(),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap),
        ) {
            walletHeader(state, actions)
            walletHistory(state.entries, open)
        }
    } else {
        Row(modifier = modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.paneGap)) {
            LazyColumn(
                modifier = Modifier.width(listPaneWidth).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap),
            ) {
                walletHeader(state, actions)
                walletHistory(state.entries, open)
            }
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                val entry = state.entries.firstOrNull { it.id == selected }
                if (entry != null) {
                    WavelengthEntryDetail(entry, preimage = state.preimageOf(entry))
                } else {
                    EmptyState(message = stringResource(Res.string.wavelength_entry_select_a_payment), icon = Icons.AutoMirrored.Filled.ReceiptLong)
                }
            }
        }
    }
}

private fun LazyListScope.walletHeader(state: WavelengthWalletUIState.Loaded, actions: @Composable (WavelengthWalletUIState.Loaded) -> Unit) {
    item(key = "balance") {
        Column(
            modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.containerPadding),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap),
        ) {
            Text(stringResource(Res.string.s_sats, Satoshi(state.balance.confirmedSat).grouped()), style = MaterialTheme.typography.displaySmall)
            if (state.pendingSat > 0) {
                Text(stringResource(Res.string.wavelength_pending_s_sats, Satoshi(state.pendingSat).grouped()), style = MaterialTheme.typography.bodyMedium)
            }
            if (state.syncing) {
                Text(stringResource(Res.string.wavelength_syncing), style = MaterialTheme.typography.bodyMedium)
            }
            wavelengthExpiryText(state.expiry)?.let { text ->
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (state.expiry) {
                        is WalletExpiry.Urgent, WalletExpiry.Expired -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (state.network != Network.MAINNET) {
                Text(stringResource(Res.string.wavelength_test_network_s, state.network.wire), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            WavelengthWalletRollout.capFor(state.network)?.let { cap ->
                Text(stringResource(Res.string.wavelength_mainnet_cap_s_sats, cap.grouped()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            actions(state)
        }
    }
    if (state.creditSat > 0) {
        item(key = "credit") {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(MaterialTheme.spacing.containerPadding),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap),
                ) {
                    Text(stringResource(Res.string.wavelength_credit_s_sats, Satoshi(state.creditSat).grouped()), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(Res.string.wavelength_credit_explainer), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    state.recovery?.takeIf { it.found }?.let { recovery ->
        item(key = "recovery") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
            ) {
                Text(
                    stringResource(Res.string.wavelength_recovered_s_coins, (recovery.coins + recovery.boardings).toString()),
                    modifier = Modifier.padding(MaterialTheme.spacing.containerPadding),
                )
            }
        }
    }
    item(key = "divider") { HorizontalDivider() }
}

private fun LazyListScope.walletHistory(entries: List<Entry>, onOpen: (String) -> Unit) {
    if (entries.isEmpty()) {
        item(key = "empty") { EmptyState(message = stringResource(Res.string.wavelength_no_payments_yet), icon = Icons.AutoMirrored.Filled.ReceiptLong) }
        return
    }
    items(entries, key = { "wavelength-entry-" + it.id }) { entry ->
        ListItem(
            modifier = Modifier.clickable { onOpen(entry.id) }.minimumInteractiveComponentSize(),
            headlineContent = { Text(entryTitle(entry), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(entrySubtitle(entry), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingContent = {
                Text(
                    signedAmount(entry),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (entry.status == EntryStatus.FAILED) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            },
        )
    }
}

/** A newer daemon's kinds and statuses arrive as UNKNOWN, and still get a line. */
@Composable
internal fun entryTitle(entry: Entry): String = stringResource(
    when (entry.kind) {
        EntryKind.SEND -> Res.string.wavelength_entry_sent
        EntryKind.RECEIVE -> Res.string.wavelength_entry_received
        EntryKind.DEPOSIT -> Res.string.wavelength_entry_deposit
        EntryKind.EXIT -> Res.string.wavelength_entry_exit
        else -> Res.string.wavelength_entry_payment
    }
)

@Composable
private fun entryStatusText(entry: Entry): String = stringResource(
    when {
        entry.status == EntryStatus.COMPLETE -> Res.string.wavelength_entry_succeeded
        entry.status == EntryStatus.FAILED -> Res.string.wavelength_entry_failed
        entry.isUnpaidRequest -> Res.string.wavelength_entry_waiting_for_payment
        else -> Res.string.wavelength_entry_pending
    }
)

/** An invoice or address nobody has paid yet, which is no money on its way. */
private val Entry.isUnpaidRequest: Boolean
    get() = status == EntryStatus.PENDING && (kind == EntryKind.RECEIVE || kind == EntryKind.DEPOSIT) &&
        (progress?.phase == null || progress?.phase == EntryPhase.REQUEST_CREATED || progress?.phase == EntryPhase.WAITING_FOR_PAYMENT)

/** The status, then when -- *Pending · 14:02 2 Nov 2026*. */
@Composable
private fun entrySubtitle(entry: Entry): String {
    val status = entryStatusText(entry)
    val time = (entry.updatedAt ?: entry.createdAt)?.toFormattedTimeAndDateString() ?: return status
    return stringResource(Res.string.wavelength_entry_s_dot_s, status, time)
}

@Composable
private fun signedAmount(entry: Entry): String {
    val amount = Satoshi(entry.amountSat).grouped()
    return when {
        // An address has no amount until it is paid.
        entry.amountSat == 0L -> ""
        entry.isUnpaidRequest -> stringResource(Res.string.s_sats, amount)
        entry.kind == EntryKind.SEND || entry.kind == EntryKind.EXIT -> stringResource(Res.string.wavelength_entry_minus_s_sats, amount)
        else -> stringResource(Res.string.wavelength_entry_plus_s_sats, amount)
    }
}

/** One payment, every field it has, the long ones copyable. */
@Composable
fun WavelengthEntryDetail(entry: Entry?, modifier: Modifier = Modifier, preimage: String? = entry?.progress?.preimage?.ifEmpty { null }) {
    if (entry == null) {
        EmptyState(modifier = modifier.readableContent(), message = stringResource(Res.string.wavelength_entry_not_found), icon = Icons.AutoMirrored.Filled.ReceiptLong)
        return
    }
    val clipboard = LocalClipboardManager.current
    val notify = rememberNotifier(rememberCoroutineScope())
    val copied = stringResource(Res.string.copied)
    Column(
        modifier = modifier.fillMaxSize().readableContent().verticalScroll(rememberScrollState()).padding(MaterialTheme.spacing.containerPadding),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
    ) {
        Text(entryTitle(entry), style = MaterialTheme.typography.headlineSmall)
        Text(signedAmount(entry), style = MaterialTheme.typography.displaySmall)
        DetailLine(stringResource(Res.string.wavelength_entry_status), entryStatusText(entry))
        DetailLine(stringResource(Res.string.wavelength_amount), stringResource(Res.string.s_sats, Satoshi(entry.amountSat).grouped()))
        if (entry.feeSat > 0) DetailLine(stringResource(Res.string.wavelength_entry_fee), stringResource(Res.string.s_sats, Satoshi(entry.feeSat).grouped()))
        entry.createdAt?.let { DetailLine(stringResource(Res.string.wavelength_entry_started), it.toFormattedTimeAndDateString()) }
        if (entry.status == EntryStatus.COMPLETE) {
            entry.updatedAt?.let { DetailLine(stringResource(Res.string.wavelength_entry_finished), it.toFormattedTimeAndDateString()) }
        }
        entry.counterparty.ifEmpty { null }?.let { counterparty ->
            DetailLine(stringResource(Res.string.wavelength_entry_to), counterparty, onCopy = { clipboard.setText(AnnotatedString(counterparty)); notify(copied) })
        }
        entry.progress?.paymentHash?.ifEmpty { null }?.let { hash ->
            DetailLine(stringResource(Res.string.wavelength_entry_payment_hash), hash, onCopy = { clipboard.setText(AnnotatedString(hash)); notify(copied) })
        }
        preimage?.let { proof ->
            DetailLine(stringResource(Res.string.wavelength_preimage), proof, onCopy = { clipboard.setText(AnnotatedString(proof)); notify(copied) })
        }
        entry.failureReason.ifEmpty { null }?.let { DetailLine(stringResource(Res.string.wavelength_entry_failure), it) }
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

/** A payment below the expanded breakpoint: [WavelengthEntryDetail] on a pushed screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WavelengthEntryScreen(
    identityWavelength: IdentityWavelength,
    entryId: String,
    onNavigateBack: () -> Unit,
) {
    val viewModel: WavelengthWalletViewModel = viewModel(factory = WavelengthWalletViewModel.factory(identityWavelength))
    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.wavelength_entry_payment_details)) },
                navigationIcon = { NavigateBackButton(onNavigateBack) },
            )
        },
    ) { padding ->
        ScreenStateTransition(viewModel.uiState) { uiState ->
            when (val state = uiState) {
                is WavelengthWalletUIState.Loaded -> {
                    val entry = state.entries.firstOrNull { it.id == entryId }
                    WavelengthEntryDetail(entry, Modifier.padding(padding), preimage = entry?.let(state::preimageOf))
                }
                WavelengthWalletUIState.Failed -> ErrorState(modifier = Modifier.padding(padding), onRetry = viewModel::retry)
                else -> LoadingDataIndicator(modifier = Modifier.fillMaxWidth().padding(padding))
            }
        }
    }
}
