package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
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
import com.machankura.dedun.compose.database.model.DepositRow
import com.machankura.dedun.compose.nostr.wallet.ContactAmendmentEvent
import com.machankura.dedun.compose.nostr.wallet.ContactRecordEvent
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.DepositRepository
import com.machankura.dedun.compose.ui.composable.navigation.routes.FrostSigningRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.composable.widgets.EmptyState
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.composable.widgets.rememberNotifier
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.ContactViewModel
import com.machankura.dedun.compose.ui.view.state.ContactUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.added_by_s
import dedun.composeapp.generated.resources.asked_a_member_with_a_share
import dedun.composeapp.generated.resources.cancel
import dedun.composeapp.generated.resources.couldn_t_ask_the_group
import dedun.composeapp.generated.resources.name
import dedun.composeapp.generated.resources.nothing_has_been_deposited_for_them_yet
import dedun.composeapp.generated.resources.onchain_lightning_wavelength_s_s_s
import dedun.composeapp.generated.resources.paid_to_them_s_sats
import dedun.composeapp.generated.resources.rename
import dedun.composeapp.generated.resources.reopen_s
import dedun.composeapp.generated.resources.s_put_in_s_sats
import dedun.composeapp.generated.resources.s_sats
import dedun.composeapp.generated.resources.stop_counting_deposits_for_them
import dedun.composeapp.generated.resources.stop_counting_deposits_for_them_question
import dedun.composeapp.generated.resources.the_group_no_longer_counts_new_deposits_for_them
import dedun.composeapp.generated.resources.their_deposits_so_far_stay_theirs
import dedun.composeapp.generated.resources.what_the_group_will_call_them
import fr.acinq.bitcoin.Chain
import org.jetbrains.compose.resources.stringResource

/**
 * One person outside the group that it has agreed to count: their name and number or address in
 * full -- the group signed both, and every member sees them (the owner's decision 1) -- what they
 * have put in, every deposit, and what any member can ask the group to change about them.
 * `docs/non-member-deposits.md`, Phase 4.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    contactId: HexKey,
    chain: Chain,
    initialUIState: ContactUIState = ContactUIState.Loading,
    chatRepository: ChatRepository,
    depositRepository: DepositRepository,
    onNavigateToRoute: (Route) -> Unit = {},
    onNavigateBack: () -> Unit,
) {
    val viewModel: ContactViewModel = viewModel(
        key = "$chatRoomId:$contactId",
        factory = ContactViewModel.factory(chatRoomId, contactId, chain, initialUIState, chatRepository, depositRepository),
    )
    LaunchedEffect(chatRoomId, contactId, chain) {
        if (initialUIState == ContactUIState.Loading) viewModel.initiate()
    }
    val notify = rememberNotifier(rememberCoroutineScope())
    val couldNotAsk = stringResource(Res.string.couldn_t_ask_the_group)
    val askedAMember = stringResource(Res.string.asked_a_member_with_a_share)
    val toSigning: (String) -> Unit = { sessionId -> onNavigateToRoute(FrostSigningRoute(activeUserPublicKey = activeUserPublicKey, chatRoomId = chatRoomId, sessionId = sessionId)) }
    val asked: () -> Unit = { notify(askedAMember) }
    val failed: () -> Unit = { notify(couldNotAsk) }
    var renaming by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = { Text((viewModel.uiState as? ContactUIState.Loaded)?.contact?.name ?: "") },
                navigationIcon = { NavigateBackButton(onNavigateBack) },
            )
        },
    ) { padding ->
        ScreenStateTransition(viewModel.uiState) { uiState ->
            when (val state = uiState) {
                ContactUIState.Loading -> LoadingDataIndicator(modifier = Modifier.fillMaxWidth().padding(padding))
                is ContactUIState.Error -> ErrorState(modifier = Modifier.padding(padding), message = state.message, onRetry = viewModel::initiate)
                is ContactUIState.Loaded -> LazyColumn(
                    modifier = Modifier.padding(padding).readableContent().fillMaxSize().padding(MaterialTheme.spacing.screenMargin),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap),
                ) {
                    val contact = state.contact
                    item {
                        SelectionContainer {
                            Text(contact.identifier.formatted, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    item {
                        Text(
                            stringResource(Res.string.added_by_s, "@" + (state.names[contact.proposer] ?: contact.proposer.take(8))),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (contact.closed) {
                        item {
                            Text(
                                stringResource(Res.string.the_group_no_longer_counts_new_deposits_for_them),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item { HorizontalDivider() }
                    state.tally?.let { tally ->
                        item {
                            Text(stringResource(Res.string.s_put_in_s_sats, contact.name, sats(tally.depositedMsat)), style = MaterialTheme.typography.bodyLarge)
                        }
                        item {
                            Text(
                                stringResource(Res.string.onchain_lightning_wavelength_s_s_s, sats(tally.onchainMsat), sats(tally.lightningMsat), sats(tally.wavelengthMsat)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (tally.paidOutMsat > 0) {
                            item {
                                Text(
                                    stringResource(Res.string.paid_to_them_s_sats, sats(tally.paidOutMsat)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    item {
                        ContactActions(
                            closed = contact.closed,
                            name = contact.name,
                            enabled = !viewModel.isActionPending.value,
                            onRename = { renaming = true },
                            onClose = { closing = true },
                            onReopen = { viewModel.amend(ContactAmendmentEvent.Change.Reopen, toSigning, asked, failed) },
                        )
                    }
                    item { HorizontalDivider() }
                    if (state.deposits.isEmpty()) {
                        item { EmptyState(message = stringResource(Res.string.nothing_has_been_deposited_for_them_yet), icon = Icons.Default.Savings) }
                    }
                    items(state.deposits, key = { it.depositKey }) { deposit -> ContactDepositRow(deposit) }
                }
            }
        }
    }

    val loaded = viewModel.uiState as? ContactUIState.Loaded
    if (loaded != null && renaming) {
        RenameDialog(
            current = loaded.contact.name,
            onDismiss = { renaming = false },
            onRename = { name ->
                renaming = false
                viewModel.amend(ContactAmendmentEvent.Change.Rename(name), toSigning, asked, failed)
            },
        )
    }
    if (loaded != null && closing) {
        AlertDialog(
            onDismissRequest = { closing = false },
            title = { Text(stringResource(Res.string.stop_counting_deposits_for_them_question)) },
            text = { Text(stringResource(Res.string.their_deposits_so_far_stay_theirs)) },
            confirmButton = {
                TextButton(onClick = {
                    closing = false
                    viewModel.amend(ContactAmendmentEvent.Change.Close, toSigning, asked, failed)
                }) { Text(stringResource(Res.string.stop_counting_deposits_for_them)) }
            },
            dismissButton = { TextButton(onClick = { closing = false }) { Text(stringResource(Res.string.cancel)) } },
        )
    }
}

@Composable
private fun ContactActions(closed: Boolean, name: String, enabled: Boolean, onRename: () -> Unit, onClose: () -> Unit, onReopen: () -> Unit) {
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)) {
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onRename, enabled = enabled) { Text(stringResource(Res.string.rename)) }
        if (closed) {
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onReopen, enabled = enabled) { Text(stringResource(Res.string.reopen_s, name)) }
        } else {
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onClose, enabled = enabled) { Text(stringResource(Res.string.stop_counting_deposits_for_them)) }
        }
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    val trimmed = name.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.rename)) },
        text = {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = name,
                onValueChange = { name = it.take(ContactRecordEvent.MAX_NAME_CHARS) },
                singleLine = true,
                label = { Text(stringResource(Res.string.name)) },
                supportingText = { Text(stringResource(Res.string.what_the_group_will_call_them)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(trimmed) }, enabled = ContactRecordEvent.isValidName(trimmed) && trimmed != current) {
                Text(stringResource(Res.string.rename))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

@Composable
private fun ContactDepositRow(deposit: DepositRow) {
    ListItem(
        leadingContent = { Icon(if (deposit.source == DepositRow.LIGHTNING) Icons.Default.Bolt else Icons.Default.Savings, contentDescription = Decorative) },
        headlineContent = { Text(stringResource(Res.string.s_sats, sats(deposit.amountMsat))) },
    )
}
