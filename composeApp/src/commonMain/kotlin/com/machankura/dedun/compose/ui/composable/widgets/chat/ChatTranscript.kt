package com.machankura.dedun.compose.ui.composable.widgets.chat

import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AccountTree
import dedun.composeapp.generated.resources.open
import dedun.composeapp.generated.resources.the_group_has_its_key_finish_setting_it_up
import dedun.composeapp.generated.resources.check_the_combined_result
import dedun.composeapp.generated.resources.send_your_contribution_to_the_key
import dedun.composeapp.generated.resources.join_the_ceremony_by_publishing_your_key
import dedun.composeapp.generated.resources.the_subgroups_key_ceremony_needs_you
import dedun.composeapp.generated.resources.the_key_ceremony_needs_you
import dedun.composeapp.generated.resources.ceremonies_need_you
import com.machankura.dedun.compose.database.model.types.DkgApprovalStep
import com.machankura.dedun.compose.database.model.types.IcebergApprovalStep
import androidx.compose.material.icons.filled.Bolt
import dedun.composeapp.generated.resources.the_lightning_wallet_s_key_ceremony_needs_you
import dedun.composeapp.generated.resources.the_lightning_wallet_s_key_is_ready
import dedun.composeapp.generated.resources.join_the_ceremony
import dedun.composeapp.generated.resources.send_your_contributions
import dedun.composeapp.generated.resources.confirm_what_you_saw
import dedun.composeapp.generated.resources.open_a_lightning_wallet
import dedun.composeapp.generated.resources.open_the_shared_key_ceremony
import dedun.composeapp.generated.resources.open_the_lightning_wallet_s_key_ceremony
import dedun.composeapp.generated.resources.open_the_proposal
import dedun.composeapp.generated.resources.open_the_lightning_wallet
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.RequestQuote
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.PowerSettingsNew
import dedun.composeapp.generated.resources.you_approved_this
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pending
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatMessage
import com.machankura.dedun.compose.extensions.shortened
import com.machankura.dedun.compose.extensions.toFormattedTimeAndDateString
import com.machankura.dedun.compose.ui.composable.widgets.profile.ProfileColor
import com.machankura.dedun.compose.ui.view.model.ChatMessageListViewModel
import com.machankura.dedun.compose.ui.view.state.ChatMessageListUIState
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import dedun.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import dedun.composeapp.generated.resources.currently_no_messages_have_been_shared
import dedun.composeapp.generated.resources.loading
import dedun.composeapp.generated.resources.no_chat_message_relays_were_found_for_this
import dedun.composeapp.generated.resources.private_to_you
import dedun.composeapp.generated.resources.review
import dedun.composeapp.generated.resources.waiting_for_your_signature
import dedun.composeapp.generated.resources.you
import dedun.composeapp.generated.resources.private_to
import dedun.composeapp.generated.resources.proposals_are_waiting_for_your_signature
import dedun.composeapp.generated.resources.reply_privately_to
import dedun.composeapp.generated.resources.sent_a_private_message_to
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState

/**
 * A chat room's transcript: its messages, the notices between them, and the standing ask
 * at its foot.
 *
 * This was `ChatMessageListViewModel.RenderMessages`, 380 lines of layout inside a 1,100
 * line view model, which is where the only two `BoxWithConstraints` in the app ended up.
 * It moves here for the pane work: a transcript that has to render at 400dp as a whole
 * screen and at 900dp as the detail half of a two-pane layout is a layout decision, and
 * layout decisions that live in a view model cannot be composed twice or previewed once.
 *
 * The view model comes in as the first parameter rather than as a receiver. It is a
 * parameter so the reader can see, at every use, which state is the room's and which is
 * this composable's -- `openMessageActionsFor` read bare inside a class body says nothing
 * about where it is kept, and there are fourteen such reads in here.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChatTranscript(
    viewModel: ChatMessageListViewModel,
    onOpenSharedKey: (dkgSessionId: String) -> Unit,
    /** Opens the Lightning wallet key ceremony an [ChatMessage.ICEBERG_TYPES] line is about. */
    onOpenIcebergCeremony: (sessionId: String?) -> Unit = {},
    /** Opens the room's Lightning wallet, where a finished key is opened as a wallet. */
    onOpenLightningWallet: () -> Unit,
    /**
     * Opens the signing this line is about, by session id -- or the room's
     * whole list of proposals when the line predates [ChatMessage.frostSigningSessionId]
     * and cannot say which one it meant.
     */
    onOpenSigning: (sessionId: String?) -> Unit,
    /** Opens the room's proposals, all of them, whatever their state. */
    onOpenProposals: () -> Unit,
) {

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when (val chatRoomDetailMessageListUIState = viewModel.chatMessageListUIState) {
            ChatMessageListUIState.Error -> {
                ErrorState()
            }
            is ChatMessageListUIState.Loaded -> {
                Spacer(
                    modifier = Modifier.weight(1f)
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125)
                ) {

                    if (chatRoomDetailMessageListUIState.chatMessageList.isEmpty()) {
                        Spacer(
                            modifier = Modifier.height(MaterialTheme.spacing.space250)
                        )
                        Text(
                            modifier = Modifier.padding(MaterialTheme.spacing.space250),
                            text = stringResource(Res.string.currently_no_messages_have_been_shared),
                            textAlign = TextAlign.Center
                        )

                        Spacer(
                            modifier = Modifier.height(MaterialTheme.spacing.space250)
                        )
                    } else {

                        // Which request lines are still asking something of the
                        // reader. Read off the transcript rather than the session
                        // -- the rows are what a line rendered days later has --
                        // and both rules live on ChatMessage, where they can be
                        // stated once and tested.
                        val messages = chatRoomDetailMessageListUIState
                            .chatMessageList
                            .map { it.chatMessage }

                        val answeredRequests = ChatMessage.answeredRequests(messages)
                        val settledRequests = ChatMessage.settledRequests(messages)

                        // Read out here rather than inside the list, so the
                        // notice appearing and disappearing is a recomposition
                        // of this function and not of a lazy item that may not
                        // be composed at the time.
                        val awaitingYou = viewModel.proposalsAwaitingYou
                        val ceremoniesAwaitingYou = viewModel.ceremoniesAwaitingYou

                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.space50),
                            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125),
                            reverseLayout = true
                        ) {
                            // First item, and the layout is reversed, so this
                            // sits under the newest message and above the
                            // composer -- where the reader already is.
                            if (awaitingYou.isNotEmpty()) {
                                item {
                                    ProposalsAwaitingYouNotice(
                                        proposals = awaitingYou,
                                        onClick = onOpenProposals
                                    )
                                }
                            }

                            // Beside the signing notice and above it in the
                            // reversed layout, so a room owing both puts the
                            // ceremony nearest the composer. A ceremony is the
                            // thing everything else in the room is waiting on:
                            // until it finishes there is no key to sign anything
                            // with.
                            if (ceremoniesAwaitingYou.isNotEmpty()) {
                                item {
                                    CeremoniesAwaitingYouNotice(
                                        ceremonies = ceremoniesAwaitingYou,
                                        // Each ceremony has its own screen, and a
                                        // finished Lightning key is opened from the
                                        // wallet's.
                                        onClick = { ceremony ->
                                            when (ceremony.ask) {
                                                is ChatMessageListViewModel.Ask.SharedKeyStep,
                                                is ChatMessageListViewModel.Ask.RoomCreation ->
                                                    onOpenSharedKey(ceremony.sessionId)
                                                is ChatMessageListViewModel.Ask.LightningKeyStep ->
                                                    onOpenIcebergCeremony(ceremony.sessionId)
                                                ChatMessageListViewModel.Ask.LightningWalletOpening ->
                                                    onOpenLightningWallet()
                                            }
                                        }
                                    )
                                }
                            }

                            item {
                                if (viewModel.isReceiverChatMessageRelayListMissing.value) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Card(
                                            onClick = {
                                                // TODO: Open description for chatMessageRelayList
                                            },
                                            modifier = Modifier.fillMaxWidth(0.79f),
                                            colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                                            )
                                        ) {

                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.compactPadding),
                                                horizontalArrangement = Arrangement.Center,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Text(
                                                        text = stringResource(Res.string.no_chat_message_relays_were_found_for_this),
                                                        textAlign = TextAlign.Center,
                                                        style = MaterialTheme.typography.labelSmall
                                                    )
                                                }

                                                Icon(
                                                    Icons.Default.Info,
                                                    contentDescription = "Warning description"
                                                )
                                            }


                                        }
                                    }
                                }
                            }
                            items(
                                items = chatRoomDetailMessageListUIState.chatMessageList,
                                key = { it.chatMessage.id }
                            ) { localChatMessage ->
                                // Not somebody's words -- see ChatMessage.DKG_TYPES.
                                // A bubble would attribute "a shared key ceremony
                                // started" to the coordinator as if they had said it.
                                if (localChatMessage.chatMessage.messageType in ChatMessage.DKG_TYPES) {
                                    RitualNotice(
                                        localChatMessage = localChatMessage,
                                        isAnswered = localChatMessage.chatMessage.id in answeredRequests,
                                        isSettled = localChatMessage.chatMessage.id in settledRequests,
                                        // The line knows which ceremony it is
                                        // about; the room does not. Passing it is
                                        // what stops a subgroup's request opening
                                        // the group's own finished ceremony. A
                                        // line that names none opens nothing: the
                                        // ceremony screen shows the ceremony it is
                                        // named, and guessing one is how a
                                        // subgroup's got buried under another.
                                        onClick = localChatMessage.chatMessage.dkgSessionId?.let { dkgSessionId ->
                                            { onOpenSharedKey(dkgSessionId) }
                                        },
                                        opens = stringResource(Res.string.open_the_shared_key_ceremony),
                                    )
                                    return@items
                                }

                                // The Lightning wallet's key ceremony, the same kind of
                                // notice as the onchain key's, opening its own screen.
                                if (localChatMessage.chatMessage.messageType in ChatMessage.ICEBERG_TYPES) {
                                    RitualNotice(
                                        localChatMessage = localChatMessage,
                                        isAnswered = localChatMessage.chatMessage.id in answeredRequests,
                                        isSettled = localChatMessage.chatMessage.id in settledRequests,
                                        onClick = { onOpenIcebergCeremony(localChatMessage.chatMessage.dkgSessionId) },
                                        opens = stringResource(Res.string.open_the_lightning_wallet_s_key_ceremony),
                                    )
                                    return@items
                                }

                                // A private message this device cannot open. Everything
                                // about it is known except the one thing that matters,
                                // so it is a notice rather than an empty bubble --
                                // which would read as the sender having said nothing.
                                if (localChatMessage.chatMessage.messageType == ChatMessage.TYPE_DIRECT_MESSAGE &&
                                    localChatMessage.chatMessage.content.isBlank()
                                ) {
                                    PrivateMessageNotice(
                                        sender = viewModel.nameFor(localChatMessage.chatMessage.senderPublicKey),
                                        recipient = viewModel.nameFor(localChatMessage.chatMessage.directMessageRecipientPublicKey)
                                    )
                                    return@items
                                }

                                // Catching a member up is nobody's words either,
                                // and it leads nowhere: the work it delivered is
                                // in the artifact list, not behind this line.
                                // Passed as answered and settled because those
                                // are about requests and this asks nothing --
                                // which is what keeps it in the quiet tint.
                                if (localChatMessage.chatMessage.messageType in ChatMessage.CHRONICLE_TYPES) {
                                    RitualNotice(
                                        localChatMessage = localChatMessage,
                                        isAnswered = true,
                                        isSettled = true,
                                        onClick = null,
                                        opens = null,
                                    )
                                    return@items
                                }

                                // Inviting somebody is nobody's words either, and
                                // for a while it was not in the room at all: the
                                // line was written when the Welcome went out, which
                                // on the deferred path is a relay round trip away
                                // and may never happen. Passed as answered and
                                // settled for the same reason the chronicle's are
                                // -- these report rather than ask, so they stay in
                                // the quiet tint and offer nothing to review.
                                if (localChatMessage.chatMessage.messageType in ChatMessage.MEMBERSHIP_TYPES) {
                                    RitualNotice(
                                        localChatMessage = localChatMessage,
                                        isAnswered = true,
                                        isSettled = true,
                                        onClick = null,
                                        opens = null,
                                    )
                                    return@items
                                }

                                // A subgroup being certified is the group's own
                                // signature, not a member's words, so it gets the
                                // same treatment. Answered and settled because it
                                // reports rather than asks: by the time this line
                                // exists a quorum has already signed, and there is
                                // nothing left for a reader to do about it.
                                //
                                // It leads nowhere for now. What a reader wants
                                // from one is the subgroup, and the subgroups
                                // section of the group's detail screen is where
                                // that lives.
                                if (localChatMessage.chatMessage.messageType in ChatMessage.SUBGROUP_TYPES) {
                                    RitualNotice(
                                        localChatMessage = localChatMessage,
                                        isAnswered = true,
                                        isSettled = true,
                                        onClick = null,
                                        opens = null,
                                    )
                                    return@items
                                }

                                // What the Wavelength wallet's devices said.
                                if (localChatMessage.chatMessage.messageType in ChatMessage.WAVELENGTH_TYPES) {
                                    RitualNotice(
                                        localChatMessage = localChatMessage,
                                        isAnswered = true,
                                        isSettled = true,
                                        onClick = null,
                                        opens = null,
                                    )
                                    return@items
                                }

                                // What the Lightning wallet's devices told each other.
                                // Reports, not asks -- an invoice request is the
                                // operator's node's to answer -- so they stay quiet,
                                // and each leads to the wallet it is about.
                                if (localChatMessage.chatMessage.messageType in ChatMessage.LIGHTNING_TYPES) {
                                    RitualNotice(
                                        localChatMessage = localChatMessage,
                                        isAnswered = true,
                                        isSettled = true,
                                        onClick = onOpenLightningWallet,
                                        opens = stringResource(Res.string.open_the_lightning_wallet),
                                    )
                                    return@items
                                }

                                // Something the group signed. Same treatment and for
                                // the same reason: nobody said it. Answered and
                                // settled because it reports rather than asks -- a
                                // quorum has already signed by the time this line
                                // exists. It leads nowhere, except the Lightning
                                // wallet's, which lead to the wallet.
                                if (localChatMessage.chatMessage.messageType in ChatMessage.GROUP_IDENTITY_TYPES) {
                                    val isLightning = localChatMessage.chatMessage.messageType in ChatMessage.GROUP_LIGHTNING_SIGNED_TYPES
                                    RitualNotice(
                                        localChatMessage = localChatMessage,
                                        isAnswered = true,
                                        isSettled = true,
                                        onClick = if (isLightning) onOpenLightningWallet else null,
                                        opens = if (isLightning) stringResource(Res.string.open_the_lightning_wallet) else null,
                                    )
                                    return@items
                                }

                                // Signing lines are the same kind of thing and get
                                // the same treatment -- nobody said them either --
                                // but they lead somewhere else, because what a
                                // reader needs from one is the event being signed
                                // rather than the state of the key.
                                if (localChatMessage.chatMessage.messageType in ChatMessage.FROST_TYPES) {
                                    RitualNotice(
                                        localChatMessage = localChatMessage,
                                        isAnswered = localChatMessage.chatMessage.id in answeredRequests,
                                        isSettled = localChatMessage.chatMessage.id in settledRequests,
                                        onClick = {
                                            onOpenSigning(
                                                localChatMessage.chatMessage.frostSigningSessionId
                                            )
                                        },
                                        opens = stringResource(Res.string.open_the_proposal),
                                    )
                                    return@items
                                }

                                BoxWithConstraints(
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    val screenWidth = maxWidth

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = if (localChatMessage.chatMessage.isUserMessage) {
                                            Arrangement.End
                                        } else {
                                            Arrangement.Start
                                        }
                                    ) {
                                        // Only somebody else's message, and only in a
                                        // group -- a NIP-17 room has no audience for a
                                        // message to be private from.
                                        val canReplyPrivately =
                                            viewModel.localChatRoom.chatRoom.mlsGroupState != null &&
                                                !localChatMessage.chatMessage.isUserMessage &&
                                                viewModel.participantFor(localChatMessage.chatMessage.senderPublicKey) != null

                                        Card(
                                            modifier = Modifier.widthIn(
                                                max = screenWidth * 0.8f
                                            ).wrapContentWidth(),
                                            onClick = {
                                                if (canReplyPrivately) {
                                                    viewModel.openMessageActions(localChatMessage.chatMessage.id)
                                                }
                                            },
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(MaterialTheme.spacing.space125),
                                                horizontalAlignment = if (localChatMessage.chatMessage.isUserMessage) {
                                                    Alignment.End
                                                } else {
                                                    Alignment.Start
                                                },
                                                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space75)
                                            ) {
                                                if (localChatMessage.chatMessage.isUserMessage.not()) {
                                                    Text(
                                                        text = localChatMessage.profile?.humanReadableNameOrPubkey() ?: localChatMessage.chatMessage.senderPublicKey,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.MiddleEllipsis,
                                                        color = ProfileColor.fromPublicKey(localChatMessage.chatMessage.senderPublicKey),
                                                        style = MaterialTheme.typography.labelSmall
                                                    )
                                                }

                                                // A readable direct message must never
                                                // pass for a public one. The label says
                                                // who the other party is, since that is
                                                // the thing a reader would otherwise
                                                // assume was the whole room.
                                                if (localChatMessage.chatMessage.messageType == ChatMessage.TYPE_DIRECT_MESSAGE) {
                                                    Row(
                                                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Icon(
                                                            Icons.Default.Lock,
                                                            contentDescription = Decorative,
                                                            modifier = Modifier.size(12.dp),
                                                            tint = MaterialTheme.colorScheme.primary
                                                        )
                                                        Text(
                                                            text = if (localChatMessage.chatMessage.isUserMessage) {
                                                                stringResource(Res.string.private_to, viewModel.nameFor(localChatMessage.chatMessage.directMessageRecipientPublicKey))
                                                            } else {
                                                                stringResource(Res.string.private_to_you)
                                                            },
                                                            color = MaterialTheme.colorScheme.primary,
                                                            style = MaterialTheme.typography.labelSmall
                                                        )
                                                    }
                                                }

                                                SelectionContainer {

                                                    Text(
                                                        text = localChatMessage.chatMessage.content,
                                                        style = MaterialTheme.typography.bodyMedium
                                                    )

                                                }

                                                Row(
                                                    horizontalArrangement = Arrangement.spacedBy(
                                                        MaterialTheme.spacing.space125,
                                                        Alignment.End
                                                    ),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Text(
                                                        text = localChatMessage.chatMessage.createdAt.toFormattedTimeAndDateString(),
                                                        style = MaterialTheme.typography.labelSmall
                                                    )
                                                    if (localChatMessage.chatMessage.isUserMessage) {
                                                        if (localChatMessage.chatMessageBroadcastNostrEventReceiptRelation != null) {
                                                            Icon(
                                                                Icons.Default.Check,
                                                                contentDescription = "Message sent"
                                                            )
                                                        } else if (localChatMessage.chatMessageBroadcastNostrEventRequestRelation != null) {
                                                            Icon(
                                                                Icons.Default.AccessTime,
                                                                contentDescription = "Message sent"
                                                            )
                                                        } else if (localChatMessage.chatMessageNostrEventRelation != null) {
                                                            Icon(
                                                                Icons.Default.Pending,
                                                                contentDescription = "Message signed and sealed status"
                                                            )
                                                        } else {
                                                            Icon(
                                                                Icons.Default.KeyOff,
                                                                contentDescription = "Unsealed message status"
                                                            )
                                                        }

                                                    }

                                                }
                                            }
                                        }

                                        DropdownMenu(
                                            expanded = viewModel.openMessageActionsFor == localChatMessage.chatMessage.id,
                                            onDismissRequest = { viewModel.openMessageActions(null) }
                                        ) {
                                            DropdownMenuItem(
                                                text = {
                                                    Text(stringResource(Res.string.reply_privately_to, viewModel.nameFor(localChatMessage.chatMessage.senderPublicKey)))
                                                },
                                                leadingIcon = {
                                                    Icon(Icons.Default.Lock, contentDescription = Decorative)
                                                },
                                                onClick = {
                                                    viewModel.participantFor(localChatMessage.chatMessage.senderPublicKey)
                                                        ?.let { viewModel.startDirectMessage(it) }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            ChatMessageListUIState.Loading -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                )  {
                    Spacer(
                        modifier = Modifier.height(MaterialTheme.spacing.space600)
                    )
                    Text(
                        text = stringResource(Res.string.loading),
                        textAlign = TextAlign.Center
                    )
                    Spacer(
                        modifier = Modifier.height(MaterialTheme.spacing.space600)
                    )

                    LoadingIndicator()
                }
            }
        }
    }
}
/**
 * What the group is waiting on this member to sign, standing at the foot of the
 * transcript.
 *
 * A proposal announces itself as a line and then the conversation carries it
 * upward, but the decision it asks for does not expire with the scroll -- and a
 * member who has not answered is what the whole room is waiting on. So the ask
 * is restated where the reader already is, under the newest message, and is gone
 * the moment nothing is owed. Nothing to dismiss: there is no state here beyond
 * whether the group still needs an answer.
 *
 * Named after what it signs when there is one of them, because "a proposal is
 * waiting" is not something anybody can decide about. With several, the count is
 * the honest summary -- naming one of several here would say the others were not
 * there.
 *
 * It opens the room's proposals rather than the one it names, in every case. The
 * transcript's own lines are the way to one proposal; this is the standing count
 * of what is owed, and the queue is the screen that answers the question it
 * raises -- including for the one it could not name.
 */
/**
 * What a ceremony in this room still wants from this member, under the newest
 * message.
 *
 * The counterpart to [ProposalsAwaitingYouNotice], for the other thing a room can
 * be owed. It matters more in a NIP-17 room than a signing notice does, because a
 * NIP-17 room is where ceremonies happen and a ceremony cannot finish until every
 * member has taken part -- so one member not finding their request stalls everyone
 * indefinitely, with nothing on any screen saying why.
 *
 * Unlike the signing notice this opens the ceremony it names rather than a queue.
 * There is no queue of ceremonies to open, and naming one is honest: with several,
 * the count is shown and the newest is opened, which is the one a member is most
 * likely to have just been asked about.
 *
 * The coordinator's case has no approval gate behind it at all. A ceremony that
 * has produced a key still needs its state signed and its room created, and the
 * member who opened it is the one who does that -- so `roomAwaitingCreation`
 * earns a place here beside the gates, and stops being asked for the moment the
 * room exists.
 */
@Composable
private fun CeremoniesAwaitingYouNotice(
    ceremonies: List<ChatMessageListViewModel.AwaitingCeremony>,
    onClick: (ChatMessageListViewModel.AwaitingCeremony) -> Unit,
) {
    val single = ceremonies.singleOrNull()
    val isLightning = single?.ask is ChatMessageListViewModel.Ask.LightningKeyStep ||
        single?.ask == ChatMessageListViewModel.Ask.LightningWalletOpening

    val headline = when {
        single == null -> stringResource(Res.string.ceremonies_need_you, ceremonies.size)
        single.ask == ChatMessageListViewModel.Ask.LightningWalletOpening ->
            stringResource(Res.string.the_lightning_wallet_s_key_is_ready)
        isLightning -> stringResource(Res.string.the_lightning_wallet_s_key_ceremony_needs_you)
        single.isSubgroup -> stringResource(Res.string.the_subgroups_key_ceremony_needs_you)
        else -> stringResource(Res.string.the_key_ceremony_needs_you)
    }

    // What it wants, in the words the approval screens use for the same step, so
    // a member is not asked twice in two vocabularies. The gate comes first: an
    // approval is owed by this member and cannot be done by anybody else, while
    // finishing the setup is work the coordinator can come back to. The Lightning
    // wallet's gates are buttons on its ceremony screen, and say what those say.
    val detail = single?.let {
        when (val ask = it.ask) {
            is ChatMessageListViewModel.Ask.SharedKeyStep -> when (ask.step) {
                DkgApprovalStep.HOST_KEY -> stringResource(Res.string.join_the_ceremony_by_publishing_your_key)
                DkgApprovalStep.ROUND_1 -> stringResource(Res.string.send_your_contribution_to_the_key)
                DkgApprovalStep.ROUND_2 -> stringResource(Res.string.check_the_combined_result)
            }
            is ChatMessageListViewModel.Ask.RoomCreation -> stringResource(Res.string.the_group_has_its_key_finish_setting_it_up)
            is ChatMessageListViewModel.Ask.LightningKeyStep -> when (ask.step) {
                IcebergApprovalStep.JOIN -> stringResource(Res.string.join_the_ceremony)
                IcebergApprovalStep.REVEAL -> stringResource(Res.string.send_your_contributions)
                IcebergApprovalStep.CONFIRM -> stringResource(Res.string.confirm_what_you_saw)
            }
            ChatMessageListViewModel.Ask.LightningWalletOpening -> stringResource(Res.string.open_a_lightning_wallet)
        }
    }

    Card(
        onClick = { onClick(ceremonies.first()) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacing.space50),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.space150),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(if (isLightning) Icons.Default.Bolt else Icons.Default.Key, contentDescription = Decorative)

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space25)
            ) {
                Text(text = headline, style = MaterialTheme.typography.labelMedium)

                if (detail != null) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Text(
                text = stringResource(Res.string.open),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun ProposalsAwaitingYouNotice(
    proposals: List<ChatMessageListViewModel.AwaitingProposal>,
    onClick: () -> Unit,
) {
    val single = proposals.singleOrNull()

    val summary = single?.lead
        ?.let { lead ->
            listOfNotNull(lead.label, lead.detail.takeIf { it.isNotBlank() })
                .joinToString(" · ")
        }
        // Two ways for a proposal to have no lead, and they are different
        // situations: a session can exist before its proposal has arrived, and a
        // proposal can arrive holding events this build cannot read. Said the
        // same way the proposal list says it.
        ?: single?.let {
            if (it.eventCount == 0) {
                "Nothing has arrived to sign yet"
            } else {
                "None of its events could be read"
            }
        }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacing.space50),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.space150),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Draw,
                contentDescription = Decorative
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space25)
            ) {
                Text(
                    text = if (single != null) {
                        stringResource(Res.string.waiting_for_your_signature)
                    } else {
                        stringResource(Res.string.proposals_are_waiting_for_your_signature, proposals.size)
                    },
                    style = MaterialTheme.typography.labelMedium
                )

                if (summary != null) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // The same word the transcript's own request lines use for the same
            // thing, so a member reading down the room is not asked twice in two
            // vocabularies.
            Text(
                text = stringResource(Res.string.review),
                style = MaterialTheme.typography.labelLarge
            )

            Icon(
                Icons.Default.ChevronRight,
                contentDescription = "Open the group's proposals"
            )
        }
    }
}

/**
 * A private message this device cannot read, as a system line.
 *
 * Deliberately not a bubble. The group is meant to know that a private message was sent
 * and to whom -- that is the honest half of the feature -- but an empty bubble attributed
 * to the sender would read as them having said nothing, and a bubble with placeholder text
 * would read as them having said the placeholder.
 *
 * Not tappable: there is nothing behind it to open. See docs/marmot-direct-messages.md.
 */
@Composable
private fun PrivateMessageNotice(
    sender: String,
    recipient: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.spacing.space250, vertical = MaterialTheme.spacing.space125),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Lock,
            contentDescription = Decorative,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = stringResource(Res.string.sent_a_private_message_to, sender, recipient),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

/**
 * A ChillDKG milestone, as a system line across the transcript.
 *
 * Deliberately not a bubble: nobody said this, and giving it a sender and a side
 * would make the coordinator appear to have announced it. It is tappable because
 * the point of telling the group is to give them somewhere to go — a ritual only
 * finishes once every member's device has taken part, and the ladder that shows
 * who it is waiting on lives on the shared-key screen.
 */
@Composable
private fun RitualNotice(
    localChatMessage: LocalChatMessage,
    isAnswered: Boolean,
    isSettled: Boolean,
    /** Null for a line that leads nowhere, or names no ceremony, which then opens nothing. */
    onClick: (() -> Unit)?,
    /**
     * What a tap opens, as the chevron's description; null draws no chevron. Each family
     * opens its own screen, and one description for all of them told a screen reader
     * every signing line opened the shared key ceremony.
     */
    opens: String?,
) {
    val chatMessage = localChatMessage.chatMessage

    // One per stage. A ceremony puts a dozen-odd lines in a row into the transcript,
    // and with a single icon on all of them the reader has to actually read each to
    // tell "somebody joined" from "somebody contributed" from "you are being asked
    // for something". A request shares its stage's icon rather than getting a
    // distinct one: it is the same step, before rather than after, and the primary
    // tint and the Review affordance already say which.
    val icon = when (chatMessage.messageType) {
        ChatMessage.TYPE_DKG_STARTED -> Icons.Default.Key
        ChatMessage.TYPE_DKG_HOST_KEY -> Icons.Default.PersonAdd
        ChatMessage.TYPE_DKG_ROUND_1 -> Icons.Default.Upload
        ChatMessage.TYPE_DKG_COORDINATOR_ROUND_1 -> Icons.Default.CallMerge
        ChatMessage.TYPE_DKG_ROUND_2 -> Icons.Default.FactCheck
        ChatMessage.TYPE_DKG_CERTIFICATE -> Icons.Default.WorkspacePremium
        ChatMessage.TYPE_DKG_COMPLETE -> Icons.Default.CheckCircle
        ChatMessage.TYPE_DKG_FAILED -> Icons.Default.ErrorOutline

        ChatMessage.TYPE_DKG_APPROVAL_NEEDED_HOST_KEY -> Icons.Default.PersonAdd
        ChatMessage.TYPE_DKG_APPROVAL_NEEDED_ROUND_1 -> Icons.Default.Upload
        ChatMessage.TYPE_DKG_APPROVAL_NEEDED_ROUND_2 -> Icons.Default.FactCheck

        ChatMessage.TYPE_ICEBERG_STARTED -> Icons.Default.Key
        ChatMessage.TYPE_ICEBERG_COMMITTED, ChatMessage.TYPE_ICEBERG_APPROVAL_NEEDED_JOIN -> Icons.Default.PersonAdd
        ChatMessage.TYPE_ICEBERG_REVEALED, ChatMessage.TYPE_ICEBERG_APPROVAL_NEEDED_REVEAL -> Icons.Default.Upload
        ChatMessage.TYPE_ICEBERG_CONFIRMED, ChatMessage.TYPE_ICEBERG_APPROVAL_NEEDED_CONFIRM -> Icons.Default.FactCheck
        ChatMessage.TYPE_ICEBERG_COMPLETE -> Icons.Default.CheckCircle
        ChatMessage.TYPE_ICEBERG_FAILED -> Icons.Default.ErrorOutline

        ChatMessage.TYPE_FROST_STARTED -> Icons.Default.Draw
        ChatMessage.TYPE_FROST_NONCE -> Icons.Default.Upload
        ChatMessage.TYPE_FROST_SIGNER_SET -> Icons.Default.Groups
        ChatMessage.TYPE_FROST_PARTIAL_SIGNATURE -> Icons.Default.Draw
        ChatMessage.TYPE_FROST_SIGNATURE -> Icons.Default.WorkspacePremium
        ChatMessage.TYPE_FROST_COMPLETE -> Icons.Default.CheckCircle
        ChatMessage.TYPE_FROST_FAILED -> Icons.Default.ErrorOutline
        // A refusal the session survived. Quiet on purpose: it is not an error,
        // and the one that ends a session is written as a failure instead.
        ChatMessage.TYPE_FROST_DECLINED -> Icons.Default.Block
        // Nothing moved; somebody knocked. Its own icon rather than the
        // proposal's, so that a resend does not read as a second proposal --
        // which is the one thing a session must never have.
        ChatMessage.TYPE_FROST_RESENT -> Icons.Default.Campaign
        ChatMessage.TYPE_FROST_APPROVAL_NEEDED -> Icons.Default.Draw

        ChatMessage.TYPE_CHRONICLE_REQUESTED -> Icons.Default.History
        ChatMessage.TYPE_CHRONICLE_SENT -> Icons.Default.Upload
        ChatMessage.TYPE_CHRONICLE_RECEIVED -> Icons.Default.Download

        // An invite made and an invite sent are two separate steps on the deferred
        // path, so they get separate icons -- the whole reason both lines exist is
        // to be able to see that the first happened and the second did not.
        ChatMessage.TYPE_SUBGROUP_CERTIFIED -> Icons.Default.AccountTree
        ChatMessage.TYPE_MEMBER_WALLET_REQUESTED, ChatMessage.TYPE_MEMBER_WALLET_GUARDED -> Icons.Default.AccountBalanceWallet
        ChatMessage.TYPE_PAYOUT_DESTINATION_SET, ChatMessage.TYPE_PAYOUT_DESTINATION_REMOVED -> Icons.Default.Payments
        ChatMessage.TYPE_DEPOSITS_OPENED, ChatMessage.TYPE_DEPOSITS_ATTRIBUTED, ChatMessage.TYPE_COINS_ASSIGNED -> Icons.Default.Savings
        ChatMessage.TYPE_MEMBER_INVITED -> Icons.Default.PersonAdd
        ChatMessage.TYPE_MEMBER_INVITE_SENT -> Icons.Default.Upload
        ChatMessage.TYPE_MEMBER_INVITE_FAILED -> Icons.Default.ErrorOutline
        ChatMessage.TYPE_CHANGE_REJECTED -> Icons.Default.ErrorOutline

        ChatMessage.TYPE_GROUP_LIGHTNING_WALLET_OPENED -> Icons.Default.Bolt
        ChatMessage.TYPE_GROUP_LIGHTNING_OPERATOR_ELECTED -> Icons.Default.Badge
        ChatMessage.TYPE_GROUP_LIGHTNING_PAYMENT_AUTHORISED -> Icons.Default.Payments
        ChatMessage.TYPE_GROUP_WAVELENGTH_WALLET_OPENED -> Icons.Default.Waves
        ChatMessage.TYPE_GROUP_WAVELENGTH_OPERATOR_ELECTED -> Icons.Default.Badge
        ChatMessage.TYPE_GROUP_WAVELENGTH_PAYMENT_APPROVED -> Icons.Default.Payments
        ChatMessage.TYPE_LIGHTNING_SEED_SHARED -> Icons.Default.Key
        ChatMessage.TYPE_LIGHTNING_INVOICE_REQUESTED -> Icons.Default.RequestQuote
        ChatMessage.TYPE_LIGHTNING_INVOICE_ISSUED -> Icons.Default.Receipt
        ChatMessage.TYPE_LIGHTNING_OPERATOR_STOPPED -> Icons.Default.PowerSettingsNew
        ChatMessage.TYPE_LIGHTNING_CHANNEL_OPENED -> Icons.Default.AddLink
        ChatMessage.TYPE_LIGHTNING_CHANNEL_CLOSED -> Icons.Default.LinkOff
        ChatMessage.TYPE_LIGHTNING_PAYMENT_RECEIVED -> Icons.Default.CallReceived
        ChatMessage.TYPE_LIGHTNING_PAYMENT_SENT -> Icons.Default.CallMade
        ChatMessage.TYPE_LIGHTNING_PAYMENT_FAILED -> Icons.Default.ErrorOutline
        ChatMessage.TYPE_LIGHTNING_LIQUIDITY_ADDED -> Icons.Default.AddLink
        ChatMessage.TYPE_LIGHTNING_LIQUIDITY_FAILED -> Icons.Default.ErrorOutline
        ChatMessage.TYPE_LIGHTNING_REFUSED -> Icons.Default.Block
        ChatMessage.TYPE_LIGHTNING_EQUIVOCATION -> Icons.Default.Warning
        ChatMessage.TYPE_WAVELENGTH_REFUSED -> Icons.Default.Block
        ChatMessage.TYPE_WAVELENGTH_EQUIVOCATION -> Icons.Default.Warning
        ChatMessage.TYPE_WAVELENGTH_ROUND_DIED -> Icons.Default.ErrorOutline
        ChatMessage.TYPE_WAVELENGTH_DEADLINE -> Icons.Default.Warning
        ChatMessage.TYPE_WAVELENGTH_OPERATOR_STOPPED -> Icons.Default.PowerSettingsNew
        ChatMessage.TYPE_WAVELENGTH_PAID_OUT -> Icons.Default.CallMade

        else -> Icons.Default.PanTool
    }

    // The requests are the ritual lines that ask rather than report, and the ones
    // the ceremony cannot get past on its own. Everything else here is deliberately
    // quiet; these are not.
    // An answered request is history, not a summons: it keeps its stage's icon so
    // the step is still recognisable, but drops the colour and the call to action.
    // So is a settled one -- declined, abandoned, or signed by a quorum that did
    // not need this member. Nothing was answered there, so it gets no tick, but
    // offering to review it would be offering a decision that has already gone by.
    val asks = chatMessage.messageType in ChatMessage.DKG_REQUEST_TYPES ||
        chatMessage.messageType in ChatMessage.ICEBERG_REQUEST_TYPES ||
        chatMessage.messageType in ChatMessage.FROST_REQUEST_TYPES
    val isRequest = asks && !isAnswered && !isSettled

    val tint = when {
        chatMessage.messageType == ChatMessage.TYPE_DKG_FAILED ||
            chatMessage.messageType == ChatMessage.TYPE_ICEBERG_FAILED ||
            chatMessage.messageType == ChatMessage.TYPE_FROST_FAILED ||
            chatMessage.messageType == ChatMessage.TYPE_MEMBER_INVITE_FAILED ||
            chatMessage.messageType == ChatMessage.TYPE_LIGHTNING_PAYMENT_FAILED ||
            chatMessage.messageType == ChatMessage.TYPE_LIGHTNING_LIQUIDITY_FAILED ||
            chatMessage.messageType == ChatMessage.TYPE_LIGHTNING_REFUSED ||
            chatMessage.messageType == ChatMessage.TYPE_LIGHTNING_EQUIVOCATION ||
            chatMessage.messageType == ChatMessage.TYPE_WAVELENGTH_REFUSED ||
            chatMessage.messageType == ChatMessage.TYPE_WAVELENGTH_EQUIVOCATION ||
            chatMessage.messageType == ChatMessage.TYPE_WAVELENGTH_DEADLINE ||
            chatMessage.messageType == ChatMessage.TYPE_CHANGE_REJECTED ->
            MaterialTheme.colorScheme.error
        isRequest -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = MaterialTheme.spacing.space250, vertical = MaterialTheme.spacing.space125),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = Decorative,
            tint = tint
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space25)
        ) {
            Text(
                text = buildAnnotatedString {
                    // Somebody opened this ceremony, or somebody walked away from it,
                    // and which member that was is the point of the line. Resolved
                    // from the joined profile rather than written into the content,
                    // so it follows a rename and is not stuck on the "LOADING..."
                    // placeholder a member is given the moment they are first seen.
                    if (chatMessage.messageType in ChatMessage.DKG_AUTHORED_TYPES ||
                        chatMessage.messageType in ChatMessage.ICEBERG_AUTHORED_TYPES ||
                        chatMessage.messageType in ChatMessage.FROST_AUTHORED_TYPES ||
                        chatMessage.messageType == ChatMessage.TYPE_WAVELENGTH_OPERATOR_STOPPED ||
                        chatMessage.messageType in ChatMessage.LIGHTNING_AUTHORED_TYPES
                    ) {
                        withStyle(
                            SpanStyle(color = ProfileColor.fromPublicKey(chatMessage.senderPublicKey))
                        ) {
                            append(
                                if (chatMessage.isUserMessage) {
                                    stringResource(Res.string.you)
                                } else {
                                    localChatMessage.profile?.humanReadableNameOrPubkey()
                                        ?: chatMessage.senderPublicKey.shortened()
                                }
                            )
                        }
                        append(" ")
                    }

                    append(chatMessage.content)
                },
                style = MaterialTheme.typography.bodySmall,
                color = tint
            )

            Text(
                text = chatMessage.createdAt.toFormattedTimeAndDateString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (isRequest) {
            Text(
                text = stringResource(Res.string.review),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        } else if (asks && isAnswered) {
            // Only a request can have been approved. The lines that report rather
            // than ask are passed as answered to keep them quiet, and a tick on
            // "the group made a subgroup" would claim the reader approved it.
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = stringResource(Res.string.you_approved_this),
                tint = MaterialTheme.colorScheme.primary
            )
        }

        if (opens != null && onClick != null) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = opens,
                tint = tint
            )
        }
    }
}
