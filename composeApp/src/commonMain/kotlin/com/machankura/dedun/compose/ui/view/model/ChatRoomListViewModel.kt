package com.machankura.dedun.compose.ui.view.model

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.machankura.dedun.compose.database.model.NegentropySynchronizeRequest
import com.machankura.dedun.compose.database.model.types.SynchronizationFilter
import com.machankura.dedun.compose.extensions.toChatListTimestampString
import com.machankura.dedun.compose.nostr.Nip17Filters
import com.machankura.dedun.compose.nostr.Relays
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.ui.view.state.ChatRoomListUIState
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.marmot.mip03GroupMessages.GroupEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import com.machankura.dedun.compose.ui.theme.spacing
import bennu.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import bennu.composeapp.generated.resources.loading
import bennu.composeapp.generated.resources.no_messages_go_to_a_profile_and_send_them_a
import bennu.composeapp.generated.resources.something_went_wrong
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState

class ChatRoomListViewModel(
    initialChatRoomListUIState: ChatRoomListUIState,
    val publicKey: String,
    val nostrRepository: NostrRepository,
    val chatRepository: ChatRepository
): ViewModel() {
    val logger = Logger.withTag(TAG)
    var chatRoomListUIState: ChatRoomListUIState by mutableStateOf(initialChatRoomListUIState)
        private set

    fun initiate() {
        logger.d("init")
        observeChatRoomFeed()
    }

    fun observeChatRoomFeed() {
        logger.d("observeChatRoomFeed")
        viewModelScope.launch(Dispatchers.IO) {
            chatRepository.observeChatRoomListByPublicKey(
                publicKey
            ).collect { chatRooms ->
                logger.d("ChatRoomList: $chatRooms")
                chatRoomListUIState = ChatRoomListUIState.Loaded(
                    chatRoomList = chatRooms
                )
            }
        }
    }

    /**
     * Reconciles this account's chat history against the relays: gift wraps addressed to us,
     * and group events for every group we are in.
     *
     * No longer called on open. LiveSubscriptionManager holds both of those subscriptions
     * open for as long as the app is in the foreground and runs exactly this reconciliation
     * when it returns from the background, so opening the list is no longer a reason to ask.
     * This stays for an explicit user-initiated refresh — the one case the live tier does not
     * answer, because it is the user saying they believe something is missing.
     */
    fun scheduleSynchronization() {
        logger.d("scheduleSynchronization")
        viewModelScope.launch(Dispatchers.IO) {
            // Sync Notifications... might want to also run this in the background
            val chatRequestFilter = Nip17Filters.inbox(publicKey)
            nostrRepository.queueNegentropySynchronizeRequest(
                Relays.DefaultDMRelayList.shuffled().map { normalizedRelayUrl ->
                    NegentropySynchronizeRequest(
                        id = NegentropySynchronizeRequest.computeId(
                            relayURL = normalizedRelayUrl.url,
                            synchronizationFilter = chatRequestFilter
                        ),
                        purpose = "chat",
                        synchronizationFilter = chatRequestFilter,
                        relayURL = normalizedRelayUrl.url,
                        level = 0
                    )
                }
            )

            // Get/observe groupIds for publicKey
            val chatRooms = chatRepository.getChatRoomListByPublicKey(publicKey)
            // The addresses the rooms' traffic travels under, not their ids: a relay
            // indexes the `h` tag, and on the current profile that is the routing id.
            val relayChatRoomMaps = Relays.DefaultDMRelayList.map { dmRelay ->
                dmRelay.url to chatRooms.flatMap { localChatRoom ->
                    localChatRoom.routingIds
                }
            }

            val negentropySyncRequests = relayChatRoomMaps.map { relayChatRoomMap ->
                val mlsGroupMessageFilter = SynchronizationFilter(
                    kinds = arrayOf(GroupEvent.KIND),
                    tags = mapOf("h" to relayChatRoomMap.second),
                )
                logger.d("mlsGroupMessageFilter: $mlsGroupMessageFilter")

                NegentropySynchronizeRequest(
                    id = NegentropySynchronizeRequest.computeId(
                        relayURL = relayChatRoomMap.first,
                        synchronizationFilter = mlsGroupMessageFilter
                    ),
                    purpose = "mlsMessages",
                    synchronizationFilter = mlsGroupMessageFilter,
                    relayURL = relayChatRoomMap.first,
                    level = 0
                )
            }

            logger.d("negentropySyncRequests: ${negentropySyncRequests.map { it.synchronizationFilter }}")
            nostrRepository.queueNegentropySynchronizeRequest(
                negentropySyncRequests
            )
        }
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    fun RenderFeed(
        onNavigateToDirectMessageDetail: (com.machankura.dedun.compose.ui.composable.navigation.routes.Route) -> Unit
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (val chatRoomListUIState = chatRoomListUIState) {
                ChatRoomListUIState.Error -> {
                    ErrorState()
                }
                is ChatRoomListUIState.Loaded -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125)
                    ) {

                        if (chatRoomListUIState.chatRoomList.isEmpty()) {
                            Text(
                                text = stringResource(Res.string.no_messages_go_to_a_profile_and_send_them_a)
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125)
                            ) {
                                items(
                                    items = chatRoomListUIState.chatRoomList,
                                    key = { it.chatRoom.id }
                                ) { localChatRoom ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = {
                                            onNavigateToDirectMessageDetail.invoke(
                                                com.machankura.dedun.compose.ui.composable.navigation.routes.ChatRoomMessagingRoute(
                                                    activeUserPublicKey = publicKey,
                                                    chatRoomId = localChatRoom.chatRoom.id,
                                                    relayHint = localChatRoom.localParticipants.firstOrNull { it.participant.participantPublicKey != localChatRoom.chatRoom.userPublicKey }?.participant?.relayHint
                                                )
                                            )
                                        }
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.space250),
                                            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // Both lines are clipped to one, so the name
                                            // and the preview keep their width whatever
                                            // was said -- a long message must not push
                                            // the clock off the row.
                                            Column(
                                                modifier = Modifier.weight(1f),
                                                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)
                                            ) {
                                                localChatRoom.RenderChatRoomTitleText()
                                                localChatRoom.RenderChatRoomLastMessageText()
                                            }

                                            // Absent rather than blank for a room with
                                            // nothing in it: there is no time to show,
                                            // and the room's own creation -- which is
                                            // what it sorts on -- is not one the user
                                            // has any reason to read here.
                                            localChatRoom.lastChatMessage?.let { lastChatMessage ->
                                                Text(
                                                    text = lastChatMessage.createdAt.toChatListTimestampString(),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                ChatRoomListUIState.Loading -> {
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

    companion object {
        const val TAG = "ChatRoomListViewModel"

        fun factory(
            initialChatRoomListUIState: ChatRoomListUIState = ChatRoomListUIState.Loading,
            publicKey: String,
            nostrRepository: NostrRepository,
            chatRepository: ChatRepository
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ChatRoomListViewModel(
                    initialChatRoomListUIState = initialChatRoomListUIState,
                    publicKey = publicKey,
                    nostrRepository = nostrRepository,
                    chatRepository = chatRepository
                )
            }
        }
    }
}