package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.GroupWavelengthDaemonSource
import com.machankura.dedun.compose.repository.GroupWavelengthRepository
import com.machankura.dedun.compose.text.grouped
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.extensions.toFormattedTimeAndDateString
import com.machankura.dedun.compose.ui.composable.widgets.EmptyState
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.composable.widgets.wallet.DetailLine
import com.machankura.dedun.compose.ui.composable.widgets.wallet.entryHeadline
import com.machankura.dedun.compose.ui.composable.widgets.wallet.entryStatus
import com.machankura.dedun.compose.ui.composable.widgets.wallet.memberName
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.GroupWavelengthWalletViewModel
import com.machankura.dedun.compose.ui.view.state.GroupWavelengthWalletUIState
import com.machankura.dedun.compose.wallet.wavelength.WavelengthPlatform
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthEntry
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.group_wavelength_advanced_untested_refund
import dedun.composeapp.generated.resources.group_wavelength_entry_from_address
import dedun.composeapp.generated.resources.group_wavelength_entry_how
import dedun.composeapp.generated.resources.group_wavelength_entry_how_lightning
import dedun.composeapp.generated.resources.group_wavelength_entry_how_onchain
import dedun.composeapp.generated.resources.group_wavelength_entry_not_found
import dedun.composeapp.generated.resources.group_wavelength_entry_paid_to
import dedun.composeapp.generated.resources.group_wavelength_entry_source_both
import dedun.composeapp.generated.resources.group_wavelength_entry_source_device
import dedun.composeapp.generated.resources.group_wavelength_entry_source_mismatch
import dedun.composeapp.generated.resources.group_wavelength_entry_source_operator
import dedun.composeapp.generated.resources.group_wavelength_entry_title
import dedun.composeapp.generated.resources.group_wavelength_entry_when
import dedun.composeapp.generated.resources.group_wavelength_entry_whose
import dedun.composeapp.generated.resources.group_wavelength_history_when_unknown
import dedun.composeapp.generated.resources.group_wavelength_invoice_warning
import dedun.composeapp.generated.resources.s_sats
import dedun.composeapp.generated.resources.wavelength_amount
import dedun.composeapp.generated.resources.wavelength_entry_fee
import dedun.composeapp.generated.resources.wavelength_entry_minus_s_sats
import dedun.composeapp.generated.resources.wavelength_entry_plus_s_sats
import dedun.composeapp.generated.resources.wavelength_entry_status
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Satoshi
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/**
 * One thing the group's Wavelength wallet did, in general (`docs/group-wavelength-transaction-details.md`,
 * T5): what it was, how much, whose, where it got to, what it cost, and one line saying whose word any of
 * it is. Every technical field is behind the sheet, not here.
 *
 * Nothing on this screen does anything (T9). A payout that failed needs a new invoice and a new approval,
 * which is the payouts screen's job, and an approval that ran out is gone; an action offered here would be
 * offered on the operator's word, since that is where a failure is reported from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupWavelengthEntryScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    chain: Chain,
    entryId: String,
    activeIdentityStateFlow: StateFlow<Identity?>,
    chatRepository: ChatRepository,
    repository: GroupWavelengthRepository,
    daemonSource: GroupWavelengthDaemonSource,
    onNavigateBack: () -> Unit,
) {
    val viewModel: GroupWavelengthWalletViewModel = viewModel(
        key = "wavelength-entry:$chatRoomId:${GroupWalletStatementEvent.chainName(chain)}",
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
    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.group_wavelength_entry_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { NavigateBackButton(onNavigateBack) },
            )
        },
    ) { padding ->
        ScreenStateTransition(viewModel.uiState) { uiState ->
            when (val state = uiState) {
                is GroupWavelengthWalletUIState.Opened -> GroupWavelengthEntryDetail(
                    entry = state.entries.firstOrNull { it.id == entryId },
                    localChatRoom = state.localChatRoom,
                    reader = state.userPublicKey,
                    modifier = Modifier.padding(padding),
                )
                is GroupWavelengthWalletUIState.Error -> ErrorState(modifier = Modifier.padding(padding), message = state.message, onRetry = null)
                else -> LoadingDataIndicator(modifier = Modifier.fillMaxWidth().padding(padding))
            }
        }
    }
}

/** The body, apart from the view model so a test can show it one entry. */
@Composable
internal fun GroupWavelengthEntryDetail(
    entry: GroupWavelengthEntry?,
    localChatRoom: LocalChatRoom,
    reader: HexKey,
    modifier: Modifier = Modifier,
) {
    if (entry == null) {
        EmptyState(
            modifier = modifier.readableContent(),
            message = stringResource(Res.string.group_wavelength_entry_not_found),
            icon = Icons.AutoMirrored.Filled.ReceiptLong,
        )
        return
    }
    Column(
        modifier = modifier.fillMaxSize().readableContent().verticalScroll(rememberScrollState()).padding(MaterialTheme.spacing.containerPadding),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
    ) {
        Text(entryHeadline(entry, localChatRoom, reader), style = MaterialTheme.typography.headlineSmall)
        Text(signedAmount(entry), style = MaterialTheme.typography.displaySmall)

        DetailLine(stringResource(Res.string.wavelength_entry_status), entryStatus(entry))
        // A round moved the group's own coins and nothing else: it has a cost and no amount, so saying
        // "0 sats" under Amount beside the same figure above would be saying nothing, twice (T10).
        if (entry.kind != GroupWavelengthEntry.Kind.ROUND) {
            DetailLine(stringResource(Res.string.wavelength_amount), stringResource(Res.string.s_sats, Satoshi(entry.amountSat).grouped()))
        }
        if (entry.feeSat > 0) {
            DetailLine(stringResource(Res.string.wavelength_entry_fee), stringResource(Res.string.s_sats, Satoshi(entry.feeSat).grouped()))
        }
        entry.member?.let { DetailLine(stringResource(Res.string.group_wavelength_entry_whose), memberName(localChatRoom, it, reader)) }
        rail(entry)?.let { DetailLine(stringResource(Res.string.group_wavelength_entry_how), it) }
        DetailLine(
            label = stringResource(Res.string.group_wavelength_entry_when),
            value = entry.at?.let { Instant.fromEpochSeconds(it).toFormattedTimeAndDateString() }
                ?: stringResource(Res.string.group_wavelength_history_when_unknown),
        )
        // An onchain payout is the one kind whose destination a device can name exactly (W2), so it is the
        // one kind that says where the money went in full. A boarding deposit says only that the address
        // was checked, because the address itself is gone once the round has happened.
        (entry.detail as? GroupWavelengthEntry.Detail.OnchainPayout)?.let {
            DetailLine(stringResource(Res.string.group_wavelength_entry_paid_to), it.approval.address, copyable = true)
        }
        (entry.detail as? GroupWavelengthEntry.Detail.OnchainDeposit)
            ?.takeIf { it.deposit?.member != null || it.boarded != null }
            ?.let { Text(stringResource(Res.string.group_wavelength_entry_from_address), style = MaterialTheme.typography.bodyMedium) }

        Text(sourceLine(entry.source), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        warning(entry)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/**
 * What leaves and what arrives, signed, as the profile wallet's detail writes it. A round's figure is what
 * it cost, since that is the only thing that left: nothing moved in or out of the wallet (T10).
 */
@Composable
private fun signedAmount(entry: GroupWavelengthEntry): String {
    if (entry.kind == GroupWavelengthEntry.Kind.ROUND) {
        return stringResource(Res.string.wavelength_entry_minus_s_sats, Satoshi(entry.feeSat).grouped())
    }
    val amount = Satoshi(entry.amountSat).grouped()
    return when (entry.direction) {
        GroupWavelengthEntry.Direction.IN -> stringResource(Res.string.wavelength_entry_plus_s_sats, amount)
        GroupWavelengthEntry.Direction.OUT -> stringResource(Res.string.wavelength_entry_minus_s_sats, amount)
        else -> stringResource(Res.string.s_sats, amount)
    }
}

/** Which rail, where an entry has one. A round and an exit are the wallet's own doing and have none. */
@Composable
private fun rail(entry: GroupWavelengthEntry): String? = when (entry.kind) {
    GroupWavelengthEntry.Kind.DEPOSIT_LIGHTNING, GroupWavelengthEntry.Kind.PAYOUT_LIGHTNING ->
        stringResource(Res.string.group_wavelength_entry_how_lightning)
    GroupWavelengthEntry.Kind.DEPOSIT_ONCHAIN, GroupWavelengthEntry.Kind.PAYOUT_ONCHAIN ->
        stringResource(Res.string.group_wavelength_entry_how_onchain)
    else -> null
}

/** Whose word this is, which is the one thing every entry has to say (T2). */
@Composable
private fun sourceLine(source: GroupWavelengthEntry.Source): String = stringResource(
    when (source) {
        GroupWavelengthEntry.Source.THIS_DEVICE -> Res.string.group_wavelength_entry_source_device
        GroupWavelengthEntry.Source.BOTH -> Res.string.group_wavelength_entry_source_both
        GroupWavelengthEntry.Source.MISMATCH -> Res.string.group_wavelength_entry_source_mismatch
        GroupWavelengthEntry.Source.THE_OPERATOR -> Res.string.group_wavelength_entry_source_operator
    }
)

/**
 * The known gap this kind sits on, said to the one person who has opened the detail of it. Both come from
 * the wallet note's own list of what the screens may not promise.
 */
@Composable
private fun warning(entry: GroupWavelengthEntry): String? = when (entry.kind) {
    // Spike 6: nothing can prove an invoice the operator made pays the group.
    GroupWavelengthEntry.Kind.DEPOSIT_LIGHTNING -> stringResource(Res.string.group_wavelength_invoice_warning)
    // A funded vHTLC the swap server won't refund has only ever met fixtures.
    GroupWavelengthEntry.Kind.PAYOUT_LIGHTNING -> stringResource(Res.string.group_wavelength_advanced_untested_refund)
    else -> null
}
