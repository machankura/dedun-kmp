package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.ui.composable.navigation.routes.AddMemberToChatRoomConfirmationRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.profile.ProfileAvatar
import com.machankura.dedun.compose.ui.theme.BennuTheme
import com.machankura.dedun.compose.ui.view.model.SearchMemberToAddToChatRoomViewModel
import com.machankura.dedun.compose.ui.view.state.SearchMemberToAddToChatRoomUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.ui.theme.spacing
import dedun.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import dedun.composeapp.generated.resources.currently_no_contacts_please_search_and_chat
import dedun.composeapp.generated.resources.search_member_functionality
import dedun.composeapp.generated.resources.search_message_functionality_will_be_here
import androidx.compose.material3.SnackbarHost
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.theme.ConformancePreviews

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SearchMemberToAddToChatRoomScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    relayHint: String?,
    initialSearchMemberToAddToChatRoomUIState: SearchMemberToAddToChatRoomUIState = SearchMemberToAddToChatRoomUIState.Loading,
    nostrRepository: NostrRepository,
    chatRepository: ChatRepository,
    onNavigateToRoute: (Route) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val searchMemberToAddToChatRoomViewModel: SearchMemberToAddToChatRoomViewModel = viewModel(
        factory = SearchMemberToAddToChatRoomViewModel.factory(
            chatRoomId = chatRoomId,
            relayHint = relayHint,
            initialSearchMemberToAddToChatRoomUIState = initialSearchMemberToAddToChatRoomUIState,
            nostrRepository = nostrRepository,
            chatRepository = chatRepository,
            activeUserPublicKey = activeUserPublicKey
        )
    )

    ScreenStateTransition(searchMemberToAddToChatRoomViewModel.searchMemberToAddToChatRoomUIState) { uiState ->
    when (val searchMemberToAddToChatRoomUIState = uiState) {
            is SearchMemberToAddToChatRoomUIState.Error -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(
                        modifier = Modifier.height(MaterialTheme.spacing.space600)
                    )
                    Text(
                        text = searchMemberToAddToChatRoomUIState.message,
                    )
                }
            }
            is SearchMemberToAddToChatRoomUIState.Loaded -> {
                Scaffold(
                    snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
                    topBar = {
                        TopAppBar(
                            title = {
                                val title = if (searchMemberToAddToChatRoomUIState.localChatRoom.chatRoom.subject != null) {
                                    "Invite new member to ${searchMemberToAddToChatRoomUIState.localChatRoom.chatRoom.subject}"
                                } else {
                                    "Invite new member to chat"
                                }
                                Text(
                                    text = title,
                                )
                            },
                            navigationIcon = { NavigateBackButton(onNavigateBack) },
                        )
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier.padding(innerPadding).readableContent().fillMaxSize()
                    ) {
                        Column(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125)
                        ) {

                            if (searchMemberToAddToChatRoomUIState.contacts.isEmpty()) {
                                Spacer(
                                    modifier = Modifier.height(MaterialTheme.spacing.space250)
                                )
                                Text(
                                    modifier = Modifier.padding(MaterialTheme.spacing.space250),
                                    text = stringResource(Res.string.currently_no_contacts_please_search_and_chat),
                                    textAlign = TextAlign.Center
                                )

                                Spacer(
                                    modifier = Modifier.height(MaterialTheme.spacing.space250)
                                )
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.space50),
                                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125),
                                    reverseLayout = true
                                ) {

                                    items(
                                        items = searchMemberToAddToChatRoomUIState.contacts,
                                        key = { it.publicKey }
                                    ) { profile ->
                                        Card(
                                            onClick = {
                                                onNavigateToRoute.invoke(
                                                    AddMemberToChatRoomConfirmationRoute(
                                                        activeUserPublicKey = activeUserPublicKey,
                                                        chatRoomId = chatRoomId,
                                                        profilePublicKey = profile.publicKey,
                                                        relayHint = relayHint,
                                                    )
                                                )
                                            }
                                        ) {
                                            ListItem(
                                                leadingContent = {
                                                    ProfileAvatar(
                                                        profile = profile
                                                    )
                                                },
                                                headlineContent = {
                                                    Text(
                                                        profile.humanReadableNameOrPubkey()
                                                    )
                                                },
                                                supportingContent = {
                                                    profile.about?.let {
                                                        Text(
                                                            text = it,
                                                            overflow = TextOverflow.Ellipsis,
                                                            maxLines = 1
                                                        )
                                                    }
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
            SearchMemberToAddToChatRoomUIState.Loading -> {
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
                        text = stringResource(Res.string.search_member_functionality),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = stringResource(Res.string.search_message_functionality_will_be_here),
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
        if (initialSearchMemberToAddToChatRoomUIState == SearchMemberToAddToChatRoomUIState.Loading) {
            searchMemberToAddToChatRoomViewModel.initiate()
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
            SearchMemberToAddToChatRoomScreen(
                activeUserPublicKey = "",
                chatRoomId = "publicKey",
                relayHint = null,
                initialSearchMemberToAddToChatRoomUIState = SearchMemberToAddToChatRoomUIState.Loaded(
                    localChatRoom = LocalChatRoom(
                        chatRoom = ChatRoom(
                            id = "",
                            userPublicKey = "",
                            subject = "Message title",
                            description = "See something... say something.",
                            initialGiftWrapPayloadId = "sdfaer",
                            mlsGroupState = null
                        ),
                    ),
                    contacts = listOf(
                        Profile(
                            publicKey = "hex",
                            displayName = "Man With A Plan",
                            about = "Making plans and getting things done...",
                            nostrEventId = "nostrEventId"
                        )
                    )
                ),
                nostrRepository = NostrRepository.NO_OP_NOSTR_REPOSITORY,
                chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                onNavigateToRoute = {},
                onNavigateBack = {},
            )
        }
    }
}