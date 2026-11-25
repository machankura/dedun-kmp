package com.machankura.dedun.compose.ui.composable

import dedun.composeapp.generated.resources.ask_for_a_lightning_invoice_for_them
import dedun.composeapp.generated.resources.address_copied
import dedun.composeapp.generated.resources.copy_address
import dedun.composeapp.generated.resources.qr_their_deposit_address
import dedun.composeapp.generated.resources.finding_the_group_s_coins
import dedun.composeapp.generated.resources.open_deposits_for_them
import dedun.composeapp.generated.resources.deposits_for_them
import com.machankura.dedun.compose.ui.composable.navigation.routes.GroupLightningReceiveRoute
import com.machankura.dedun.compose.ui.composable.widgets.QRCodeView
import com.machankura.dedun.compose.wallet.bip21Uri
import com.machankura.dedun.compose.wallet.GroupOnchainWallets
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import dedun.composeapp.generated.resources.ask_the_group
import dedun.composeapp.generated.resources.the_group_signs_where_it_pays_them
import dedun.composeapp.generated.resources.give_a_lightning_or_an_onchain_address
import dedun.composeapp.generated.resources.that_s_one_of_this_group_s_own_addresses
import dedun.composeapp.generated.resources.couldn_t_check_the_address
import dedun.composeapp.generated.resources.this_address_has_been_used_before
import dedun.composeapp.generated.resources.that_address_is_for_another_network
import dedun.composeapp.generated.resources.that_isn_t_an_onchain_address
import dedun.composeapp.generated.resources.couldn_t_find_that_lightning_address
import dedun.composeapp.generated.resources.that_isn_t_a_lightning_address
import dedun.composeapp.generated.resources.onchain_address
import dedun.composeapp.generated.resources.lightning_address
import dedun.composeapp.generated.resources.pay_out
import dedun.composeapp.generated.resources.remove
import dedun.composeapp.generated.resources.set_where_the_group_pays_them
import dedun.composeapp.generated.resources.nowhere_yet
import dedun.composeapp.generated.resources.where_the_group_pays_them
import com.machankura.dedun.compose.ui.composable.navigation.routes.GroupPayoutsRoute
import com.machankura.dedun.compose.repository.PayoutDestinationRepository
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.dedun.compose.nostr.wallet.PayoutDestination
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Button
import dedun.composeapp.generated.resources.nobody_in_the_group
import dedun.composeapp.generated.resources.which_member_is_s_question
import dedun.composeapp.generated.resources.not_them_correct
import dedun.composeapp.generated.resources.they_ve_joined_the_group
import dedun.composeapp.generated.resources.their_deposits_and_balance_are_s_s
import dedun.composeapp.generated.resources.s_is_s
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
    payoutDestinationRepository: PayoutDestinationRepository = PayoutDestinationRepository.NO_OP,
    /** The group's onchain wallets, for the address to hand the contact (D10); null where there are none. */
    wallets: GroupOnchainWallets? = null,
    onNavigateToRoute: (Route) -> Unit = {},
    onNavigateBack: () -> Unit,
) {
    val viewModel: ContactViewModel = viewModel(
        key = "$chatRoomId:$contactId",
        factory = ContactViewModel.factory(chatRoomId, contactId, chain, initialUIState, chatRepository, depositRepository, payoutDestinationRepository, wallets),
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
    val clipboardManager = LocalClipboardManager.current
    val addressCopied = stringResource(Res.string.address_copied)
    // Choosing which member the contact was: docs/non-member-deposits.md, Phase 5.
    var claiming by remember { mutableStateOf(false) }
    // Saying where the group pays them: docs/non-member-deposits.md, Phase 6.
    var settingDestination by remember { mutableStateOf(false) }
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
                    contact.claimedBy?.let { member ->
                        item {
                            Text(
                                stringResource(Res.string.s_is_s, contact.name, "@" + (state.names[member] ?: member.take(8))),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        item {
                            Text(
                                stringResource(Res.string.their_deposits_and_balance_are_s_s),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
                        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { claiming = true }, enabled = !viewModel.isActionPending.value) {
                            Text(stringResource(if (contact.claimedBy == null) Res.string.they_ve_joined_the_group else Res.string.not_them_correct))
                        }
                    }
                    if (contact.claimedBy == null) item {
                        ContactActions(
                            closed = contact.closed,
                            name = contact.name,
                            enabled = !viewModel.isActionPending.value,
                            onRename = { renaming = true },
                            onClose = { closing = true },
                            onReopen = { viewModel.amend(ContactAmendmentEvent.Change.Reopen, toSigning, asked, failed) },
                        )
                    }
                    // Deposits for them, while they are nobody in the group and still counted: an address on a
                    // branch of their own, and invoices issued against it. docs/non-member-deposits.md, D10.
                    if (contact.isOpen) {
                        item { HorizontalDivider() }
                        item { Text(stringResource(Res.string.deposits_for_them), style = MaterialTheme.typography.labelMedium) }
                        if (!state.depositsOpen) {
                            item {
                                OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { viewModel.openDeposits(toSigning, asked, failed) }, enabled = !viewModel.isActionPending.value) {
                                    Text(stringResource(Res.string.open_deposits_for_them))
                                }
                            }
                        } else {
                            val address = viewModel.depositAddress
                            if (address == null) {
                                item { LoadingDataIndicator(fillScreen = false, text = stringResource(Res.string.finding_the_group_s_coins)) }
                            } else {
                                item { QRCodeView(bip21Uri(address), stringResource(Res.string.qr_their_deposit_address)) }
                                item { SelectionContainer { Text(address, style = MaterialTheme.typography.bodyMedium) } }
                                item {
                                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                                        clipboardManager.setText(buildAnnotatedString { append(address) })
                                        notify(addressCopied)
                                    }) { Text(stringResource(Res.string.copy_address)) }
                                }
                            }
                            item {
                                OutlinedButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = { onNavigateToRoute(GroupLightningReceiveRoute(activeUserPublicKey = activeUserPublicKey, chatRoomId = chatRoomId, contactId = contact.id)) },
                                ) { Text(stringResource(Res.string.ask_for_a_lightning_invoice_for_them)) }
                            }
                        }
                    }
                    // Where the group pays them, while they are nobody in the group (D9). A claimed contact
                    // is paid as their member, at the member's own destination.
                    if (contact.claimedBy == null) {
                        item { HorizontalDivider() }
                        item { Text(stringResource(Res.string.where_the_group_pays_them), style = MaterialTheme.typography.labelMedium) }
                        item {
                            val destination = state.destination
                            SelectionContainer {
                                Text(
                                    destination?.let { listOfNotNull(it.lightningAddress?.toString(), it.onchainAddress).joinToString("\n") }
                                        ?: stringResource(Res.string.nowhere_yet),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                        item {
                            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { settingDestination = true }, enabled = !viewModel.isActionPending.value) {
                                Text(stringResource(Res.string.set_where_the_group_pays_them))
                            }
                        }
                        if (state.destination != null) {
                            item {
                                OutlinedButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = { viewModel.removeDestination(toSigning, asked, failed) },
                                    enabled = !viewModel.isActionPending.value,
                                ) { Text(stringResource(Res.string.remove)) }
                            }
                            item {
                                Button(
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = { onNavigateToRoute(GroupPayoutsRoute(activeUserPublicKey = activeUserPublicKey, chatRoomId = chatRoomId, chain = GroupWalletStatementEvent.chainName(chain))) },
                                ) { Text(stringResource(Res.string.pay_out)) }
                            }
                        }
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
    if (loaded != null && claiming) {
        ClaimDialog(
            name = loaded.contact.name,
            members = loaded.members.filter { it != loaded.contact.claimedBy }.sortedBy { (loaded.names[it] ?: it).lowercase() },
            names = loaded.names,
            offersNobody = loaded.contact.claimedBy != null,
            onDismiss = { claiming = false },
            onChoose = { member ->
                claiming = false
                viewModel.claim(member, toSigning, asked, failed)
            },
        )
    }
    if (loaded != null && settingDestination) {
        DestinationSheet(
            current = loaded.destination,
            problem = viewModel.destinationProblem,
            pending = viewModel.isActionPending.value,
            onDismiss = { settingDestination = false },
            onAsk = { lightning, onchain ->
                viewModel.setDestination(
                    lightning, onchain,
                    onProposed = { settingDestination = false; toSigning(it) },
                    onAsked = { settingDestination = false; asked() },
                    onFailure = failed,
                )
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

/**
 * Where the group pays a contact: a Lightning address, an unused onchain address, or both, each
 * checked here as a member's own device checks its own before the group is asked
 * (`docs/non-member-deposits.md`, D9).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DestinationSheet(
    current: PayoutDestination?,
    problem: ContactViewModel.DestinationProblem?,
    pending: Boolean,
    onDismiss: () -> Unit,
    onAsk: (lightning: String, onchain: String) -> Unit,
) {
    var lightning by remember { mutableStateOf(current?.lightningAddress?.toString() ?: "") }
    var onchain by remember { mutableStateOf(current?.onchainAddress ?: "") }
    val lightningProblem = when (problem) {
        ContactViewModel.DestinationProblem.LIGHTNING_NOT_AN_ADDRESS -> stringResource(Res.string.that_isn_t_a_lightning_address)
        ContactViewModel.DestinationProblem.LIGHTNING_NOT_FOUND -> stringResource(Res.string.couldn_t_find_that_lightning_address)
        else -> null
    }
    val onchainProblem = when (problem) {
        ContactViewModel.DestinationProblem.ONCHAIN_NOT_AN_ADDRESS -> stringResource(Res.string.that_isn_t_an_onchain_address)
        ContactViewModel.DestinationProblem.ONCHAIN_OTHER_NETWORK -> stringResource(Res.string.that_address_is_for_another_network)
        ContactViewModel.DestinationProblem.ONCHAIN_USED -> stringResource(Res.string.this_address_has_been_used_before)
        ContactViewModel.DestinationProblem.ONCHAIN_UNKNOWN -> stringResource(Res.string.couldn_t_check_the_address)
        ContactViewModel.DestinationProblem.ONCHAIN_THE_GROUPS -> stringResource(Res.string.that_s_one_of_this_group_s_own_addresses)
        else -> null
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.readableContent().padding(MaterialTheme.spacing.screenMargin),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
        ) {
            Text(stringResource(Res.string.where_the_group_pays_them), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = lightning,
                onValueChange = { lightning = it },
                singleLine = true,
                label = { Text(stringResource(Res.string.lightning_address)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                isError = lightningProblem != null,
                supportingText = lightningProblem?.let { { Text(it) } },
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = onchain,
                onValueChange = { onchain = it },
                singleLine = true,
                label = { Text(stringResource(Res.string.onchain_address)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                isError = onchainProblem != null,
                supportingText = onchainProblem?.let { { Text(it) } },
            )
            if (problem == ContactViewModel.DestinationProblem.NEEDS_ONE) {
                Text(stringResource(Res.string.give_a_lightning_or_an_onchain_address), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Text(stringResource(Res.string.the_group_signs_where_it_pays_them), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(modifier = Modifier.fillMaxWidth(), onClick = { onAsk(lightning, onchain) }, enabled = !pending) {
                Text(stringResource(Res.string.ask_the_group))
            }
        }
    }
}

/**
 * Which member a contact was: any current member, or -- correcting a claim -- nobody in the group
 * (`docs/non-member-deposits.md`, D8). Nothing proves it; the group knows who joined.
 */
@Composable
private fun ClaimDialog(
    name: String,
    members: List<HexKey>,
    names: Map<HexKey, String>,
    offersNobody: Boolean,
    onDismiss: () -> Unit,
    onChoose: (HexKey?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.which_member_is_s_question, name)) },
        text = {
            androidx.compose.foundation.layout.Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                members.forEach { member ->
                    TextButton(onClick = { onChoose(member) }) { Text("@" + (names[member] ?: member.take(8))) }
                }
                if (offersNobody) {
                    TextButton(onClick = { onChoose(null) }) { Text(stringResource(Res.string.nobody_in_the_group)) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
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
