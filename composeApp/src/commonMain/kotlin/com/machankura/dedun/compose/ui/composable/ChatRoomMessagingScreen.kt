package com.machankura.dedun.compose.ui.composable

import com.machankura.dedun.compose.ui.composable.navigation.routes.IcebergCeremonyRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.GroupLightningWalletRoute
import com.machankura.dedun.compose.repository.GroupLightningRepository
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.BottomAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.DkgRepository
import com.machankura.dedun.compose.repository.FrostSigningRepository
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.ui.composable.navigation.routes.ChatRoomDetailRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.DkgRitualRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.FrostSigningRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.ProposalListRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.proposalDestination
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.theme.DedunTheme
import com.machankura.dedun.compose.ui.view.model.ChatRoomMessagingViewModel
import com.machankura.dedun.compose.ui.view.state.ChatRoomMessagingUIState
import com.machankura.dedun.compose.ui.view.state.ProposalFilter
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.ui.composable.widgets.chat.ChatTranscript
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import dedun.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import dedun.composeapp.generated.resources.creating_new_chat
import dedun.composeapp.generated.resources.direct_message_detail
import dedun.composeapp.generated.resources.direct_message_functionality_will_be_here
import dedun.composeapp.generated.resources.say_what_now
import dedun.composeapp.generated.resources.private_message_to
import dedun.composeapp.generated.resources.private_to
import androidx.compose.material3.SnackbarHost
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.theme.ConformancePreviews

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ChatRoomMessagingScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    relayHint: String?,
    initialChatRoomMessagingUIState: ChatRoomMessagingUIState = ChatRoomMessagingUIState.Loading,
    nostrRepository: NostrRepository,
    chatRepository: ChatRepository,
    frostSigningRepository: FrostSigningRepository,
    dkgRepository: DkgRepository,
    lightningRepository: GroupLightningRepository,
    onNavigateToRouteAndPopUpInclusive: (Route) -> Unit,
    onNavigateToRoute: (Route) -> Unit,
    /**
     * `null` when this is the home screen's detail pane rather than a pushed route. In
     * the pane the room list is beside the transcript and there is nothing to pop, so
     * the bar draws no back button -- M3's list-detail has the list do that job. On a
     * phone the same screen is a destination, and the button is what leaves it.
     */
    onNavigateBack: (() -> Unit)?,
) {
    val chatRoomMessagingViewModel: ChatRoomMessagingViewModel = viewModel(
        factory = ChatRoomMessagingViewModel.factory(
            chatRoomId = chatRoomId,
            relayHint = relayHint,
            initialChatRoomMessagingUIState = initialChatRoomMessagingUIState,
            nostrRepository = nostrRepository,
            chatRepository = chatRepository,
            activeUserPublicKey = activeUserPublicKey
        )
    )

    ScreenStateTransition(chatRoomMessagingViewModel.chatRoomMessagingUIState) { uiState ->
    when (val chatRoomDetailUIState = uiState) {
            is ChatRoomMessagingUIState.Error -> {
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
            is ChatRoomMessagingUIState.Loaded -> {
                val textFieldState = rememberTextFieldState()

                val chatMessageListViewModel: com.machankura.dedun.compose.ui.view.model.ChatMessageListViewModel = viewModel(
                    factory = com.machankura.dedun.compose.ui.view.model.ChatMessageListViewModel.factory(
                        localChatRoom = chatRoomDetailUIState.localChatRoom,
                        nostrRepository = nostrRepository,
                        chatRepository = chatRepository,
                        frostSigningRepository = frostSigningRepository,
                        dkgRepository = dkgRepository,
                        lightningRepository = lightningRepository,
                    )
                )

                Scaffold(
                    snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
                    topBar = {
                        TopAppBar(
                            title = {
                                chatRoomDetailUIState.localChatRoom.RenderChatRoomTitleText()
                            },
                            navigationIcon = {
                                if (onNavigateBack != null) NavigateBackButton(onNavigateBack)
                            },
                            actions = {
                                IconButton(
                                    onClick = {
                                        // Navigate to the invite user screen...
                                        onNavigateToRoute.invoke(
                                            ChatRoomDetailRoute(
                                                chatRoomId = chatRoomId,
                                                activeUserPublicKey = activeUserPublicKey,
                                                relayHint = relayHint
                                            )
                                        )
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.MoreVert,
                                        contentDescription = "View details..."
                                    )
                                }
                            }
                        )
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier.padding(innerPadding).readableContent().fillMaxSize()
                    ) {
                        Column(
                            modifier = Modifier.weight(1f).fillMaxWidth()
                        ) {
                            key(true) {
                                ChatTranscript(
                                    viewModel = chatMessageListViewModel,
                                    onOpenSharedKey = { dkgSessionId ->
                                        onNavigateToRoute.invoke(
                                            DkgRitualRoute(
                                                activeUserPublicKey = activeUserPublicKey,
                                                chatRoomId = chatRoomId,
                                                dkgSessionId = dkgSessionId
                                            )
                                        )
                                    },
                                    onOpenIcebergCeremony = { sessionId ->
                                        onNavigateToRoute.invoke(
                                            IcebergCeremonyRoute(
                                                activeUserPublicKey = activeUserPublicKey,
                                                chatRoomId = chatRoomId,
                                                sessionId = sessionId
                                            )
                                        )
                                    },
                                    onOpenLightningWallet = {
                                        onNavigateToRoute.invoke(
                                            GroupLightningWalletRoute(
                                                activeUserPublicKey = activeUserPublicKey,
                                                chatRoomId = chatRoomId,
                                            )
                                        )
                                    },
                                    onOpenSigning = { sessionId ->
                                        onNavigateToRoute.invoke(
                                            proposalDestination(
                                                activeUserPublicKey = activeUserPublicKey,
                                                chatRoomId = chatRoomId,
                                                sessionId = sessionId,
                                                hasOtherDecisionsWaiting =
                                                    chatMessageListViewModel::hidesOtherDecisions
                                            )
                                        )
                                    },
                                    // The notice has just counted the proposals
                                    // waiting on this member and said so. Opening
                                    // the room's whole signing history from it
                                    // would have the reader look for the three it
                                    // named, so the chip is set on the way in.
                                    onOpenProposals = {
                                        onNavigateToRoute.invoke(
                                            ProposalListRoute(
                                                activeUserPublicKey = activeUserPublicKey,
                                                chatRoomId = chatRoomId,
                                                filter = ProposalFilter.NEEDS_YOU
                                            )
                                        )
                                    }
                                )
                            }
                        }

                        key(true) {
                            chatMessageListViewModel.initiate()
                        }

                        // TODO: Check that we have direct message relays for this user...

                        // The composer handles its own bottom inset, which is why this
                        // screen is the one text-field screen without `imePadding()` on its
                        // Scaffold. Adding it here would pad twice while the keyboard is up:
                        // the ime inset already covers the navigation bar area this row is
                        // separately reserving. Getting the combination right wants a device
                        // with a keyboard open, not a compiler.
                        Column(
                            modifier = Modifier.fillMaxWidth()
                                .background(BottomAppBarDefaults.containerColor)
                                .navigationBarsPadding()
                        ) {
                            // An armed composer has to be impossible to miss. The whole risk
                            // of this feature is somebody sending to the room what they meant
                            // for one person, or the reverse, and by the time either is on the
                            // wire it cannot be taken back. Hence three signals at once: the
                            // chip, the placeholder, and the tinted field.
                            val directMessageRecipient = chatMessageListViewModel.directMessageRecipient

                            if (directMessageRecipient != null) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.primaryContainer)
                                        .padding(horizontal = MaterialTheme.spacing.containerPadding, vertical = MaterialTheme.spacing.space75),
                                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Lock,
                                        contentDescription = Decorative,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )

                                    Text(
                                        modifier = Modifier.weight(1f),
                                        text = stringResource(Res.string.private_to, chatMessageListViewModel.nameFor(directMessageRecipient.participantPublicKey)),
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        style = MaterialTheme.typography.labelMedium
                                    )

                                    // The one way back to the room, and it has to be one tap.
                                    IconButton(
                                        onClick = { chatMessageListViewModel.cancelDirectMessage() }
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Send to the whole group instead",
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                            }

                            Box(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                OutlinedTextField(
                                    modifier = Modifier.fillMaxWidth(),
                                    state = textFieldState,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color.Transparent,
                                        unfocusedBorderColor = Color.Transparent,
                                        disabledBorderColor = Color.Transparent,
                                        focusedContainerColor = if (directMessageRecipient != null) {
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                        } else {
                                            Color.Transparent
                                        },
                                        unfocusedContainerColor = if (directMessageRecipient != null) {
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                        } else {
                                            Color.Transparent
                                        }
                                    ),
                                    placeholder = {
                                        Text(
                                            text = if (directMessageRecipient != null) {
                                                stringResource(Res.string.private_message_to, chatMessageListViewModel.nameFor(directMessageRecipient.participantPublicKey))
                                            } else {
                                                stringResource(Res.string.say_what_now)
                                            }
                                        )
                                    },
                                    trailingIcon = {
                                        if (textFieldState.text.isEmpty()) {
                                            IconButton(
                                                onClick = {

                                                }
                                            ) {
                                                Icon(
                                                    Icons.Default.Mic,
                                                    contentDescription = "Voice"
                                                )
                                            }

                                        } else {
                                            IconButton(
                                                onClick = {
                                                    chatMessageListViewModel.sendMessage(
                                                        textFieldState = textFieldState
                                                    )
                                                }
                                            ) {
                                                Icon(
                                                    Icons.AutoMirrored.Filled.Send,
                                                    contentDescription = "Send"
                                                )
                                            }
                                        }
                                    }
                                )
                            }

    //                        Row(
    //                            modifier = Modifier.padding(4.dp),
    //                            horizontalArrangement = Arrangement.spacedBy(4.dp)
    //                        ) {
    //                            IconButton(
    //                                onClick = {
    //
    //                                }
    //                            ) {
    //                                Icon(
    //                                    Icons.Default.InsertEmoticon,
    //                                    contentDescription = "Add emoji"
    //                                )
    //                            }
    //
    //                            IconButton(
    //                                onClick = {
    //
    //                                }
    //                            ) {
    //                                Icon(
    //                                    Icons.Default.AlternateEmail,
    //                                    contentDescription = "Mention profile"
    //                                )
    //                            }
    //
    //                            IconButton(
    //                                onClick = {
    //
    //                                }
    //                            ) {
    //                                Icon(
    //                                    Icons.Default.Collections,
    //                                    contentDescription = "Attach media"
    //                                )
    //                            }
    //
    //                            IconButton(
    //                                onClick = {
    //
    //                                }
    //                            ) {
    //                                Icon(
    //                                    Icons.Default.LocationOn,
    //                                    contentDescription = "Send location"
    //                                )
    //                            }
    //                        }
                        }
                    }
                }
            }
            ChatRoomMessagingUIState.Loading -> {
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
            ChatRoomMessagingUIState.InitiatingNewChat -> {
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
                        text = stringResource(Res.string.creating_new_chat),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )

                    LoadingDataIndicator(
                        fillScreen = false
                    )

                    Spacer(
                        modifier = Modifier.weight(2f)
                    )

                    LaunchedEffect(true) {
                        chatRoomMessagingViewModel.initiateNewChat(
                            onNavigateToRouteAndPopUpInclusive
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(true) {
        if (initialChatRoomMessagingUIState == ChatRoomMessagingUIState.Loading) {
            chatRoomMessagingViewModel.initiateChatRoomDetail()
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
            ChatRoomMessagingScreen(
                activeUserPublicKey = "",
                chatRoomId = "publicKey",
                relayHint = null,
                initialChatRoomMessagingUIState = ChatRoomMessagingUIState.Loaded(
                    localChatRoom = LocalChatRoom(
                        chatRoom = ChatRoom(
                            id = "",
                            userPublicKey = "",
                            subject = "Message title",
                            description = "See something. Say somethin",
                            initialGiftWrapPayloadId = "sdfaer",
                            mlsGroupState = null
                        ),
                    )
                ),
                nostrRepository = NostrRepository.NO_OP_NOSTR_REPOSITORY,
                chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                frostSigningRepository = FrostSigningRepository.NO_OP_FROST_SIGNING_REPOSITORY,
                dkgRepository = DkgRepository.NO_OP_DKG_REPOSITORY,
                lightningRepository = GroupLightningRepository.NO_OP_GROUP_LIGHTNING_REPOSITORY,
                onNavigateToRouteAndPopUpInclusive = {},
                onNavigateToRoute = {},
                onNavigateBack = {},
            )
        }
    }
}