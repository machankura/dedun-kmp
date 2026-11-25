package com.machankura.dedun.compose.ui.composable

import dedun.composeapp.generated.resources.this_was_someone_else_s_money
import dedun.composeapp.generated.resources.someone_outside_the_group_ellipsis
import dedun.composeapp.generated.resources.put_s_request_to_the_group
import dedun.composeapp.generated.resources.requests
import dedun.composeapp.generated.resources.asked_the_group_to_add_s
import dedun.composeapp.generated.resources.asked_a_member_with_a_share
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.machankura.dedun.compose.text.ProposedEvent
import com.machankura.dedun.compose.nostr.wallet.ContactAmendmentEvent
import com.machankura.dedun.compose.contacts.Party
import dedun.composeapp.generated.resources.outside_the_group_s
import dedun.composeapp.generated.resources.outside_the_group
import dedun.composeapp.generated.resources.a_contact
import com.machankura.dedun.compose.managers.Contacts
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
    val askedAMember = stringResource(Res.string.asked_a_member_with_a_share)
    // The deposit whose attribution is being chosen, by key.
    var attributing by remember { mutableStateOf<String?>(null) }
    // Whether the add-contact sheet is open: docs/non-member-deposits.md, Phase 3.
    var addingContact by remember { mutableStateOf(false) }
    // The name of the contact just asked for, for the notice that says so.
    var askedFor by remember { mutableStateOf<String?>(null) }
    val askedToAdd = askedFor?.let { stringResource(Res.string.asked_the_group_to_add_s, it) }
    LaunchedEffect(askedToAdd) { askedToAdd?.let { notify(it); askedFor = null } }
    val toSigning: (String) -> Unit = { sessionId -> onNavigateToRoute(FrostSigningRoute(activeUserPublicKey = activeUserPublicKey, chatRoomId = chatRoomId, sessionId = sessionId)) }

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
                    // Members' requests a device that holds a share can put to the group (T10).
                    if (state.canSign && state.requests.isNotEmpty()) {
                        item { Text(stringResource(Res.string.requests), style = MaterialTheme.typography.labelMedium) }
                        items(state.requests, key = { it.id }) { request ->
                            RequestRow(
                                request = request,
                                chatRoomId = chatRoomId,
                                asker = "@" + (state.names[request.member] ?: request.member.take(8)),
                                nameOf = { id -> state.contacts[id]?.name ?: state.names[id] },
                                onPut = if (!viewModel.isActionPending.value) {
                                    { viewModel.putToGroup(request, onSuccess = toSigning, onFailure = { notify(couldNotAsk) }) }
                                } else null,
                            )
                        }
                        item { HorizontalDivider() }
                    }
                    item { Tallies(state.tallies, state.names, state.contacts) }
                    item { HorizontalDivider() }
                    if (state.deposits.isEmpty()) {
                        item { EmptyState(message = stringResource(Res.string.nothing_has_been_deposited_yet), icon = Icons.Default.Savings) }
                    }
                    items(state.deposits, key = { it.depositKey }) { deposit ->
                        DepositListRow(
                            deposit = deposit,
                            // A contact by what the group calls them, a member by @name. docs/non-member-deposits.md.
                            from = deposit.member?.let { state.contacts[it]?.name ?: ("@" + (state.names[it] ?: it.take(8))) },
                            // A device that holds a share can ask the group to say whose a deposit
                            // is, or to correct what it said. docs/member-deposits.md, Phase 9.
                            onAttribute = if (state.canSign && !viewModel.isActionPending.value) {
                                { attributing = deposit.depositKey }
                            } else null,
                            // A member without a share says one of their own deposits was someone else's money,
                            // and asks a member who holds one to put it to the group. docs/non-member-deposits.md, D7.
                            onSomeoneElses = if (!state.canSign && deposit.member != null && deposit.member == state.self && !viewModel.isActionPending.value) {
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
            // A member without a share may only say their own deposit was a contact's.
            depositors = if (loaded.canSign) loaded.depositors else emptyList(),
            names = loaded.names,
            contacts = loaded.contacts.values.filter { it.isOpen }.sortedBy { it.name.lowercase() },
            offersTheGroup = loaded.canSign,
            current = deposit.member,
            onDismiss = { attributing = null },
            onChoose = { party ->
                attributing = null
                if (loaded.canSign) {
                    viewModel.attribute(depositKey = deposit.depositKey, party = party, onSuccess = toSigning, onFailure = { notify(couldNotAsk) })
                } else {
                    viewModel.askToAttribute(deposit.depositKey, party, onAsked = { notify(askedAMember) }, onFailure = { notify(couldNotAsk) })
                }
            },
            onSomeoneOutside = { attributing = null; addingContact = true },
        )
    }
    if (loaded != null && addingContact) {
        AddContactSheet(
            chatRoomId = chatRoomId,
            contacts = loaded.contacts,
            waitingFor = loaded.waitingFor.map { loaded.names[it] ?: it.take(8) },
            pending = viewModel.isActionPending.value,
            onDismiss = { addingContact = false },
            onUse = { addingContact = false },
            onReopen = { contact ->
                addingContact = false
                viewModel.amendContact(contact.id, ContactAmendmentEvent.Change.Reopen, onProposed = toSigning, onAsked = { notify(askedAMember) }, onFailure = { notify(couldNotAsk) })
            },
            onAsk = { identifier, name ->
                addingContact = false
                viewModel.addContact(
                    identifier, name,
                    onProposed = { sessionId -> askedFor = name; toSigning(sessionId) },
                    onAsked = { askedFor = name },
                    onFailure = { notify(couldNotAsk) },
                )
            },
        )
    }
}

/**
 * A member's request a device that holds a share can put to the group: what it asks, said as the
 * signing screen says it, and who asked. `docs/non-member-deposits.md`, Phase 3.
 */
@Composable
private fun RequestRow(request: Contacts.PendingRequest, chatRoomId: String, asker: String, nameOf: (HexKey) -> String?, onPut: (() -> Unit)?) {
    // Summarised as the group would sign it: the room is its author.
    val summary = ProposedEvent.summarize(
        Event("", chatRoomId, request.askedAt, request.request.kind, request.request.tags, request.request.content, ""),
        nameOf,
    )
    ListItem(
        headlineContent = { Text(summary.label) },
        supportingContent = { Text(summary.detail) },
        trailingContent = onPut?.let { put ->
            { TextButton(onClick = put) { Text(stringResource(Res.string.put_s_request_to_the_group, asker)) } }
        },
    )
}

/**
 * Whose a deposit is: one of the depositors, one of the contacts the group counts, the group's own,
 * or someone outside the group not yet added (`docs/non-member-deposits.md`, Phase 3). The current
 * answer is marked.
 */
@Composable
private fun AttributeDialog(
    depositors: List<HexKey>,
    names: Map<HexKey, String>,
    contacts: List<Contacts.Contact>,
    offersTheGroup: Boolean,
    current: HexKey?,
    onDismiss: () -> Unit,
    onChoose: (Party?) -> Unit,
    onSomeoneOutside: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.whose_deposit_is_this_question)) },
        text = {
            androidx.compose.foundation.layout.Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                depositors.forEach { member ->
                    TextButton(onClick = { onChoose(Party.Member(member)) }, enabled = member != current) {
                        Text("@" + (names[member] ?: member.take(8)))
                    }
                }
                if (offersTheGroup) {
                    TextButton(onClick = { onChoose(null) }) { Text(stringResource(Res.string.the_group_s_own)) }
                }
                if (depositors.isNotEmpty() || offersTheGroup) HorizontalDivider()
                contacts.forEach { contact ->
                    TextButton(onClick = { onChoose(Party.Contact(contact.id)) }, enabled = contact.id != current) {
                        Text(stringResource(Res.string.s_dot_s, contact.name, contact.identifier.formatted))
                    }
                }
                TextButton(onClick = onSomeoneOutside) { Text(stringResource(Res.string.someone_outside_the_group_ellipsis)) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

/**
 * Each member's tally, then each contact's -- the people outside the group it agreed to count
 * (`docs/non-member-deposits.md`) -- then the unattributed, then the total they add up to.
 */
@Composable
internal fun Tallies(tallies: DepositLedger.Tallies, names: Map<HexKey, String>, contacts: Map<HexKey, Contacts.Contact> = emptyMap()) {
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
        tallies.contacts.values.sortedByDescending { it.depositedMsat }.forEach { tally ->
            val contact = contacts[tally.member]
            Text(
                stringResource(Res.string.s_put_in_s_sats, contact?.name ?: stringResource(Res.string.a_contact), sats(tally.depositedMsat)),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                contact?.let { stringResource(Res.string.outside_the_group_s, it.identifier.formatted) } ?: stringResource(Res.string.outside_the_group),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
private fun DepositListRow(deposit: DepositRow, from: String?, onAttribute: (() -> Unit)?, onSomeoneElses: (() -> Unit)? = null) {
    ListItem(
        trailingContent = when {
            onAttribute != null -> {
                {
                    TextButton(onClick = onAttribute) {
                        Text(stringResource(if (deposit.member == null) Res.string.attribute else Res.string.correct))
                    }
                }
            }
            onSomeoneElses != null -> {
                { TextButton(onClick = onSomeoneElses) { Text(stringResource(Res.string.this_was_someone_else_s_money)) } }
            }
            else -> null
        },
        leadingContent = {
            Icon(if (deposit.source == DepositRow.LIGHTNING) Icons.Default.Bolt else Icons.Default.Savings, contentDescription = Decorative)
        },
        headlineContent = { Text(stringResource(Res.string.s_sats, sats(deposit.amountMsat))) },
        supportingContent = {
            val whose = from?.let { stringResource(Res.string.from_s, it) } ?: stringResource(Res.string.unattributed)
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
