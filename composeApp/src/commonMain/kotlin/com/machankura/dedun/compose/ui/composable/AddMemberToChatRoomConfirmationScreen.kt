package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.MarmotKeyPackage
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.theme.BennuTheme
import com.machankura.dedun.compose.ui.view.model.AddMemberToChatRoomConfirmationViewModel
import com.machankura.dedun.compose.ui.view.state.AddMemberToChatRoomConfirmationUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.ui.theme.spacing
import bennu.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import bennu.composeapp.generated.resources.direct_message_detail
import bennu.composeapp.generated.resources.direct_message_functionality_will_be_here
import bennu.composeapp.generated.resources.invite
import bennu.composeapp.generated.resources.invite_2
import bennu.composeapp.generated.resources.invite_new_member
import bennu.composeapp.generated.resources.once_invited_will_be_able_to_receive_and
import bennu.composeapp.generated.resources.to_join_the_chat_room
import androidx.compose.material3.SnackbarHost
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.composable.widgets.buttons.ExtendedFab
import com.machankura.dedun.compose.ui.theme.ConformancePreviews

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AddMemberToChatRoomConfirmationScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: HexKey,
    profilePublicKey: HexKey,
    relayHint: String?,
    initialChatRoomDetailUIState: AddMemberToChatRoomConfirmationUIState = AddMemberToChatRoomConfirmationUIState.Loading,
    nostrRepository: NostrRepository,
    chatRepository: ChatRepository,
    onInviteSent: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val addMemberToChatRoomConfirmationViewModel: AddMemberToChatRoomConfirmationViewModel = viewModel(
        factory = AddMemberToChatRoomConfirmationViewModel.factory(
            chatRoomId = chatRoomId,
            profilePublicKey = profilePublicKey,
            relayHint = relayHint,
            initialAddMemberToChatRoomConfirmationUIState = initialChatRoomDetailUIState,
            nostrRepository = nostrRepository,
            chatRepository = chatRepository,
            activeUserPublicKey = activeUserPublicKey
        )
    )

    ScreenStateTransition(addMemberToChatRoomConfirmationViewModel.addMemberToChatRoomConfirmationUIState) { uiState ->
    when (val addMemberToChatRoomConfirmationUIState = uiState) {
            is AddMemberToChatRoomConfirmationUIState.Error -> {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.space250),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(
                        modifier = Modifier.height(MaterialTheme.spacing.space600)
                    )
                    Text(
                        text = addMemberToChatRoomConfirmationUIState.message,
                        textAlign = TextAlign.Center
                    )
                }
            }
            is AddMemberToChatRoomConfirmationUIState.Loaded -> {
                Scaffold(
                    snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
                    topBar = {
                        // The body already names who is being invited to which room,
                        // so the bar says only what the screen is for.
                        TopAppBar(
                            title = { Text(stringResource(Res.string.invite_new_member)) },
                            navigationIcon = { NavigateBackButton(onNavigateBack) },
                        )
                    },
                    bottomBar = {
                        BottomAppBar(
                            floatingActionButton = {
                                ExtendedFab(
                                    label = stringResource(
                                        Res.string.invite_2,
                                        addMemberToChatRoomConfirmationUIState.profile.humanReadableNameOrPubkey()
                                    ),
                                    icon = { Icon(Icons.Default.Add, contentDescription = "Invite new member") },
                                    onClick = {
                                        addMemberToChatRoomConfirmationViewModel.inviteToChatRoom(
                                            localChatRoom = addMemberToChatRoomConfirmationUIState.localChatRoom,
                                            peerPublicKey = addMemberToChatRoomConfirmationUIState.profile.publicKey,
                                            peerKeyPackage = addMemberToChatRoomConfirmationUIState.marmotKeyPackage,
                                            onInviteSent = onInviteSent
                                        )
                                    }
                                )
                            },
                            actions = {

                            }
                        )
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier.padding(innerPadding).readableContent().fillMaxSize()
                    ) {
                        Column(
                            modifier = Modifier.weight(1f).fillMaxWidth().padding(MaterialTheme.spacing.space250),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125)
                        ) {
                            Spacer(
                                modifier = Modifier.weight(1f)
                            )

                            Text(stringResource(Res.string.invite))

                            Text(
                                text = addMemberToChatRoomConfirmationUIState.profile.humanReadableNameOrPubkey()
                            )

                            Text(
                                text = stringResource(Res.string.to_join_the_chat_room, addMemberToChatRoomConfirmationUIState.localChatRoom.chatRoom.subject ?: "")
                            )

                            Text(
                                text = stringResource(Res.string.once_invited_will_be_able_to_receive_and, addMemberToChatRoomConfirmationUIState.localChatRoom.localParticipants.size),
                                textAlign = TextAlign.Center
                            )

                            Spacer(
                                modifier = Modifier.weight(3f)
                            )
                        }
                    }
                }
            }
            AddMemberToChatRoomConfirmationUIState.Loading -> {
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
        if (initialChatRoomDetailUIState == AddMemberToChatRoomConfirmationUIState.Loading) {
            addMemberToChatRoomConfirmationViewModel.initiate()
        }
    }
}

@ConformancePreviews
@Composable
private fun ChatRoomDetailScreenPreview() {
    BennuTheme {
        Surface(
            modifier = Modifier.fillMaxSize()
        ) {
            AddMemberToChatRoomConfirmationScreen(
                activeUserPublicKey = "",
                profilePublicKey = "hex",
                chatRoomId = "publicKey",
                relayHint = null,
                initialChatRoomDetailUIState =
//                    AddMemberToChatRoomConfirmationUIState.Error("Profile missing key package event.\n\nTry again later or tell Person to use Bennu."),
                    AddMemberToChatRoomConfirmationUIState.Loaded(
                    localChatRoom = LocalChatRoom(
                        chatRoom = ChatRoom(
                            id = "hex",
                            userPublicKey = "hex",
                            subject = "Room title",
                            description = "See something... say something.",
                            initialGiftWrapPayloadId = "sdfaer",
                            mlsGroupState = null
                        ),
                    ),
                        profile = Profile(
                            publicKey = "hex",
                            nostrEventId = "hex",
                            displayName = "Person"
                        ),
                        marmotKeyPackage = MarmotKeyPackage(
                            id = "keypackage",
                            publicKey = "hex",
                            tlsEncodedMarmotKeyPackage = ByteArray(0)
                        )
                ),
                nostrRepository = NostrRepository.NO_OP_NOSTR_REPOSITORY,
                chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                onInviteSent = {},
                onNavigateBack = {},
            )
        }
    }
}