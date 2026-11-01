package com.machankura.dedun.compose.ui.composable

import androidx.compose.material.icons.filled.Bolt
import dedun.composeapp.generated.resources.lightning_wallet
import dedun.composeapp.generated.resources.lightning_needs_an_onchain_wallet
import dedun.composeapp.generated.resources.member_removal_requested_lightning
import com.machankura.dedun.compose.ui.composable.navigation.routes.GroupLightningWalletRoute
import com.machankura.dedun.compose.wallet.GroupLightningRollout
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import dedun.composeapp.generated.resources.change
import dedun.composeapp.generated.resources.not_set
import dedun.composeapp.generated.resources.payouts
import dedun.composeapp.generated.resources.set
import dedun.composeapp.generated.resources.where_this_group_pays_you
import dedun.composeapp.generated.resources.guard_members_wallets
import com.machankura.dedun.compose.nostr.wallet.MemberWalletPolicyEvent
import com.machankura.dedun.compose.ui.composable.navigation.routes.MemberWalletPolicyRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.GroupPayoutDestinationRoute
import com.machankura.dedun.compose.repository.PayoutDestinationRepository
import dedun.composeapp.generated.resources.guarded_wallet_after
import com.machankura.dedun.compose.ui.composable.widgets.delayText
import com.machankura.dedun.compose.managers.MemberWalletManager
import com.machankura.dedun.compose.repository.MemberWalletRepository
import com.machankura.dedun.compose.ui.composable.navigation.routes.NewMemberWalletRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.FrostSigningRoute
import dedun.composeapp.generated.resources.members_guarded_wallets
import dedun.composeapp.generated.resources.ask_this_group_to_guard_a_wallet_for_you
import dedun.composeapp.generated.resources.asked_by_s_not_yet_signed
import dedun.composeapp.generated.resources.put_to_the_group
import dedun.composeapp.generated.resources.couldn_t_put_it_to_the_group
import dedun.composeapp.generated.resources.at_s
import dedun.composeapp.generated.resources.s_dot_s
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextOverflow
import dedun.composeapp.generated.resources.unguarded_wallet
import dedun.composeapp.generated.resources.guarded_wallet
import com.machankura.dedun.compose.nostr.subgroup.WalletPolicyReading
import com.machankura.dedun.compose.nostr.subgroup.SubgroupWalletPolicy
import com.machankura.dedun.compose.wallet.GroupWalletRollout
import com.machankura.dedun.compose.ui.composable.navigation.routes.GroupWalletRoute
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import dedun.composeapp.generated.resources.open_on_s
import dedun.composeapp.generated.resources.not_opened_yet
import dedun.composeapp.generated.resources.group_wallet
import dedun.composeapp.generated.resources.s_onchain_wallet
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Payments
import androidx.compose.runtime.remember
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestination
import com.machankura.dedun.compose.wallet.UnusedAddressCheck
import dedun.composeapp.generated.resources.checking
import dedun.composeapp.generated.resources.copied
import dedun.composeapp.generated.resources.copy_the_lightning_address
import dedun.composeapp.generated.resources.copy_the_onchain_address
import dedun.composeapp.generated.resources.couldn_t_check
import dedun.composeapp.generated.resources.hasn_t_said_where_to_be_paid
import dedun.composeapp.generated.resources.lightning_address
import dedun.composeapp.generated.resources.onchain_address
import dedun.composeapp.generated.resources.paid_before_ask_for_a_new_one
import dedun.composeapp.generated.resources.set_on
import dedun.composeapp.generated.resources.unused
import dedun.composeapp.generated.resources.where_s_is_paid
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Unsubscribe
import androidx.compose.material.icons.filled.WaterfallChart
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.GroupKeyState
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.database.model.Participant
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.intermdiate.LocalParticipant
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.ui.composable.navigation.routes.ProfilePreviewRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.composable.navigation.routes.CreateSharedKeyRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.ProposalListRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.SearchMemberToAddToChatRoomRoute
import com.machankura.dedun.compose.extensions.inComparableGroups
import com.machankura.dedun.compose.extensions.toFormattedTimeAndDateString
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.dialogs.ModalBottomSheet
import com.machankura.dedun.compose.ui.composable.widgets.rememberNotifier
import com.machankura.dedun.compose.ui.composable.widgets.profile.ProfileAvatar
import com.machankura.dedun.compose.ui.theme.DedunTheme
import com.machankura.dedun.compose.ui.view.model.ChatRoomDetailViewModel
import com.machankura.dedun.compose.ui.view.state.ChatRoomDetailUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.ui.theme.spacing
import dedun.composeapp.generated.resources.run_by_members
import dedun.composeapp.generated.resources.parent_group
import dedun.composeapp.generated.resources.a_subgroup
import dedun.composeapp.generated.resources.created_you_are_not_a_member
import dedun.composeapp.generated.resources.certified_not_yet_created
import dedun.composeapp.generated.resources.no_subgroups_have_been_made_by_this_group
import dedun.composeapp.generated.resources.add_subgroup
import dedun.composeapp.generated.resources.subgroups
import com.machankura.dedun.compose.ui.composable.navigation.routes.SelectSubgroupAdminsRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.NostrEventDetailRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.ChatRoomDetailRoute
import com.machankura.dedun.compose.managers.SubgroupManager
import dedun.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import dedun.composeapp.generated.resources.delete_group
import dedun.composeapp.generated.resources.direct_message_detail
import dedun.composeapp.generated.resources.direct_message_functionality_will_be_here
import dedun.composeapp.generated.resources.invite_new_member
import dedun.composeapp.generated.resources.leave_group
import dedun.composeapp.generated.resources.remove_from_group
import dedun.composeapp.generated.resources.disband_group
import dedun.composeapp.generated.resources.disband_group_explainer
import dedun.composeapp.generated.resources.group_disbanded
import dedun.composeapp.generated.resources.member_removal_requested
import dedun.composeapp.generated.resources.members_2
import dedun.composeapp.generated.resources.proposals
import dedun.composeapp.generated.resources.reindex_events
import dedun.composeapp.generated.resources.shared_key
import dedun.composeapp.generated.resources.signing_key
import dedun.composeapp.generated.resources.the_key_this_room_signs_as
import dedun.composeapp.generated.resources.what_the_group_signed
import dedun.composeapp.generated.resources.this_room_signs_as
import dedun.composeapp.generated.resources.derivation_path
import dedun.composeapp.generated.resources.key_ceremony
import dedun.composeapp.generated.resources.agreed_on
import dedun.composeapp.generated.resources.the_signed_event
import dedun.composeapp.generated.resources.copy_the_signed_event
import dedun.composeapp.generated.resources.copied_the_signed_event
import dedun.composeapp.generated.resources.this_device_does_not_hold_the_event
import dedun.composeapp.generated.resources.event_s_still_unreadable
import dedun.composeapp.generated.resources.recovered_of_event_s
import dedun.composeapp.generated.resources.recovered_of_still_unreadable
import androidx.compose.material3.SnackbarHost
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.theme.ConformancePreviews
import kotlin.time.Instant

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ChatRoomDetailScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    relayHint: String?,
    initialChatRoomDetailUIState: ChatRoomDetailUIState = ChatRoomDetailUIState.Loading,
    nostrRepository: NostrRepository,
    chatRepository: ChatRepository,
    memberWalletRepository: MemberWalletRepository = MemberWalletRepository.NO_OP,
    payoutDestinationRepository: PayoutDestinationRepository = PayoutDestinationRepository.NO_OP,
    onNavigateToRoute: (Route) -> Unit,
    onPopBackToRoute: (Route) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val chatRoomDetailViewModel: ChatRoomDetailViewModel = viewModel(
        factory = ChatRoomDetailViewModel.factory(
            chatRoomId = chatRoomId,
            relayHint = relayHint,
            initialChatRoomDetailUIState = initialChatRoomDetailUIState,
            nostrRepository = nostrRepository,
            chatRepository = chatRepository,
            activeUserPublicKey = activeUserPublicKey,
            memberWalletRepository = memberWalletRepository,
            payoutDestinationRepository = payoutDestinationRepository,
        )
    )
    val couldNotPutToGroup = stringResource(Res.string.couldn_t_put_it_to_the_group)

    // Read outside the click handlers: stringResource and rememberNotifier are both
    // composable, and a lambda passed to onClick is not.
    val notify = rememberNotifier(rememberCoroutineScope())
    val removalRequested = stringResource(Res.string.member_removal_requested)
    val removalLeavesLightningShare = stringResource(Res.string.member_removal_requested_lightning)

    ScreenStateTransition(chatRoomDetailViewModel.chatRoomDetailUIState) { uiState ->
    when (val chatRoomDetailUIState = uiState) {
            is ChatRoomDetailUIState.Error -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(
                        modifier = Modifier.height(MaterialTheme.spacing.space600)
                    )
                    Text(
                        text = chatRoomDetailUIState.message,
                    )
                }
            }
            is ChatRoomDetailUIState.Loaded -> {
                Scaffold(
                    snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
                    topBar = {
                        TopAppBar(
                            title = {
                                chatRoomDetailUIState.localChatRoom.RenderChatRoomTitleText()
                            },
                            navigationIcon = { NavigateBackButton(onNavigateBack) },
                        )
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier.padding(innerPadding).readableContent().fillMaxSize()
                    ) {
                        var isShowingGroupKeyState by rememberSaveable { mutableStateOf(false) }
                        // The member whose payout sheet is open, by public key.
                        var showingPayoutOf by rememberSaveable { mutableStateOf<String?>(null) }

                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth().padding(MaterialTheme.spacing.space250),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125)
                        ) {
                            // First on the screen, because it is the fact the rest
                            // of the room's signed work stands on: which key the
                            // room signs as decides whether anything it has signed
                            // will be believed anywhere.
                            //
                            // Absent rather than empty on a room the group has said
                            // nothing about. There is no half state to report -- a
                            // room either has one a quorum signed or has none -- and
                            // the shared key entry further down is where somebody
                            // goes to make one.
                            chatRoomDetailUIState.groupKeyState?.let { groupKeyState ->
                                item {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = { isShowingGroupKeyState = true }
                                    ) {
                                        ListItem(
                                            leadingContent = {
                                                Icon(Icons.Default.Key, contentDescription = Decorative)
                                            },
                                            trailingContent = {
                                                Icon(
                                                    Icons.Default.ChevronRight,
                                                    contentDescription = Decorative
                                                )
                                            },
                                            headlineContent = {
                                                Text(text = stringResource(Res.string.signing_key))
                                            },
                                            supportingContent = {
                                                // The identity, not the group's root
                                                // key: this is the pubkey a reader
                                                // sees on everything the room signs,
                                                // and the room's own id. Truncated
                                                // here and whole in the sheet, where
                                                // it can be compared.
                                                Text(
                                                    text = groupKeyState.announcedBy
                                                        .take(16)
                                                        .inComparableGroups(),
                                                    fontFamily = FontFamily.Monospace,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.MiddleEllipsis
                                                )
                                            }
                                        )
                                    }
                                }
                            }

                            // Directly under the signing key, because between them
                            // they are what the room *is*: the identity it signs as
                            // and whose child it is. Absent on a group that is
                            // nobody's child, and absent -- rather than guessed --
                            // on a subgroup this device holds no key state for,
                            // since an unverified lineage is not one worth showing.
                            chatRoomDetailUIState.parentChatRoomId?.let { parentChatRoomId ->
                                item {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = {
                                            onNavigateToRoute.invoke(
                                                ChatRoomDetailRoute(
                                                    activeUserPublicKey = activeUserPublicKey,
                                                    chatRoomId = parentChatRoomId,
                                                    relayHint = relayHint
                                                )
                                            )
                                        }
                                    ) {
                                        ListItem(
                                            leadingContent = {
                                                Icon(
                                                    Icons.Default.AccountTree,
                                                    contentDescription = Decorative
                                                )
                                            },
                                            trailingContent = {
                                                Icon(
                                                    Icons.Default.ChevronRight,
                                                    contentDescription = Decorative
                                                )
                                            },
                                            headlineContent = {
                                                Text(text = stringResource(Res.string.parent_group))
                                            },
                                            supportingContent = {
                                                Column {
                                                    Text(
                                                        text = parentChatRoomId
                                                            .take(16)
                                                            .inComparableGroups(),
                                                        fontFamily = FontFamily.Monospace,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.MiddleEllipsis
                                                    )
                                                    // What the parent decided about
                                                    // this room's wallet at its birth.
                                                    // Nothing for one that decided
                                                    // nothing, as before policies.
                                                    walletPolicyLabel(chatRoomDetailUIState.walletPolicy, chatRoomDetailUIState.guardianDelay)?.let { Text(it) }
                                                }
                                            }
                                        )
                                    }
                                }
                            }

                            item {
                                chatRoomDetailUIState.localChatRoom.chatRoom.description?.let {
                                    Text(
                                        text = it,
                                        textAlign = TextAlign.Center,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                            }

                            item {
                                HorizontalDivider()
                            }

                            // The group's coins, signed by the room's key and read
                            // by nobody outside the room: the statement opening it
                            // never leaves the group. Shown to every member, open
                            // or not -- the screen behind it is where a member who
                            // can sign asks the group to open one.
                            //
                            // Marmot rooms only, and absent rather than empty in a
                            // NIP-17 one. A NIP-17 room's id is not a key it can
                            // sign as, so it has no wallet to open.
                            if (
                                GroupWalletRollout.ENABLED &&
                                chatRoomDetailUIState.localChatRoom.chatRoom.mlsGroupState != null
                            ) {
                                item {
                                    SignedWorkRow(
                                        icon = Icons.Default.AccountBalanceWallet,
                                        headline = chatRoomDetailUIState.localChatRoom.chatRoom.subject
                                            ?.let { stringResource(Res.string.s_onchain_wallet, it) }
                                            ?: stringResource(Res.string.group_wallet),
                                        supporting = chatRoomDetailUIState.groupWallet?.let { wallet ->
                                            stringResource(
                                                Res.string.open_on_s,
                                                GroupWalletStatementEvent.chainName(wallet.chain)
                                            )
                                        } ?: stringResource(Res.string.not_opened_yet),
                                        onOpen = {
                                            onNavigateToRoute.invoke(
                                                GroupWalletRoute(
                                                    activeUserPublicKey = activeUserPublicKey,
                                                    chatRoomId = chatRoomId,
                                                    relayHint = relayHint
                                                )
                                            )
                                        }
                                    )
                                }

                                // The group's Lightning wallet, which closes to the onchain
                                // wallet -- the row above this one, on the same network.
                                // Its key is made by the onchain wallet's signers and its
                                // channels close to that wallet, so until the group has
                                // opened one there is nothing behind this row: it stays,
                                // disabled, and says what it is waiting for.
                                if (GroupLightningRollout.ENABLED) item {
                                    val hasOnchainWallet = chatRoomDetailUIState.groupWallet != null
                                    SignedWorkRow(
                                        icon = Icons.Default.Bolt,
                                        headline = stringResource(Res.string.lightning_wallet),
                                        supporting = if (hasOnchainWallet) {
                                            GroupWalletStatementEvent.chainName(GroupLightningRollout.NETWORK)
                                        } else {
                                            stringResource(Res.string.lightning_needs_an_onchain_wallet)
                                        },
                                        enabled = hasOnchainWallet,
                                        onOpen = {
                                            onNavigateToRoute.invoke(
                                                GroupLightningWalletRoute(
                                                    activeUserPublicKey = activeUserPublicKey,
                                                    chatRoomId = chatRoomId
                                                )
                                            )
                                        }
                                    )
                                }

                                item {
                                    HorizontalDivider()
                                }
                            }

                            item {
                                Text(
                                    text = stringResource(Res.string.subgroups),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }

                            if (chatRoomDetailUIState.subgroups.isEmpty()) {
                                item {
                                    Text(stringResource(Res.string.no_subgroups_have_been_made_by_this_group))
                                }
                            } else {
                                items(
                                    items = chatRoomDetailUIState.subgroups,
                                    key = { subgroup -> subgroup.chatRoomId }
                                ) { subgroup ->
                                    SubgroupCard(
                                        subgroup = subgroup,
                                        onOpen = {
                                            // Into the child where this device has
                                            // it; at the certificate where it does
                                            // not, since that is the whole of what
                                            // is known and it is checkable.
                                            onNavigateToRoute.invoke(
                                                subgroup.room?.let {
                                                    ChatRoomDetailRoute(
                                                        activeUserPublicKey = activeUserPublicKey,
                                                        chatRoomId = it.chatRoom.id,
                                                        relayHint = relayHint
                                                    )
                                                } ?: NostrEventDetailRoute(
                                                    activeUserPublicKey = activeUserPublicKey,
                                                    nostrEventId = subgroup.certificate.id
                                                )
                                            )
                                        }
                                    )
                                }
                            }

                            // Offered only where it can work: this device holds a
                            // share of the group's key, so it can open the
                            // certificate session, and the room is a Marmot one, so
                            // there is a group with admins to be a parent. Hidden
                            // rather than disabled, the way the shared key entry is.
                            if (chatRoomDetailUIState.canAddSubgroup) {
                                item {
                                    TextButton(
                                        onClick = {
                                            onNavigateToRoute.invoke(
                                                SelectSubgroupAdminsRoute(
                                                    activeUserPublicKey = activeUserPublicKey,
                                                    parentChatRoomId = chatRoomId,
                                                    relayHint = relayHint
                                                )
                                            )
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.AccountTree,
                                            contentDescription = Decorative
                                        )

                                        Spacer(
                                            modifier = Modifier.width(MaterialTheme.spacing.space125)
                                        )

                                        Text(stringResource(Res.string.add_subgroup))
                                    }
                                }
                            }

                            // A member's wallet is a person, not a room, so it is its
                            // own section and its row opens the person -- there is no
                            // chat to open. docs/member-guarded-wallets.md, Phase 11.
                            if (chatRoomDetailUIState.memberWallets.isNotEmpty() || chatRoomDetailUIState.canAskForMemberWallet || chatRoomDetailUIState.showsMemberWalletPolicy) {
                                item { HorizontalDivider() }
                                item {
                                    Text(
                                        text = stringResource(Res.string.members_guarded_wallets),
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                                // Whether the group guards members' wallets at all, and at
                                // what delay -- first, because every row under it is a
                                // wallet it agreed to under one. docs/member-wallet-delay.md.
                                if (chatRoomDetailUIState.showsMemberWalletPolicy) {
                                    item {
                                        MemberWalletPolicyRow(
                                            policy = chatRoomDetailUIState.memberWalletPolicy,
                                            onChange = if (chatRoomDetailUIState.canSponsor) {
                                                {
                                                    onNavigateToRoute.invoke(
                                                        MemberWalletPolicyRoute(activeUserPublicKey = activeUserPublicKey, chatRoomId = chatRoomId)
                                                    )
                                                }
                                            } else null,
                                        )
                                    }
                                }
                                items(
                                    items = chatRoomDetailUIState.memberWallets,
                                    key = { "member-wallet-" + it.walletId }
                                ) { wallet ->
                                    MemberWalletRow(
                                        wallet = wallet,
                                        isPending = chatRoomDetailViewModel.isActionPending.value,
                                        onOpen = {
                                            onNavigateToRoute.invoke(
                                                ProfilePreviewRoute(activeUserPublicKey = activeUserPublicKey, profilePublicKey = wallet.ownerPublicKey)
                                            )
                                        },
                                        onPutToGroup = if (!wallet.isCertified && chatRoomDetailUIState.canSponsor) {
                                            {
                                                chatRoomDetailViewModel.putToGroup(
                                                    request = wallet.request,
                                                    onSuccess = { sessionId ->
                                                        onNavigateToRoute.invoke(
                                                            FrostSigningRoute(activeUserPublicKey = activeUserPublicKey, chatRoomId = chatRoomId, sessionId = sessionId)
                                                        )
                                                    },
                                                    onFailure = { notify(couldNotPutToGroup) },
                                                )
                                            }
                                        } else null,
                                    )
                                }
                                if (chatRoomDetailUIState.canAskForMemberWallet) {
                                    item {
                                        TextButton(
                                            onClick = {
                                                onNavigateToRoute.invoke(NewMemberWalletRoute(activeUserPublicKey = activeUserPublicKey, chatRoomId = chatRoomId))
                                            }
                                        ) {
                                            Icon(Icons.Default.AccountBalanceWallet, contentDescription = Decorative)
                                            Spacer(modifier = Modifier.width(MaterialTheme.spacing.space125))
                                            Text(stringResource(Res.string.ask_this_group_to_guard_a_wallet_for_you))
                                        }
                                    }
                                }
                            }

                            // Where the group pays this profile, above the members it
                            // will pay. docs/group-payout-destination.md, Phase 4.
                            chatRoomDetailUIState.payouts?.let { payouts ->
                                item { HorizontalDivider() }
                                item {
                                    Text(
                                        text = stringResource(Res.string.payouts),
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                                item {
                                    PayoutDestinationRow(
                                        standing = payouts,
                                        onChange = if (payouts.canSet) {
                                            {
                                                onNavigateToRoute.invoke(
                                                    GroupPayoutDestinationRoute(activeUserPublicKey = activeUserPublicKey, chatRoomId = chatRoomId)
                                                )
                                            }
                                        } else null,
                                    )
                                }
                            }

                            item {
                                HorizontalDivider()
                            }

                            item {
                                Text(
                                    text = stringResource(Res.string.members_2),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }

                            // Offering a ceremony to a room is offering it a key of
                            // its own, and only a NIP-17 room is in a position to
                            // want one: a Marmot room's id is derived from a key it
                            // already has, so a second ceremony there would produce
                            // a key the room could never answer to.
                            //
                            // A Marmot room does host ceremonies -- its subgroups'
                            // -- and those are reached from the transcript line that
                            // names the ceremony they are about, never from here,
                            // which has no way to say which.
                            //
                            // To the creation screen, which hands over to the group's
                            // ceremony if it already has one: the ceremony screen only
                            // shows a ceremony it is named, and this button has none
                            // to name. See docs/ceremony-creation.md.
                            if (chatRoomDetailUIState.localChatRoom.chatRoom.mlsGroupState == null) {
                                item {
                                    TextButton(
                                        onClick = {
                                            onNavigateToRoute.invoke(
                                                CreateSharedKeyRoute(
                                                    activeUserPublicKey = activeUserPublicKey,
                                                    chatRoomId = chatRoomId
                                                )
                                            )
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.Key,
                                            contentDescription = "Shared key"
                                        )

                                        Spacer(
                                            modifier = Modifier.width(MaterialTheme.spacing.space125)
                                        )

                                        Text(stringResource(Res.string.shared_key))
                                    }
                                }
                            }

                            // Every room, not only the one that holds the key. Most
                            // of what a group signs it signs in its Marmot room --
                            // its subgroups' certificates and key states included --
                            // so that is the last place this should be missing from.
                            //
                            // The transcript carries a proposal past as it happens;
                            // this is where a member goes to find one that has scrolled
                            // away, or to see what the group has signed.
                            item {
                                TextButton(
                                    onClick = {
                                        onNavigateToRoute.invoke(
                                            ProposalListRoute(
                                                activeUserPublicKey = activeUserPublicKey,
                                                chatRoomId = chatRoomId
                                            )
                                        )
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Draw,
                                        contentDescription = "Proposals"
                                    )

                                    Spacer(
                                        modifier = Modifier.width(MaterialTheme.spacing.space125)
                                    )

                                    Text(stringResource(Res.string.proposals))
                                }
                            }

                            item {
                                TextButton(
                                    onClick = {
                                        // Navigate to the invite user screen...
                                        onNavigateToRoute.invoke(
                                            SearchMemberToAddToChatRoomRoute(
                                                chatRoomId = chatRoomId,
                                                activeUserPublicKey = activeUserPublicKey,
                                                relayHint = relayHint
                                            )
                                        )
                                    }
                                ) {

                                    Icon(
                                        Icons.Default.PersonAdd,
                                        contentDescription = "Invite new member"
                                    )

                                    Spacer(
                                        modifier = Modifier.width(MaterialTheme.spacing.space125)
                                    )

                                    Text(stringResource(Res.string.invite_new_member))
                                }
                            }

                            items(
                                items = chatRoomDetailUIState.localChatRoom.localParticipants,
                                key = { localParticipant -> localParticipant.participant.participantPublicKey}
                            ) { localParticipant ->

                                // The preview, not the profile detail: the detail is
                                // keyed by a kind 0's event id, and a member whose
                                // profile has not arrived yet -- the "LOADING..." row
                                // the sync exists for -- has none. The preview is
                                // keyed by the public key, which is the one thing
                                // every row here has, and it shows the cached
                                // profile at once while the relays refresh it.
                                Card(
                                    onClick = {
                                        onNavigateToRoute.invoke(
                                            ProfilePreviewRoute(
                                                activeUserPublicKey = activeUserPublicKey,
                                                profilePublicKey = localParticipant.participant.participantPublicKey
                                            )
                                        )
                                    }
                                ) {
                                    ListItem(
                                        leadingContent = {
                                            ProfileAvatar(
                                                publicKey = localParticipant.participant.participantPublicKey,
                                                profile = localParticipant.profile
                                            )
                                        },
                                        trailingContent = {
                                            val memberPublicKey = localParticipant.participant.participantPublicKey
                                            if (chatRoomDetailUIState.canGovern && memberPublicKey != activeUserPublicKey) {
                                                // An admin's removal of a member: a commit through the
                                                // publish gate, applied when a relay accepts it, which is
                                                // why the row stays until then. See docs/marmot-v2.md, Phase 7.
                                                IconButton(
                                                    onClick = {
                                                        chatRoomDetailViewModel.removeMember(
                                                            localChatRoom = chatRoomDetailUIState.localChatRoom,
                                                            memberPublicKey = memberPublicKey,
                                                        ) { failure ->
                                                            // They keep a share of the Lightning wallet's key and its seed: say so,
                                                            // since only a new wallet takes it from them (Phase 14).
                                                            val holdsShare = memberPublicKey.lowercase() in chatRoomDetailUIState.lightningParticipants
                                                            notify(failure ?: if (holdsShare) removalLeavesLightningShare else removalRequested)
                                                        }
                                                    }
                                                ) {
                                                    Icon(
                                                        Icons.Default.PersonRemove,
                                                        contentDescription = stringResource(Res.string.remove_from_group)
                                                    )
                                                }
                                            } else {
                                                Icon(
                                                    Icons.Default.ChevronRight,
                                                    contentDescription = "View profile"
                                                )
                                            }
                                        },
                                        headlineContent = {
                                            Text(
                                                text = localParticipant.profile?.humanReadableNameOrPubkey() ?: localParticipant.participant.participantPublicKey
                                            )
                                        },
                                        supportingContent = {
                                            val memberPublicKey = localParticipant.participant.participantPublicKey
                                            Column {
                                                localParticipant.profile?.about?.let {
                                                    Text(
                                                        text = it
                                                    )
                                                }
                                                // Where the group pays this member: theirs to set on
                                                // their own row, everybody else's to read and copy.
                                                // docs/group-payout-destination.md, Phase 5.
                                                chatRoomDetailUIState.payouts?.let { payouts ->
                                                    MemberPayoutLine(
                                                        destination = chatRoomDetailUIState.payoutDestinations[memberPublicKey.lowercase()],
                                                        isMe = memberPublicKey.equals(activeUserPublicKey, ignoreCase = true),
                                                        onOpen = { showingPayoutOf = memberPublicKey.lowercase() },
                                                        onSetMine = if (payouts.canSet) {
                                                            {
                                                                onNavigateToRoute.invoke(
                                                                    GroupPayoutDestinationRoute(activeUserPublicKey = activeUserPublicKey, chatRoomId = chatRoomId)
                                                                )
                                                            }
                                                        } else null,
                                                    )
                                                }
                                            }
                                        }

                                    )
                                }

                            }


                            item {
                                HorizontalDivider()
                            }

                            if (chatRoomDetailUIState.isDisbanded) {
                                item {
                                    Text(
                                        text = stringResource(Res.string.group_disbanded),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(
                                            horizontal = MaterialTheme.spacing.containerPadding,
                                            vertical = MaterialTheme.spacing.compactPadding,
                                        )
                                    )
                                }
                            }

                            if (chatRoomDetailUIState.canGovern) {
                                item {
                                    TextButton(
                                        colors = ButtonDefaults.textButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error
                                        ),
                                        onClick = {
                                            chatRoomDetailViewModel.disbandGroup(
                                                localChatRoom = chatRoomDetailUIState.localChatRoom,
                                                onPopBackToRoute = onPopBackToRoute,
                                                onFailed = { failure -> notify(failure ?: "Couldn't disband the group") },
                                            )
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = stringResource(Res.string.disband_group)
                                        )

                                        Spacer(
                                            modifier = Modifier.width(MaterialTheme.spacing.space125)
                                        )

                                        Text(stringResource(Res.string.disband_group))
                                    }

                                    Text(
                                        text = stringResource(Res.string.disband_group_explainer),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(
                                            start = MaterialTheme.spacing.space600,
                                            end = MaterialTheme.spacing.containerPadding,
                                            bottom = MaterialTheme.spacing.compactPadding,
                                        )
                                    )
                                }
                            }

                            item {

                                TextButton(
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.secondary
                                    ),
                                    // Disabled after we leave, and after the group ends.
                                    enabled = chatRoomDetailUIState.localChatRoom.chatRoom.leftGroupAt == null && !chatRoomDetailUIState.isDisbanded,
                                    onClick = {
                                        chatRoomDetailViewModel.leaveGroup(
                                            localChatRoom = chatRoomDetailUIState.localChatRoom,
                                            onPopBackToRoute = onPopBackToRoute
                                        )
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Unsubscribe,
                                        contentDescription = "Leave group"
                                    )

                                    Spacer(
                                        modifier = Modifier.width(MaterialTheme.spacing.space125)
                                    )

                                    Text(stringResource(Res.string.leave_group))
                                }

                                // What it actually does: sets leftGroupAt and posts a line to
                                // the room. Said here because the button fires immediately --
                                // there is no confirmation step to say it in.
                                Text(
                                    text = "Posts a line to the room saying you left, and " +
                                        "lets you delete it from this device afterwards.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(
                                        start = MaterialTheme.spacing.space600,
                                        end = MaterialTheme.spacing.containerPadding,
                                        bottom = MaterialTheme.spacing.compactPadding,
                                    )
                                )
                            }

                            item {
                                TextButton(
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    ),
                                    enabled = chatRoomDetailUIState.localChatRoom.chatRoom.leftGroupAt != null,
                                    onClick = {
                                        chatRoomDetailViewModel.softDeleteGroup(
                                            localChatRoom = chatRoomDetailUIState.localChatRoom,
                                            onPopBackToRoute = onPopBackToRoute
                                        )
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.DeleteForever,
                                        contentDescription = "Delete group"
                                    )

                                    Spacer(
                                        modifier = Modifier.width(MaterialTheme.spacing.space125)
                                    )

                                    Text(stringResource(Res.string.delete_group))
                                }

                                // A soft delete: it sets deletedAt on the local row. Saying
                                // "delete" without saying where would leave somebody believing
                                // the messages are gone from the relays and from the other
                                // members, which is the opposite of true.
                                Text(
                                    text = "Removes the room from this device. The messages " +
                                        "stay on the relays and with the other members.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(
                                        start = MaterialTheme.spacing.space600,
                                        end = MaterialTheme.spacing.containerPadding,
                                        bottom = MaterialTheme.spacing.compactPadding,
                                    )
                                )
                            }

                            // Only MLS rooms have group events to read again. A NIP-17
                            // room's messages are gift wraps, which carry no ordering
                            // for anything to go wrong with.
                            if (chatRoomDetailUIState.localChatRoom.chatRoom.mlsGroupState != null) {
                                item {
                                    HorizontalDivider()
                                }

                                item {
                                    ReindexMarmotGroupEventsButton(
                                        reindexState = chatRoomDetailViewModel.reindexState,
                                        onReindex = chatRoomDetailViewModel::reindexMarmotGroupEvents
                                    )
                                }
                            }
                        }

                        showingPayoutOf?.let { member ->
                            chatRoomDetailUIState.payoutDestinations[member]?.let { destination ->
                                val name = chatRoomDetailUIState.localChatRoom.localParticipants
                                    .firstOrNull { it.participant.participantPublicKey.equals(member, ignoreCase = true) }
                                    ?.profile?.humanReadableNameOrPubkey() ?: member.take(8)
                                var usage by remember(destination.event.id) { mutableStateOf<UnusedAddressCheck.Usage?>(null) }
                                LaunchedEffect(destination.event.id) {
                                    chatRoomDetailViewModel.usageOf(destination) { usage = it }
                                }
                                ModalBottomSheet(onDismiss = { showingPayoutOf = null }) {
                                    PayoutDestinationSheetContent(name = name, destination = destination, usage = usage)
                                }
                            }
                        }

                        if (isShowingGroupKeyState) {
                            chatRoomDetailUIState.groupKeyState?.let { groupKeyState ->
                                GroupKeyStateSheet(
                                    groupKeyState = groupKeyState,
                                    signedEvent = chatRoomDetailUIState.signedGroupKeyStateEvent,
                                    onDismiss = { isShowingGroupKeyState = false }
                                )
                            }
                        }
                    }
                }
            }
            ChatRoomDetailUIState.Loading -> {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(
                        MaterialTheme.spacing.space250
                    ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space250)
                ) {

                    Spacer(
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = stringResource(Res.string.direct_message_detail),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = stringResource(Res.string.direct_message_functionality_will_be_here),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )

                    LoadingDataIndicator(
                        fillScreen = false
                    )

                    Spacer(
                        modifier = Modifier.weight(2f)
                    )
                }
            }
        }
    }

    LaunchedEffect(true) {
        if (initialChatRoomDetailUIState == ChatRoomDetailUIState.Loading) {
            chatRoomDetailViewModel.initiateChatRoomDetail()
        }
    }
}

/**
 * What the group signed to say what this room signs with, and a way to take it
 * somewhere else.
 *
 * The fields first, because they are what a member came to read: the identity
 * the room signs as, the key behind it, the path between them, and which
 * ceremony made it. Then the event itself, which is the only part that can be
 * *checked* -- a signature by that same identity over those same fields -- and
 * so the only part worth copying.
 *
 * Whole values throughout, monospaced and wrapping. These exist to be compared
 * character by character, against another member's screen or against what a
 * relay reports, and a truncated key cannot be compared.
 *
 * [signedEvent] can be missing while [groupKeyState] is not -- a device that
 * kept the reading and lost the statement -- and the sheet says so rather than
 * showing the fields as though they were signed. It never happens the other way
 * round: a state is only ever written from an event that passed both checks.
 */
@Composable
private fun GroupKeyStateSheet(
    groupKeyState: GroupKeyState,
    signedEvent: GroupSignedEvent?,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismiss = onDismiss) {
        GroupKeyStateSheetContent(groupKeyState = groupKeyState, signedEvent = signedEvent)
    }
}

/**
 * The sheet's body, apart from the sheet.
 *
 * Split out so it can be rendered and measured on its own -- a bottom sheet is a
 * popup with its own window, which a layout test cannot reach into. See
 * `GroupKeyStateSheetLayoutJvmTest`, which is checking the one thing that is not
 * obvious from reading this: that a 500-character line of JSON with almost no
 * spaces in it wraps inside the sheet rather than running off the side of it.
 */
@Composable
internal fun GroupKeyStateSheetContent(
    groupKeyState: GroupKeyState,
    signedEvent: GroupSignedEvent?
) {
    val clipboardManager = LocalClipboardManager.current

    // Read out here rather than in the click handler: both of these are
    // composable, and an onClick lambda is not.
    val notify = rememberNotifier(rememberCoroutineScope())
    val copied = stringResource(Res.string.copied_the_signed_event)

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(Res.string.what_the_group_signed),
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.relatedGap))

        Text(
            text = stringResource(Res.string.the_key_this_room_signs_as),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.sectionGap))

        // The identity every reader will see on this room's signed events, which
        // is also the room's own id -- see SharedKeyDerivation.marmotGroupId,
        // where those are one value.
        GroupKeyStateField(
            label = stringResource(Res.string.this_room_signs_as),
            value = groupKeyState.announcedBy.inComparableGroups()
        )

        GroupKeyStateField(
            label = stringResource(Res.string.shared_key),
            value = groupKeyState.thresholdPublicKey.inComparableGroups()
        )

        GroupKeyStateField(
            label = stringResource(Res.string.derivation_path),
            value = groupKeyState.derivationPath
        )

        GroupKeyStateField(
            label = stringResource(Res.string.key_ceremony),
            value = groupKeyState.dkgSessionId.inComparableGroups()
        )

        GroupKeyStateField(
            label = stringResource(Res.string.agreed_on),
            value = groupKeyState.announcedAt.toFormattedTimeAndDateString(),
            isMonospaced = false
        )

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.sectionGap))

        Text(
            text = stringResource(Res.string.the_signed_event),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.relatedGap))

        if (signedEvent == null) {
            Text(
                text = stringResource(Res.string.this_device_does_not_hold_the_event),
                style = MaterialTheme.typography.bodySmall
            )
            return@Column
        }

        // As the group signed it, byte for byte. What is copied is exactly what
        // is shown, because the point of copying it is to hand somebody
        // something they can verify -- and a prettier rendering would be a
        // different string to the one whose id was hashed.
        val json = signedEvent.toEvent().toJson()

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                text = json,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(MaterialTheme.spacing.containerPadding)
            )
        }

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.itemGap))

        FilledTonalButton(
            onClick = {
                clipboardManager.setText(AnnotatedString(json))
                notify(copied)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.ContentCopy, contentDescription = Decorative)
            Spacer(modifier = Modifier.width(MaterialTheme.spacing.space100))
            Text(text = stringResource(Res.string.copy_the_signed_event))
        }
    }
}

/**
 * One row for a kind of statement the group has signed: a line summarising it,
 * and the way to the screen where it is read in full.
 *
 * The same shape as the signing key and parent group rows above it -- a card
 * with a leading icon and a trailing chevron -- because it is the same kind of
 * thing: a fact about the group, opened by tapping. The icons are decorative
 * because the headline beside each says what the row is, and the chevron says
 * only what every card on this screen already says by being one.
 */
@Composable
private fun SignedWorkRow(
    icon: ImageVector,
    headline: String,
    supporting: String,
    enabled: Boolean = true,
    onOpen: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onOpen, enabled = enabled) {
        ListItem(
            // A disabled row dims its headline and icon to M3's 38%, but not its
            // supporting text, which is where it says why it is disabled.
            colors = if (enabled) ListItemDefaults.colors() else ListItemDefaults.colors(
                headlineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                leadingIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            ),
            leadingContent = {
                Icon(icon, contentDescription = Decorative)
            },
            trailingContent = if (enabled) {
                { Icon(Icons.Default.ChevronRight, contentDescription = Decorative) }
            } else null,
            headlineContent = {
                Text(text = headline)
            },
            supportingContent = {
                Text(
                    text = supporting,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        )
    }
}

/**
 * One member's guarded wallet, as the guarding group's page lists it: the
 * person, by the live name of their profile, and the label they gave it. A
 * request no signer has put to the group yet says so, and offers the one thing
 * a signer can do about it.
 */
@Composable
private fun MemberWalletRow(
    wallet: MemberWalletManager.GroupMemberWallet,
    isPending: Boolean,
    onOpen: () -> Unit,
    onPutToGroup: (() -> Unit)?,
) {
    val name = stringResource(Res.string.at_s, wallet.ownerName ?: wallet.ownerPublicKey.take(8))
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen).minimumInteractiveComponentSize(),
        leadingContent = { Icon(Icons.Default.AccountBalanceWallet, contentDescription = Decorative) },
        headlineContent = {
            Text(
                text = wallet.label?.let { stringResource(Res.string.s_dot_s, name, it) } ?: name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = if (wallet.isCertified) null else {
            { Text(stringResource(Res.string.asked_by_s_not_yet_signed, name)) }
        },
        trailingContent = onPutToGroup?.let { put ->
            { TextButton(onClick = put, enabled = !isPending) { Text(stringResource(Res.string.put_to_the_group)) } }
        },
    )
}

/**
 * One subgroup, as the parent's record and this device's rooms together have it.
 *
 * **The title comes from the room where there is one, and only otherwise from the
 * certificate.** The certificate's name and `p` tags are what the parent approved
 * at founding, frozen: the child may since have been renamed or have gained
 * members, and none of that reaches a signature already made. So where this
 * device holds the room, the room is the live answer; where it does not, the
 * certificate is all there is and the supporting line says which of the two
 * absences this is.
 */
@Composable
private fun SubgroupCard(
    subgroup: SubgroupManager.Subgroup,
    onOpen: () -> Unit
) {
    Card(onClick = onOpen) {
        ListItem(
            leadingContent = {
                Icon(Icons.Default.AccountTree, contentDescription = Decorative)
            },
            trailingContent = {
                Icon(Icons.Default.ChevronRight, contentDescription = Decorative)
            },
            headlineContent = {
                Text(
                    text = subgroup.room?.chatRoom?.subject
                        ?: subgroup.name
                        ?: stringResource(Res.string.a_subgroup)
                )
            },
            supportingContent = {
                Column {
                    Text(
                        text = when {
                            subgroup.isJoined -> stringResource(
                                Res.string.run_by_members,
                                subgroup.adminPublicKeys.size.toString()
                            )
                            // The room does not exist anywhere yet: the parent has
                            // certified it and nobody has created it. Between steps 2
                            // and 4 of docs/subgroups.md this is every member's view.
                            subgroup.keyState == null -> stringResource(Res.string.certified_not_yet_created)
                            else -> stringResource(Res.string.created_you_are_not_a_member)
                        }
                    )
                    // What the parent decided about its wallet, from the certificate:
                    // founding history, like the name the row falls back to.
                    walletPolicyLabel(subgroup.walletPolicy, subgroup.guardianDelay)?.let { Text(it) }
                }
            }
        )
    }
}

/**
 * A member row's line about where the group pays them: the active profile's own leads to
 * setting it, anybody else's Lightning address opens their sheet, and a member who has not
 * said is told so. `docs/group-payout-destination.md`, Phase 5.
 */
@Composable
private fun MemberPayoutLine(
    destination: GroupPayoutDestination?,
    isMe: Boolean,
    onOpen: () -> Unit,
    onSetMine: (() -> Unit)?,
) {
    when {
        isMe && onSetMine != null -> TextButton(onClick = onSetMine) {
            Icon(Icons.Default.Payments, contentDescription = Decorative)
            Spacer(modifier = Modifier.width(MaterialTheme.spacing.space100))
            Text(stringResource(Res.string.where_this_group_pays_you))
        }
        destination != null -> TextButton(onClick = onOpen) {
            Icon(Icons.Default.Payments, contentDescription = Decorative)
            Spacer(modifier = Modifier.width(MaterialTheme.spacing.space100))
            Text(destination.lightningAddress.toString())
        }
        else -> Text(
            text = stringResource(Res.string.hasn_t_said_where_to_be_paid),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One member's payout destination, whole: the Lightning address and the range it said it
 * takes, the onchain address and whether it has been paid since -- asked when the sheet opens,
 * [usage] null until it answers -- and when it was set. Copy only: an address paid before is
 * never offered as a QR code. Split out from the sheet so it can be rendered on its own.
 */
@Composable
internal fun PayoutDestinationSheetContent(
    name: String,
    destination: GroupPayoutDestination,
    usage: UnusedAddressCheck.Usage?,
) {
    val clipboardManager = LocalClipboardManager.current
    val notify = rememberNotifier(rememberCoroutineScope())
    val copied = stringResource(Res.string.copied)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap),
    ) {
        Text(text = stringResource(Res.string.where_s_is_paid, name), style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(MaterialTheme.spacing.relatedGap))

        PayoutAddressField(
            label = stringResource(Res.string.lightning_address),
            value = destination.lightningAddress.toString(),
            copyDescription = stringResource(Res.string.copy_the_lightning_address),
            onCopy = {
                clipboardManager.setText(AnnotatedString(destination.lightningAddress.toString()))
                notify(copied)
            },
        )
        destination.sendable?.let {
            Text(text = sendableText(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        destination.onchainAddress?.let { onchain ->
            Spacer(modifier = Modifier.height(MaterialTheme.spacing.relatedGap))
            PayoutAddressField(
                label = stringResource(Res.string.onchain_address),
                value = onchain,
                copyDescription = stringResource(Res.string.copy_the_onchain_address),
                onCopy = {
                    clipboardManager.setText(AnnotatedString(onchain))
                    notify(copied)
                },
            )
            Text(
                text = stringResource(
                    when (usage) {
                        null -> Res.string.checking
                        UnusedAddressCheck.Usage.Unused -> Res.string.unused
                        UnusedAddressCheck.Usage.Used, UnusedAddressCheck.Usage.TheGroups -> Res.string.paid_before_ask_for_a_new_one
                        UnusedAddressCheck.Usage.Unknown -> Res.string.couldn_t_check
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (usage == UnusedAddressCheck.Usage.Used) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.relatedGap))
        GroupKeyStateField(
            label = stringResource(Res.string.set_on),
            value = Instant.fromEpochSeconds(destination.createdAt).toFormattedTimeAndDateString(),
            isMonospaced = false,
        )
    }
}

/** A labelled address with a copy button beside it. */
@Composable
private fun PayoutAddressField(label: String, value: String, copyDescription: String, onCopy: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        }
        IconButton(onClick = onCopy) {
            Icon(Icons.Default.ContentCopy, contentDescription = copyDescription)
        }
    }
}

/**
 * *Where this group pays you*, and the Lightning address it pays -- or *Not set* -- with
 * *Change* or *Set* for a profile that can sign one. `docs/group-payout-destination.md`.
 */
@Composable
private fun PayoutDestinationRow(standing: PayoutDestinationRepository.Standing, onChange: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)) {
            Text(text = stringResource(Res.string.where_this_group_pays_you), style = MaterialTheme.typography.titleMedium)
            Text(
                text = standing.current?.lightningAddress?.toString() ?: stringResource(Res.string.not_set),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        onChange?.let { change ->
            TextButton(onClick = change) {
                Text(stringResource(if (standing.current != null) Res.string.change else Res.string.set))
            }
        }
    }
}

/**
 * The group's own stand on its members' wallets, with *Change* -- or *Guard
 * members' wallets* when it has none open -- for a device that can put a change
 * to the group. `docs/member-wallet-delay.md`, Phase 6.
 */
@Composable
private fun MemberWalletPolicyRow(policy: MemberWalletPolicyEvent.Policy?, onChange: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
    ) {
        Box(modifier = Modifier.weight(1f)) { MemberWalletPolicyState(policy) }
        onChange?.let { change ->
            TextButton(onClick = change) {
                Text(
                    stringResource(
                        if (policy is MemberWalletPolicyEvent.Policy.Open) Res.string.change else Res.string.guard_members_wallets
                    )
                )
            }
        }
    }
}

/**
 * *Guarded wallet, after 144 blocks, about 1 day* or *Unguarded wallet* for a
 * policy a parent decided, and null for one it left to the subgroup --
 * `docs/subgroup-wallet-policy.md`. The delay is the parent's own leaf's,
 * `docs/guardian-delay.md`.
 */
@Composable
private fun walletPolicyLabel(policy: WalletPolicyReading?, guardianDelay: Long?): String? = when (policy) {
    WalletPolicyReading.Decided(SubgroupWalletPolicy.GUARDED) -> guardianDelay
        ?.let { stringResource(Res.string.guarded_wallet_after, delayText(it)) }
        ?: stringResource(Res.string.guarded_wallet)
    WalletPolicyReading.Decided(SubgroupWalletPolicy.UNGUARDED) -> stringResource(Res.string.unguarded_wallet)
    else -> null
}

/** One labelled value of a key state, whole and comparable. */
@Composable
private fun GroupKeyStateField(
    label: String,
    value: String,
    isMonospaced: Boolean = true
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Text(
        text = value,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = if (isMonospaced) FontFamily.Monospace else null,
        // Stated rather than inherited: the label above is deliberately quieter,
        // and the value is the thing that was come for.
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(modifier = Modifier.height(MaterialTheme.spacing.itemGap))
}

/**
 * Reads the room's stored marmot group events again.
 *
 * Sits at the very bottom, below leaving and deleting, because it is a repair
 * rather than something anyone should need in the ordinary course of using the
 * group. It says what it found afterwards -- a replay that recovered nothing
 * looks identical to one that was never pressed, and the difference between
 * "nothing was broken" and "nothing could be fixed" is worth telling.
 */
@Composable
private fun ReindexMarmotGroupEventsButton(
    reindexState: ChatRoomDetailViewModel.ReindexState,
    onReindex: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space50)
    ) {
        TextButton(
            enabled = reindexState !is ChatRoomDetailViewModel.ReindexState.Running,
            onClick = onReindex
        ) {
            if (reindexState is ChatRoomDetailViewModel.ReindexState.Running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    Icons.Default.Autorenew,
                    contentDescription = "Reindex group events"
                )
            }

            Spacer(
                modifier = Modifier.width(MaterialTheme.spacing.space125)
            )

            Text(stringResource(Res.string.reindex_events))
        }

        when (reindexState) {
            is ChatRoomDetailViewModel.ReindexState.Done -> {
                val report = reindexState.report
                // What was published before this member joined is named rather
                // than counted as read. Their epoch keys were never on this
                // device, so "all read" would be a claim about messages nobody
                // here can open -- and a room that is mostly older than the
                // member is the ordinary case for anyone invited into one.
                val beforeJoining =
                    if (report.predatingMembership > 0) {
                        " · ${report.predatingMembership} from before you joined"
                    } else {
                        ""
                    }
                Text(
                    text = when {
                        report.isNoOp ->
                            "Nothing to reindex · ${report.stored - report.predatingMembership} " +
                                "event(s) all read$beforeJoining"
                        report.recovered > 0 && report.failed > 0 ->
                            stringResource(Res.string.recovered_of_still_unreadable, report.recovered, report.unresolved, report.failed, beforeJoining)
                        report.recovered > 0 ->
                            stringResource(Res.string.recovered_of_event_s, report.recovered, report.unresolved, beforeJoining)
                        else -> stringResource(Res.string.event_s_still_unreadable, report.failed, beforeJoining)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }

            is ChatRoomDetailViewModel.ReindexState.Failed -> {
                Text(
                    text = reindexState.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }

            else -> Unit
        }
    }
}

@ConformancePreviews
@Composable
private fun ChatRoomMessagingScreenPreview() {
    DedunTheme {
        Surface(
            modifier = Modifier.fillMaxSize()
        ) {
            ChatRoomDetailScreen(
                activeUserPublicKey = "",
                chatRoomId = "publicKey",
                relayHint = null,
                initialChatRoomDetailUIState = ChatRoomDetailUIState.Loaded(
                    localChatRoom = LocalChatRoom(
                        chatRoom = ChatRoom(
                            id = "",
                            userPublicKey = "",
                            subject = "Message title",
                            description = "Description of a chat room so that members now why they are here.",
                            initialGiftWrapPayloadId = "sdfaer",
                            // A Marmot room, so the signed-work block renders at
                            // all -- it is hidden in a NIP-17 one, which has no key
                            // to sign with.
                            mlsGroupState = "state"
                        ),
                        localParticipants = listOf(
                            LocalParticipant(
                                participant = Participant(
                                    id = 2,
                                    participantPublicKey = "gex",
                                    chatRoomId = "chatRoomId",
                                    relayHint = null
                                ),
                                profile = Profile(
                                    publicKey = "gex",
                                    displayName = "Gexie",
                                    nostrEventId = "nosrtEventId"
                                )
                            )
                        )
                    ),
                    // A room that has one, so the signing key row renders under
                    // all five conditions rather than only in a group that has
                    // held a ceremony. Not a real key: nothing here derives.
                    groupKeyState = GroupKeyState(
                        chatRoomId = "publicKey",
                        dkgSessionId = "c".repeat(64),
                        thresholdPublicKey = "02".padEnd(66, 'a'),
                        derivationPath = "m/9420/0/0",
                        announcedBy = "d".repeat(64),
                        announcedAt = Instant.fromEpochSeconds(1_700_000_000)
                    ),
                ),
                nostrRepository = NostrRepository.NO_OP_NOSTR_REPOSITORY,
                chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                onNavigateToRoute = {},
                onPopBackToRoute = {},
                onNavigateBack = {},
            )
        }
    }
}