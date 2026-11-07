package com.machankura.dedun.compose.ui.composable

import dedun.composeapp.generated.resources.this_address_was_paid_and_the_next_one_is_below
import dedun.composeapp.generated.resources.your_device_signs_only_payouts
import dedun.composeapp.generated.resources.pay_out
import dedun.composeapp.generated.resources.members_balances_and_payouts
import com.machankura.dedun.compose.ui.composable.navigation.routes.GroupPayoutsRoute
import dedun.composeapp.generated.resources.couldn_t_ask_the_group_to_open_your_deposits
import dedun.composeapp.generated.resources.unattributed
import dedun.composeapp.generated.resources.from_s
import dedun.composeapp.generated.resources.everything_paid_here_is_counted_as_yours
import dedun.composeapp.generated.resources.a_member_who_holds_a_share_of_the_group_s_key_can_open_your_deposits
import dedun.composeapp.generated.resources.start_depositing
import dedun.composeapp.generated.resources.the_group_opens_your_deposits_once_signing_a_branch_of_its_wallet_that_is_yours
import dedun.composeapp.generated.resources.set_where_this_group_pays_you_first
import dedun.composeapp.generated.resources.the_group_counts_what_you_put_in_once_it_knows_where_to_pay_you
import dedun.composeapp.generated.resources.your_deposit_address
import com.machankura.dedun.compose.ui.composable.navigation.routes.GroupPayoutDestinationRoute
import com.machankura.dedun.compose.repository.DepositRepository
import com.machankura.dedun.compose.repository.PayoutRepository
import com.machankura.dedun.compose.ui.composable.navigation.routes.GroupAssignCoinsRoute
import dedun.composeapp.generated.resources.not_anyone_s_yet_s_sats
import dedun.composeapp.generated.resources.until_the_group_says_whose_it_is
import dedun.composeapp.generated.resources.assign
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import dedun.composeapp.generated.resources.a_group_below_s
import dedun.composeapp.generated.resources.a_group_below_this_one
import dedun.composeapp.generated.resources.fund_s_s_guarded_wallet_s
import dedun.composeapp.generated.resources.fund_s_s_wallet_guarded_by_s
import dedun.composeapp.generated.resources.guarded_by_n_groups
import dedun.composeapp.generated.resources.guarded_by_s
import dedun.composeapp.generated.resources.guarded_by_s_below_s
import dedun.composeapp.generated.resources.members_wallets
import dedun.composeapp.generated.resources.n_wallets
import dedun.composeapp.generated.resources.s_comma_s
import dedun.composeapp.generated.resources.spend_from_s_s_guarded_wallet_s
import dedun.composeapp.generated.resources.spend_from_s_s_wallet_guarded_by_s
import dedun.composeapp.generated.resources.this_group
import androidx.compose.foundation.clickable
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.unit.dp
import com.machankura.dedun.compose.ui.composable.navigation.routes.ProfilePreviewRoute
import com.machankura.dedun.compose.ui.composable.widgets.profile.ProfileAvatar
import com.machankura.dedun.compose.ui.view.state.PersonGuardedWallets
import com.machankura.dedun.compose.ui.view.state.byPerson
import org.jetbrains.compose.resources.pluralStringResource
import dedun.composeapp.generated.resources.this_group_s_delay_in_this_wallet
import dedun.composeapp.generated.resources.a_group_above_this_one
import dedun.composeapp.generated.resources.guardian_after
import com.machankura.dedun.compose.ui.composable.widgets.delayText
import dedun.composeapp.generated.resources.the_group_above_decided_this_wallet_is_not_guarded
import dedun.composeapp.generated.resources.the_group_above_decided_this_wallet_is_guarded
import com.machankura.dedun.compose.nostr.subgroup.WalletPolicyReading
import com.machankura.dedun.compose.nostr.subgroup.SubgroupWalletPolicy
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.OutlinedCard
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dedun.composeapp.generated.resources.fund
import dedun.composeapp.generated.resources.fund_s_s_guarded_wallet
import dedun.composeapp.generated.resources.s_sats_arriving
import dedun.composeapp.generated.resources.spend_from_s_s_guarded_wallet
import androidx.compose.foundation.layout.Row
import com.machankura.dedun.compose.text.ProposedSpend
import com.machankura.dedun.compose.ui.view.state.SpendRow
import com.machankura.dedun.compose.ui.view.state.SpendStatus
import com.machankura.dedun.compose.wallet.GroupTransactionWatcher
import dedun.composeapp.generated.resources.spends
import dedun.composeapp.generated.resources.waiting_for_signatures
import dedun.composeapp.generated.resources.abandoned
import dedun.composeapp.generated.resources.signed_sending_it_to_the_network
import dedun.composeapp.generated.resources.sent_waiting_for_a_confirmation
import dedun.composeapp.generated.resources.confirmed
import dedun.composeapp.generated.resources.replaced_by_a_later_spend
import dedun.composeapp.generated.resources.send_again
import dedun.composeapp.generated.resources.bump_the_fee
import dedun.composeapp.generated.resources.sent_again
import dedun.composeapp.generated.resources.couldn_t_bump_the_fee
import androidx.compose.material.icons.filled.Send
import dedun.composeapp.generated.resources.send
import com.machankura.dedun.compose.ui.composable.navigation.routes.GroupSendRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.GroupWalletAddressesRoute
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.address_copied
import dedun.composeapp.generated.resources.all_addresses
import dedun.composeapp.generated.resources.anyone_with_these_can_see_every_address
import dedun.composeapp.generated.resources.balance
import dedun.composeapp.generated.resources.balance_not_known_yet
import dedun.composeapp.generated.resources.change
import dedun.composeapp.generated.resources.coins
import dedun.composeapp.generated.resources.copy_address
import dedun.composeapp.generated.resources.couldn_t_ask_the_group_to_open_a_wallet
import dedun.composeapp.generated.resources.each_member_s_share_is_stored_unencrypted
import dedun.composeapp.generated.resources.export_watch_only
import dedun.composeapp.generated.resources.finding_the_group_s_coins
import dedun.composeapp.generated.resources.group_wallet
import dedun.composeapp.generated.resources.s_onchain_wallet
import dedun.composeapp.generated.resources.nothing_has_been_sent_to_this_wallet_yet
import dedun.composeapp.generated.resources.open_a_group_wallet
import dedun.composeapp.generated.resources.open_a_guarded_wallet
import dedun.composeapp.generated.resources.a_subgroup
import dedun.composeapp.generated.resources.at_s
import dedun.composeapp.generated.resources.couldn_t_share_the_wallet
import dedun.composeapp.generated.resources.guarded_wallets
import dedun.composeapp.generated.resources.not_yet_shared_with_the_groups_above
import dedun.composeapp.generated.resources.s_sats_within_this_group_s_reach
import dedun.composeapp.generated.resources.share
import dedun.composeapp.generated.resources.spend
import dedun.composeapp.generated.resources.shared_with_the_groups_above
import dedun.composeapp.generated.resources.tell_the_groups_above
import dedun.composeapp.generated.resources.this_device_shares_these_on_its_own_when_it_can
import dedun.composeapp.generated.resources.wallets_of_groups_below_this_one
import com.machankura.dedun.compose.managers.GuardianManager
import com.machankura.dedun.compose.ui.view.state.GuardedWalletRow
import androidx.compose.foundation.lazy.LazyListScope
import dedun.composeapp.generated.resources.open_without_guardians
import dedun.composeapp.generated.resources.the_groups_above_this_one_have_agreed_to_guard_it
import dedun.composeapp.generated.resources.guarded_the_groups_above_this_one_can_spend
import com.machankura.dedun.compose.managers.GuardianDerivation
import dedun.composeapp.generated.resources.opening_a_wallet_asks_the_group_to_agree
import dedun.composeapp.generated.resources.receive_address
import dedun.composeapp.generated.resources.received
import dedun.composeapp.generated.resources.s_confirmations
import dedun.composeapp.generated.resources.s_dot_s
import dedun.composeapp.generated.resources.s_sats
import dedun.composeapp.generated.resources.s_sats_on_the_way
import dedun.composeapp.generated.resources.this_group_has_not_opened_a_wallet
import dedun.composeapp.generated.resources.waiting_for_a_confirmation
import dedun.composeapp.generated.resources.watch_only_descriptors_copied
import com.machankura.dedun.compose.managers.GroupWalletDerivation
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.FrostSigningRepository
import com.machankura.dedun.compose.text.grouped
import fr.acinq.bitcoin.Satoshi
import com.machankura.dedun.compose.ui.composable.navigation.routes.FrostSigningRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.composable.widgets.EmptyState
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.composable.widgets.PaymentReceived
import com.machankura.dedun.compose.ui.composable.widgets.QRCodeView
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.composable.widgets.rememberNotifier
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.GroupWalletViewModel
import com.machankura.dedun.compose.ui.view.state.GroupWalletUIState
import com.machankura.dedun.compose.wallet.GroupOnchainWallets
import com.machankura.dedun.compose.wallet.bip21Uri
import com.machankura.dedun.compose.wallet.GroupUtxo
import com.machankura.dedun.compose.wallet.GroupWalletState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import org.jetbrains.compose.resources.stringResource

/**
 * The group's wallet: what it holds, where to pay it, and the coins themselves.
 *
 * Pushed from its row on the group's screen. A group that has not opened a
 * wallet gets an empty state that says so -- and, for a member holding a share,
 * the way to ask the group to open one, with what that asks of everybody.
 *
 * The address is the only thing on the screen a person acts on outside the app,
 * so it is shown three ways: as a QR code, as selectable text, and on a copy
 * button. Every one of them is derived on this device from the room's key; the
 * statement the group signed is what says the wallet exists, never where it is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupWalletScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    chain: Chain,
    initialGroupWalletUIState: GroupWalletUIState = GroupWalletUIState.Loading,
    chatRepository: ChatRepository,
    frostSigningRepository: FrostSigningRepository,
    wallets: GroupOnchainWallets,
    watcher: GroupTransactionWatcher? = null,
    depositRepository: DepositRepository = DepositRepository.NO_OP,
    payoutRepository: PayoutRepository = PayoutRepository.NO_OP,
    onNavigateToRoute: (Route) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val groupWalletViewModel: GroupWalletViewModel = viewModel(
        factory = GroupWalletViewModel.factory(
            activeUserPublicKey = activeUserPublicKey,
            chatRoomId = chatRoomId,
            chain = chain,
            initialGroupWalletUIState = initialGroupWalletUIState,
            chatRepository = chatRepository,
            frostSigningRepository = frostSigningRepository,
            wallets = wallets,
            watcher = watcher,
            depositRepository = depositRepository,
            payoutRepository = payoutRepository,
        )
    )

    LaunchedEffect(true) {
        if (initialGroupWalletUIState == GroupWalletUIState.Loading) {
            groupWalletViewModel.initiate()
        }
    }
    val couldNotOpenDeposits = stringResource(Res.string.couldn_t_ask_the_group_to_open_your_deposits)

    val notify = rememberNotifier(rememberCoroutineScope())
    val couldNotOpen = stringResource(Res.string.couldn_t_ask_the_group_to_open_a_wallet)
    val sentAgain = stringResource(Res.string.sent_again)
    val couldNotBump = stringResource(Res.string.couldn_t_bump_the_fee)
    val shared = stringResource(Res.string.shared_with_the_groups_above)
    val couldNotShare = stringResource(Res.string.couldn_t_share_the_wallet)
    // Behind the same two gates as the room's own send: a share to propose
    // with, and a node to see the descendant's coins with.
    val toGuardianSpend = { row: GuardedWalletRow ->
        onNavigateToRoute(
            GroupSendRoute(
                activeUserPublicKey = activeUserPublicKey,
                chatRoomId = chatRoomId,
                relayHint = null,
                guardedChatRoomId = row.wallet.descendantChatRoomId,
            )
        )
    }
    // A member's wallets are gathered under them, and their header opens them:
    // a person is someone to look at, not a room to go into.
    val toPerson = { publicKey: HexKey ->
        onNavigateToRoute(ProfilePreviewRoute(activeUserPublicKey = activeUserPublicKey, profilePublicKey = publicKey))
    }
    val onShare = { shareable: GuardianManager.Shareable ->
        groupWalletViewModel.share(shareable, onShared = { notify(shared) }, onFailure = { notify(couldNotShare) })
    }
    val toSession = { sessionId: String ->
        onNavigateToRoute(FrostSigningRoute(activeUserPublicKey = activeUserPublicKey, chatRoomId = chatRoomId, sessionId = sessionId))
    }

    // Named for its group once the room is known; a room with no subject, and
    // the moment before it loads, fall back to the generic name.
    val groupName = when (val state = groupWalletViewModel.groupWalletUIState) {
        is GroupWalletUIState.NotOpened -> state.localChatRoom.chatRoom.subject
        is GroupWalletUIState.Loaded -> state.localChatRoom.chatRoom.subject
        else -> null
    }

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = groupName?.let { stringResource(Res.string.s_onchain_wallet, it) }
                            ?: stringResource(Res.string.group_wallet),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = { NavigateBackButton(onNavigateBack) }
            )
        }
    ) { padding ->
        ScreenStateTransition(groupWalletViewModel.groupWalletUIState) { uiState ->
            when (val state = uiState) {
                is GroupWalletUIState.Error -> {
                    // Nothing to retry: the room came from a navigation argument.
                    ErrorState(
                        modifier = Modifier.padding(padding),
                        message = state.message,
                        onRetry = null
                    )
                }

                GroupWalletUIState.Loading -> {
                    LoadingDataIndicator(modifier = Modifier.fillMaxWidth().padding(padding))
                }

                is GroupWalletUIState.NotOpened -> {
                    val notOpened = @Composable { modifier: Modifier -> EmptyState(
                        modifier = modifier,
                        message = stringResource(Res.string.this_group_has_not_opened_a_wallet),
                        icon = Icons.Default.AccountBalanceWallet,
                        // Hidden rather than disabled, the way every gated way in
                        // on the group's screens is: a device with no share cannot
                        // propose, and a room with no key has nothing to open.
                        action = if (state.canSign && state.account != null && (state.offersPlain || state.guarded != null)) {
                            {
                                OpenWalletAction(
                                    isPending = groupWalletViewModel.isActionPending.value,
                                    guardedOffered = state.guarded != null,
                                    plainOffered = state.offersPlain,
                                    decided = (state.policy as? WalletPolicyReading.Decided)?.policy,
                                    guardians = state.guarded?.account?.guardians.orEmpty(),
                                    guardianNames = state.guardianNames,
                                    chain = chain,
                                    onOpen = { guarded ->
                                        groupWalletViewModel.openWallet(
                                            guarded = guarded,
                                            onSuccess = { sessionId ->
                                                // Onto the session: the wallet does
                                                // not exist until a quorum signs.
                                                onNavigateToRoute(
                                                    FrostSigningRoute(
                                                        activeUserPublicKey = activeUserPublicKey,
                                                        chatRoomId = chatRoomId,
                                                        sessionId = sessionId
                                                    )
                                                )
                                            },
                                            onFailure = { notify(couldNotOpen) }
                                        )
                                    }
                                )
                            }
                        } else null
                    ) }

                    if (state.guardedWallets.isEmpty() && state.shareable.isEmpty()) {
                        notOpened(Modifier.padding(padding).readableContent())
                    } else {
                        // A group with no wallet of its own can still guard others',
                        // and still carry a descendant's upward.
                        LazyColumn(
                            modifier = Modifier.padding(padding).readableContent().fillMaxSize()
                                .padding(MaterialTheme.spacing.screenMargin),
                            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap)
                        ) {
                            item { notOpened(Modifier) }
                            guardianItems(
                                guardedWallets = state.guardedWallets,
                                shareable = state.shareable,
                                isPending = groupWalletViewModel.isActionPending.value,
                                onShare = onShare,
                                onSpend = if (state.canSign && wallets != null) toGuardianSpend else null,
                                // A group with no wallet of its own has nothing to fund with.
                                onFund = null,
                                onOpenPerson = toPerson,
                            )
                        }
                    }
                }

                is GroupWalletUIState.Loaded -> {
                    OpenWallet(
                        modifier = Modifier.padding(padding),
                        state = state,
                        notify = notify,
                        // The group's own wallet pays only its members, from the payouts
                        // screen, behind a share to propose with. docs/payouts-only.md, Phase 6.
                        onSend = if (state.canSign) {
                            {
                                onNavigateToRoute(
                                    GroupPayoutsRoute(
                                        activeUserPublicKey = activeUserPublicKey,
                                        chatRoomId = chatRoomId,
                                        chain = GroupWalletStatementEvent.chainName(chain),
                                    )
                                )
                            }
                        } else null,
                        onOpenAddresses = {
                            onNavigateToRoute(
                                GroupWalletAddressesRoute(
                                    activeUserPublicKey = activeUserPublicKey,
                                    chatRoomId = chatRoomId,
                                    chain = GroupWalletStatementEvent.chainName(chain)
                                )
                            )
                        },
                        onSetDestination = {
                            onNavigateToRoute(GroupPayoutDestinationRoute(activeUserPublicKey = activeUserPublicKey, chatRoomId = chatRoomId))
                        },
                        onStartDepositing = { groupWalletViewModel.openMyDeposits(onSuccess = toSession, onFailure = { notify(couldNotOpenDeposits) }) },
                        onOpenPayouts = {
                            onNavigateToRoute(
                                GroupPayoutsRoute(
                                    activeUserPublicKey = activeUserPublicKey,
                                    chatRoomId = chatRoomId,
                                    chain = GroupWalletStatementEvent.chainName(chain),
                                )
                            )
                        },
                        onAssignCoins = {
                            onNavigateToRoute(
                                GroupAssignCoinsRoute(
                                    activeUserPublicKey = activeUserPublicKey,
                                    chatRoomId = chatRoomId,
                                    chain = GroupWalletStatementEvent.chainName(chain),
                                )
                            )
                        },
                        onOpenSpend = toSession,
                        onSendAgain = if (watcher != null) {
                            { sessionId -> groupWalletViewModel.sendAgain(sessionId) { notify(sentAgain) } }
                        } else null,
                        onBump = if (state.canSign) {
                            { sessionId ->
                                groupWalletViewModel.bumpFee(sessionId, onSuccess = toSession, onFailure = { notify(couldNotBump) })
                            }
                        } else null,
                        isPending = groupWalletViewModel.isActionPending.value,
                        onShare = onShare,
                        onSpend = if (state.canSign) toGuardianSpend else null,
                        // A funding pays a guarded wallet, not a member: not a payout, and so
                        // nothing the group's own wallet signs. docs/payouts-only.md, D9.
                        onFund = null,
                        onOpenPerson = toPerson,
                    )
                }
            }
        }
    }
}

/**
 * What opening a wallet asks of the group, said before the button rather than
 * after it: that any quorum can then spend together and nobody alone, and what
 * an unencrypted share on a member's device means once there are coins behind it.
 */
@Composable
private fun OpenWalletAction(
    isPending: Boolean,
    guardedOffered: Boolean,
    plainOffered: Boolean,
    /** What the room's parent decided at its birth, or null when it left it to the room. */
    decided: SubgroupWalletPolicy?,
    /** The guardians a guarded wallet would name, nearest first, each with its own delay. */
    guardians: List<GuardianDerivation.Guardian>,
    guardianNames: Map<String, String>,
    chain: Chain,
    onOpen: (guarded: Boolean) -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap)
    ) {
        Text(
            text = stringResource(Res.string.opening_a_wallet_asks_the_group_to_agree),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(Res.string.each_member_s_share_is_stored_unencrypted),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (decided != null) {
            // The parent decided at the room's birth, and its quorum signed it:
            // one way in, and the line says whose decision it was. Every other
            // member refuses the other statement, so offering it would only
            // strand a quorum. See docs/subgroup-wallet-policy.md.
            Text(
                text = when (decided) {
                    SubgroupWalletPolicy.GUARDED -> stringResource(Res.string.the_group_above_decided_this_wallet_is_guarded)
                    SubgroupWalletPolicy.UNGUARDED -> stringResource(Res.string.the_group_above_decided_this_wallet_is_not_guarded)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (decided == SubgroupWalletPolicy.GUARDED) GuardianDelays(guardians, guardianNames, chain)
            val guarded = decided == SubgroupWalletPolicy.GUARDED
            if (guarded && guardedOffered || !guarded && plainOffered) {
                Button(onClick = { onOpen(guarded) }, enabled = !isPending) {
                    Icon(Icons.Default.AccountBalanceWallet, contentDescription = Decorative)
                    Spacer(modifier = Modifier.width(MaterialTheme.spacing.relatedGap))
                    Text(stringResource(if (guarded) Res.string.open_a_guarded_wallet else Res.string.open_a_group_wallet))
                }
            }
        } else if (guardedOffered) {
            // Undecided: a subgroup certified before parents decided. The default
            // for one whose parent agreed to guard it, and the plain wallet beside
            // it rather than hidden: being guarded is then the group's own choice,
            // and its quorum signing the statement is it making that choice.
            Text(
                text = stringResource(Res.string.the_groups_above_this_one_have_agreed_to_guard_it),
                style = MaterialTheme.typography.bodyMedium,
            )
            GuardianDelays(guardians, guardianNames, chain)
            Button(onClick = { onOpen(true) }, enabled = !isPending) {
                Icon(Icons.Default.AccountBalanceWallet, contentDescription = Decorative)
                Spacer(modifier = Modifier.width(MaterialTheme.spacing.relatedGap))
                Text(stringResource(Res.string.open_a_guarded_wallet))
            }
            OutlinedButton(onClick = { onOpen(false) }, enabled = !isPending) {
                Text(stringResource(Res.string.open_without_guardians))
            }
        } else {
            Button(onClick = { onOpen(false) }, enabled = !isPending) {
                Icon(Icons.Default.AccountBalanceWallet, contentDescription = Decorative)
                Spacer(modifier = Modifier.width(MaterialTheme.spacing.relatedGap))
                Text(stringResource(Res.string.open_a_group_wallet))
            }
        }
    }
}

@Composable
private fun OpenWallet(
    modifier: Modifier,
    state: GroupWalletUIState.Loaded,
    notify: (String) -> Unit,
    onSend: (() -> Unit)?,
    onOpenAddresses: () -> Unit,
    onSetDestination: () -> Unit,
    onStartDepositing: () -> Unit,
    onOpenPayouts: () -> Unit,
    onAssignCoins: () -> Unit,
    onOpenSpend: (sessionId: String) -> Unit,
    onSendAgain: ((sessionId: String) -> Unit)?,
    onBump: ((sessionId: String) -> Unit)?,
    isPending: Boolean,
    onShare: (GuardianManager.Shareable) -> Unit,
    onSpend: ((GuardedWalletRow) -> Unit)?,
    onFund: ((GuardedWalletRow) -> Unit)?,
    onOpenPerson: (HexKey) -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current
    val addressCopied = stringResource(Res.string.address_copied)
    val descriptorsCopied = stringResource(Res.string.watch_only_descriptors_copied)
    val walletState = state.walletState

    LazyColumn(
        modifier = modifier.readableContent().fillMaxSize()
            .padding(MaterialTheme.spacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap)
    ) {
        item { BalanceCard(walletState) }

        // Every member's balance and destination, and paying them out: docs/payouts-only.md.
        item {
            OutlinedButton(onClick = onOpenPayouts) { Text(stringResource(Res.string.members_balances_and_payouts)) }
        }

        if (state.wallet.account.isGuarded) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)) {
                    Text(
                        text = stringResource(Res.string.guarded_the_groups_above_this_one_can_spend),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    GuardianDelays(state.wallet.account.guardians, state.guardianNames, state.wallet.chain)
                }
            }
        }

        onSend?.let { send ->
            item {
                Button(onClick = send) {
                    Icon(Icons.Default.Send, contentDescription = Decorative)
                    Spacer(modifier = Modifier.width(MaterialTheme.spacing.relatedGap))
                    Text(stringResource(Res.string.pay_out))
                }
            }
        }

        // What this device will sign from here, said once: docs/payouts-only.md, D12.
        item {
            Text(
                text = stringResource(Res.string.your_device_signs_only_payouts),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        when {
            walletState == null -> item {
                LoadingDataIndicator(
                    fillScreen = false,
                    text = stringResource(Res.string.finding_the_group_s_coins)
                )
            }

            else -> {
                // Only an address that is somebody's: the member's own, on their own branch.
                // docs/member-deposits.md, Phase 5.
                item {
                    DepositCard(
                        standing = state.deposits,
                        address = (state.deposits as? DepositRepository.Standing.Open)?.let { walletState.depositAddressOf(it.depositor.member) },
                        justPaidSat = state.justPaidDeposit?.balance,
                        isPending = isPending,
                        onCopy = { address ->
                            clipboardManager.setText(buildAnnotatedString { append(address) })
                            notify(addressCopied)
                        },
                        onOpenAddresses = onOpenAddresses,
                        onSetDestination = onSetDestination,
                        onStartDepositing = onStartDepositing,
                    )
                }

                // A coin nobody owns can't be paid out until the group says whose it is.
                // docs/payouts-only.md, Phase 3.
                if (state.unownedSat > 0) {
                    item {
                        ListItem(
                            headlineContent = { Text(stringResource(Res.string.not_anyone_s_yet_s_sats, Satoshi(state.unownedSat).grouped())) },
                            supportingContent = { Text(stringResource(Res.string.until_the_group_says_whose_it_is)) },
                            trailingContent = { TextButton(onClick = onAssignCoins) { Text(stringResource(Res.string.assign)) } },
                        )
                    }
                }

                item {
                    Text(
                        text = stringResource(Res.string.coins),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }

                if (walletState.utxos.isEmpty()) {
                    item {
                        EmptyState(message = stringResource(Res.string.nothing_has_been_sent_to_this_wallet_yet))
                    }
                } else {
                    items(walletState.utxos, key = { it.utxo.outPoint.toString() }) { utxo ->
                        val member = walletState.memberOfBranch(utxo.derivation.change)
                        CoinRow(
                            utxo = utxo,
                            confirmations = walletState.confirmations(utxo),
                            from = member?.let { state.memberNames[it] ?: it.take(8) },
                        )
                    }
                }
            }
        }

        // Under the coins and for every member, node or not: a spend is the
        // group's decision, and a member who cannot see the chain can still see
        // what the group has asked of it and where each request has got to.
        if (state.spends.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(Res.string.spends),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            items(state.spends, key = { it.transaction.frostSigningSessionId }) { row ->
                SpendRowCard(
                    row = row,
                    chain = state.wallet.chain,
                    memberNames = state.memberNames,
                    onOpen = { onOpenSpend(row.transaction.frostSigningSessionId) },
                    onSendAgain = onSendAgain?.takeIf { row.canSendAgain }?.let { { it(row.transaction.frostSigningSessionId) } },
                    onBump = onBump?.takeIf { row.canBump }?.let { { it(row.transaction.frostSigningSessionId) } },
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)) {
                TextButton(
                    onClick = {
                        val account = state.wallet.account
                        clipboardManager.setText(
                            buildAnnotatedString {
                                append(account.receiveDescriptor)
                                append("\n")
                                append(account.changeDescriptor)
                            }
                        )
                        notify(descriptorsCopied)
                    }
                ) {
                    Icon(Icons.Default.Visibility, contentDescription = Decorative)
                    Spacer(modifier = Modifier.width(MaterialTheme.spacing.relatedGap))
                    Text(stringResource(Res.string.export_watch_only))
                }
                Text(
                    text = stringResource(Res.string.anyone_with_these_can_see_every_address),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        guardianItems(
            guardedWallets = state.guardedWallets,
            shareable = state.shareable,
            isPending = isPending,
            onShare = onShare,
            onSpend = onSpend,
            onFund = onFund,
            onOpenPerson = onOpenPerson,
        )
    }
}

/** One spend: what it sends, where it has got to, and what can still be done about it. */
@Composable
private fun SpendRowCard(
    row: SpendRow,
    chain: Chain,
    memberNames: Map<HexKey, String> = emptyMap(),
    onOpen: () -> Unit,
    onSendAgain: (() -> Unit)?,
    onBump: (() -> Unit)?,
) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onOpen) {
        ListItem(
            headlineContent = {
                Text(
                    text = (row.spend?.let { ProposedSpend.summarize(it, chain, row.payout, memberNames::get) } ?: row.sweep?.let(ProposedSpend::summarize) ?: ProposedSpend.UNREADABLE).detail,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            supportingContent = { Text(stringResource(row.status.label())) },
        )
        if (onSendAgain != null || onBump != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacing.compactPadding),
                horizontalArrangement = Arrangement.End,
            ) {
                onSendAgain?.let { TextButton(onClick = it) { Text(stringResource(Res.string.send_again)) } }
                onBump?.let { TextButton(onClick = it) { Text(stringResource(Res.string.bump_the_fee)) } }
            }
        }
    }
}

private fun SpendStatus.label() = when (this) {
    SpendStatus.WAITING_FOR_SIGNATURES -> Res.string.waiting_for_signatures
    SpendStatus.ABANDONED -> Res.string.abandoned
    SpendStatus.SIGNED -> Res.string.signed_sending_it_to_the_network
    SpendStatus.SENT -> Res.string.sent_waiting_for_a_confirmation
    SpendStatus.CONFIRMED -> Res.string.confirmed
    SpendStatus.REPLACED -> Res.string.replaced_by_a_later_spend
}

/** Confirmed and on-the-way said apart: only the first can be spent by a send-all. */
/**
 * The guarded wallets of groups below this one, and the guarded statements this
 * device can still carry to the groups above. Both under the room's own wallet,
 * or in place of it for a group that has none.
 */
private fun LazyListScope.guardianItems(
    guardedWallets: List<GuardedWalletRow>,
    shareable: List<GuardianManager.Shareable>,
    isPending: Boolean,
    onShare: (GuardianManager.Shareable) -> Unit,
    onSpend: ((GuardedWalletRow) -> Unit)?,
    onFund: ((GuardedWalletRow) -> Unit)?,
    onOpenPerson: (HexKey) -> Unit,
) {
    if (guardedWallets.isNotEmpty()) {
        item {
            Text(text = stringResource(Res.string.guarded_wallets), style = MaterialTheme.typography.titleMedium)
        }
        item {
            Text(
                text = stringResource(Res.string.wallets_of_groups_below_this_one),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Subgroups' wallets, each a room; then members', gathered by person, so
        // that one member's wallets under different groups below read as one
        // person's. docs/member-wallet-guarding-group.md, D6.
        val subgroups = guardedWallets.filter { it.wallet.subject is GuardianManager.GuardedSubject.Subgroup }
        items(subgroups, key = { "guarded-" + it.wallet.statement.id }) { row ->
            GuardedWalletCard(row = row, onSpend = onSpend, onFund = onFund)
        }
        val people = guardedWallets.byPerson()
        if (people.isNotEmpty()) {
            item {
                Text(text = stringResource(Res.string.members_wallets), style = MaterialTheme.typography.titleSmall)
            }
            people.forEach { person ->
                item(key = "person-" + person.ownerPublicKey) {
                    PersonHeader(person = person, onOpen = { onOpenPerson(person.ownerPublicKey) })
                }
                items(person.rows, key = { "guarded-" + it.wallet.statement.id }) { row ->
                    GuardedWalletCard(
                        row = row,
                        onSpend = onSpend,
                        onFund = onFund,
                        // Under the header, so the cards read as the person's.
                        modifier = Modifier.padding(start = MaterialTheme.spacing.containerPadding),
                    )
                }
            }
        }
    }

    if (shareable.isNotEmpty()) {
        item {
            Text(text = stringResource(Res.string.tell_the_groups_above), style = MaterialTheme.typography.titleMedium)
        }
        // What is left here is what the automatic forwarding could not send --
        // a notice lost on the wire, most often -- so the section says so, once.
        // docs/guarded-wallet-forwarding.md, Phase 6.
        item {
            Text(
                text = stringResource(Res.string.this_device_shares_these_on_its_own_when_it_can),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(shareable, key = { "shareable-" + it.statement.id }) { candidate ->
            ListItem(
                headlineContent = {
                    Text(
                        text = candidate.ownerPublicKey?.let { stringResource(Res.string.at_s, candidate.ownerName ?: it.take(8)) }
                            ?: candidate.name
                            ?: stringResource(Res.string.a_subgroup),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = { Text(stringResource(Res.string.not_yet_shared_with_the_groups_above)) },
                trailingContent = {
                    TextButton(onClick = { onShare(candidate) }, enabled = !isPending) {
                        Text(stringResource(Res.string.share))
                    }
                },
            )
        }
    }
}

/**
 * Each guardian of a guarded wallet with its own delay, nearest first:
 * *#Council: after 144 blocks, about 1 day*. One sentence with one number in it
 * stopped being true once each group chose its own -- `docs/guardian-delay.md`.
 */
@Composable
private fun GuardianDelays(
    guardians: List<GuardianDerivation.Guardian>,
    names: Map<String, String>,
    chain: Chain,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)) {
        guardians.forEach { guardian ->
            Text(
                text = stringResource(
                    Res.string.guardian_after,
                    names[guardian.chatRoomId] ?: stringResource(Res.string.a_group_above_this_one),
                    delayText(guardian.delay, chain),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * One descendant's guarded wallet: its name and what is within reach, and under
 * them the actions, in the card's own row rather than the list item's trailing
 * slot. Two buttons there would leave the name -- the one thing that says which
 * wallet this is -- a few characters at large text sizes, and a flow row wraps
 * them instead. Each button names its wallet, since a screen reader hears every
 * row's *Fund* and *Spend* alike. See `docs/guarded-wallet-funding.md`, Phase 5.
 */
@Composable
private fun GuardedWalletCard(
    row: GuardedWalletRow,
    onSpend: ((GuardedWalletRow) -> Unit)?,
    onFund: ((GuardedWalletRow) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val name = guardedName(row.wallet.subject)
    val member = row.wallet.subject as? GuardianManager.GuardedSubject.Member
    val guardedBy = member?.let { guardedByLine(row.wallet, it) }
    // A member's card sits under their header, so it is named by its label --
    // or, with none, by the group that guards it, which still tells two of one
    // person's wallets apart.
    val headline = when {
        member == null -> name
        else -> member.label ?: guardedBy.orEmpty()
    }
    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        ListItem(
            leadingContent = { Icon(Icons.Default.AccountBalanceWallet, contentDescription = Decorative) },
            // Two lines, since the name is what says which wallet the buttons act on.
            headlineContent = { Text(headline, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                val reach = row.withinReach?.let { stringResource(Res.string.s_sats_within_this_group_s_reach, it.grouped()) }
                val arriving = row.arriving?.takeIf { it.sat > 0 }?.let { stringResource(Res.string.s_sats_arriving, it.grouped()) }
                val amounts = when {
                    reach == null -> stringResource(Res.string.balance_not_known_yet)
                    arriving == null -> reach
                    else -> stringResource(Res.string.s_dot_s, reach, arriving)
                }
                Column {
                    // Which group below guards it, where the headline does not say.
                    if (member?.label != null && guardedBy != null) Text(guardedBy)
                    Text(amounts)
                    // This group's own leaf, which two of its descendants can have
                    // set differently. docs/guardian-delay.md.
                    Text(stringResource(Res.string.this_group_s_delay_in_this_wallet, delayText(row.wallet.delay, row.wallet.chain)))
                }
            },
        )
        if (onFund != null || onSpend != null) {
            // Each names its wallet and not only its person: one member can have
            // several here, and a screen reader hears every one's *Fund* alike.
            val fundLabel = when {
                member == null -> stringResource(Res.string.fund_s_s_guarded_wallet, name)
                member.label != null -> stringResource(Res.string.fund_s_s_guarded_wallet_s, name, member.label)
                else -> stringResource(Res.string.fund_s_s_wallet_guarded_by_s, name, guardianPhrase(row.wallet, member))
            }
            val spendLabel = when {
                member == null -> stringResource(Res.string.spend_from_s_s_guarded_wallet, name)
                member.label != null -> stringResource(Res.string.spend_from_s_s_guarded_wallet_s, name, member.label)
                else -> stringResource(Res.string.spend_from_s_s_wallet_guarded_by_s, name, guardianPhrase(row.wallet, member))
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(
                    start = MaterialTheme.spacing.containerPadding,
                    end = MaterialTheme.spacing.containerPadding,
                    bottom = MaterialTheme.spacing.compactPadding,
                ),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.targetGap, Alignment.End),
            ) {
                // Money in before money out, at equal weight: neither is the one
                // every member wants, and a filled button would say it was.
                onFund?.let { fund ->
                    TextButton(onClick = { fund(row) }, modifier = Modifier.semantics { contentDescription = fundLabel }) {
                        Text(stringResource(Res.string.fund))
                    }
                }
                onSpend?.let { spend ->
                    TextButton(onClick = { spend(row) }, modifier = Modifier.semantics { contentDescription = spendLabel }) {
                        Text(stringResource(Res.string.spend))
                    }
                }
            }
        }
    }
}

/**
 * One member, heading their wallets in an ancestor's list: how many this group
 * guards, and under how many groups below it -- the summary that shows a person
 * with wallets in more than one branch. Opens their profile.
 */
@Composable
private fun PersonHeader(person: PersonGuardedWallets, onOpen: () -> Unit) {
    val name = stringResource(Res.string.at_s, person.ownerName ?: person.ownerPublicKey.take(8))
    val wallets = pluralStringResource(Res.plurals.n_wallets, person.rows.size, person.rows.size)
    val groups = pluralStringResource(Res.plurals.guarded_by_n_groups, person.guardianCount, person.guardianCount)
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen).minimumInteractiveComponentSize(),
        // 40dp is the avatar's size, a dimension: M3's list item leading avatar.
        leadingContent = { ProfileAvatar(size = 40.dp, publicKey = person.ownerPublicKey, picture = null, name = person.ownerName) },
        headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(stringResource(Res.string.s_comma_s, wallets, groups)) },
    )
}

/**
 * The group that directly guards a member's wallet, as a phrase inside a
 * sentence: *this group*, its name, or -- with no name this device can check --
 * where it sits, *a group below #P*. `docs/member-wallet-guarding-group.md`, D2.
 */
@Composable
internal fun guardianPhrase(wallet: GuardianManager.GuardedWallet, member: GuardianManager.GuardedSubject.Member): String = when {
    wallet.isGuardedHere -> stringResource(Res.string.this_group)
    member.guardianName != null -> member.guardianName
    member.branchName != null -> stringResource(Res.string.a_group_below_s, member.branchName)
    else -> stringResource(Res.string.a_group_below_this_one)
}

/**
 * *Guarded by #N*, or *Guarded by #M, below #P* where the group is further down
 * than this one's own child -- the branch, so wallets from one part of the tree
 * read as being from there. `docs/member-wallet-guarding-group.md`, D6.
 */
@Composable
internal fun guardedByLine(wallet: GuardianManager.GuardedWallet, member: GuardianManager.GuardedSubject.Member): String =
    if (wallet.position >= 2 && member.guardianName != null && member.branchName != null) {
        stringResource(Res.string.guarded_by_s_below_s, member.guardianName, member.branchName)
    } else {
        stringResource(Res.string.guarded_by_s, guardianPhrase(wallet, member))
    }

/**
 * A guarded wallet as an ancestor's list names it: a subgroup by the name its
 * parent certified -- `#Translation` -- and a member by their profile's name --
 * `@alice` -- falling back to the start of their key. A person's name is theirs
 * and live; the certificate fixes who they are, not what they are called.
 */
@Composable
internal fun guardedName(subject: GuardianManager.GuardedSubject): String = when (subject) {
    is GuardianManager.GuardedSubject.Subgroup -> subject.name ?: stringResource(Res.string.a_subgroup)
    is GuardianManager.GuardedSubject.Member -> stringResource(Res.string.at_s, subject.ownerName ?: subject.ownerPublicKey.take(8))
}

@Composable
internal fun BalanceCard(walletState: GroupWalletState?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.containerPadding),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)
        ) {
            Text(
                text = stringResource(Res.string.balance),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = walletState?.let { stringResource(Res.string.s_sats, it.confirmedBalance.grouped()) }
                    ?: stringResource(Res.string.balance_not_known_yet),
                style = MaterialTheme.typography.headlineMedium,
            )
            walletState?.pendingBalance?.takeIf { it.sat > 0 }?.let { pending ->
                Text(
                    text = stringResource(Res.string.s_sats_on_the_way, pending.grouped()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Whether the active profile is handed a deposit address, and the address when it is: their own,
 * on their branch, or the step that comes first -- a payout destination, then the group opening
 * their deposits. A profile that can't sign is handed nothing. `docs/member-deposits.md`, Phase 5.
 */
@Composable
internal fun DepositCard(
    standing: DepositRepository.Standing,
    address: String?,
    /** What arrived at the address this card was handing out, once it has been paid (`docs/paid-requests.md`, D7). */
    justPaidSat: Satoshi?,
    isPending: Boolean,
    onCopy: (String) -> Unit,
    onOpenAddresses: () -> Unit,
    onSetDestination: () -> Unit,
    onStartDepositing: () -> Unit,
) {
    if (standing == DepositRepository.Standing.CannotSign) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.containerPadding),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap)
        ) {
            Text(text = stringResource(Res.string.your_deposit_address), style = MaterialTheme.typography.titleMedium)
            when (standing) {
                DepositRepository.Standing.CannotSign -> Unit
                DepositRepository.Standing.NeedsDestination -> {
                    Text(
                        text = stringResource(Res.string.the_group_counts_what_you_put_in_once_it_knows_where_to_pay_you),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onSetDestination) { Text(stringResource(Res.string.set_where_this_group_pays_you_first)) }
                }
                is DepositRepository.Standing.CanOpen -> {
                    Text(
                        text = stringResource(Res.string.the_group_opens_your_deposits_once_signing_a_branch_of_its_wallet_that_is_yours),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (standing.canPropose) {
                        Button(onClick = onStartDepositing, enabled = !isPending) { Text(stringResource(Res.string.start_depositing)) }
                    } else {
                        Text(
                            text = stringResource(Res.string.a_member_who_holds_a_share_of_the_group_s_key_can_open_your_deposits),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is DepositRepository.Standing.Open -> if (address == null) {
                    LoadingDataIndicator(fillScreen = false, text = stringResource(Res.string.finding_the_group_s_coins))
                } else {
                    // The address below has already rotated; what was missing was the saying so.
                    justPaidSat?.let { arrived ->
                        PaymentReceived(
                            amount = arrived,
                            note = stringResource(Res.string.this_address_was_paid_and_the_next_one_is_below),
                        )
                    }
                    // BIP21, so a scanning wallet knows what the text is.
                    QRCodeView(bip21Uri(address))
                    SelectionContainer {
                        Text(text = address, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        text = stringResource(Res.string.everything_paid_here_is_counted_as_yours),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { onCopy(address) }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = Decorative)
                        Spacer(modifier = Modifier.width(MaterialTheme.spacing.relatedGap))
                        Text(stringResource(Res.string.copy_address))
                    }
                    TextButton(onClick = onOpenAddresses) {
                        Icon(Icons.Default.FormatListNumbered, contentDescription = Decorative)
                        Spacer(modifier = Modifier.width(MaterialTheme.spacing.relatedGap))
                        Text(stringResource(Res.string.all_addresses))
                    }
                }
            }
        }
    }
}

/**
 * One coin: how much, whose -- [from] for a member's deposit branch, *Change*, or *Unattributed*
 * for the legacy receive branch -- and how deep. `docs/member-deposits.md`, Phase 5.
 */
@Composable
internal fun CoinRow(utxo: GroupUtxo, confirmations: Int, from: String? = null) {
    ListItem(
        leadingContent = { Icon(Icons.Default.AccountBalanceWallet, contentDescription = Decorative) },
        headlineContent = { Text(stringResource(Res.string.s_sats, utxo.amount.grouped())) },
        supportingContent = {
            val branch = when {
                utxo.derivation.change == GroupWalletDerivation.CHANGE -> stringResource(Res.string.change)
                from != null -> stringResource(Res.string.from_s, "@$from")
                else -> stringResource(Res.string.unattributed)
            }
            val depth = if (confirmations == 0) {
                stringResource(Res.string.waiting_for_a_confirmation)
            } else {
                stringResource(Res.string.s_confirmations, confirmations.toString())
            }
            Text(text = stringResource(Res.string.s_dot_s, branch, depth), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    )
}
