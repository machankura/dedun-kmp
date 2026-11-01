package com.machankura.dedun.compose.ui.view.model

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.machankura.dedun.compose.database.model.NegentropySynchronizeRequest
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.types.SynchronizationFilter
import com.machankura.dedun.compose.nostr.Relays
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.ui.view.state.SearchMemberToAddToChatRoomUIState
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch

class SearchMemberToAddToChatRoomViewModel(
    val chatRoomId: String,
    val activeUserPublicKey: HexKey,
    val relayHint: String?,
    initialSearchMemberToAddToChatRoomUIState: SearchMemberToAddToChatRoomUIState,
    val nostrRepository: NostrRepository,
    val chatRepository: ChatRepository
): ViewModel() {

    var searchMemberToAddToChatRoomUIState: SearchMemberToAddToChatRoomUIState by mutableStateOf(initialSearchMemberToAddToChatRoomUIState)
        private set



    private val logger = Logger.withTag(TAG)

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    fun initiate() {
        logger.d("compressed (most likely chat room): $chatRoomId")
        // Get ChatRoomById
        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)

            searchMemberToAddToChatRoomUIState = if (localChatRoom == null) {
                // Initiate new chat... and navigate to new chat...
                SearchMemberToAddToChatRoomUIState.Error("Failed to load chat")
            } else {
                val contacts = nostrRepository.searchableProfiles(
                    excludingPublicKeys = localChatRoom.localParticipants.map { it.participant.participantPublicKey }.toTypedArray()
                )
                scheduleKeyPackageEventSynchronization(
                    profiles = contacts
                )
                SearchMemberToAddToChatRoomUIState.Loaded(
                    localChatRoom = localChatRoom,
                    contacts = contacts
                )
            }
        }
    }

    fun scheduleKeyPackageEventSynchronization(profiles: List<Profile>) {
        logger.d("scheduleKeyPackageEventSynchronization: $profiles")

        viewModelScope.launch(Dispatchers.IO) {
            // TODO: Sync from aggregated relays of profile KeyPackageRelays...
            val negentropySynchronizeRequests = Relays.DefaultDMRelayList.map { normalizedRelayUrl ->
                val synchronizationFilter = SynchronizationFilter(
                    authors = profiles.map { it.publicKey }.toTypedArray(),
                    kinds = arrayOf(
                        KeyPackageEvent.KIND
                    )
                )

                NegentropySynchronizeRequest(
                    id = NegentropySynchronizeRequest.computeId(
                        relayURL = normalizedRelayUrl.url,
                        synchronizationFilter = synchronizationFilter
                    ),
                    purpose = "key-packages",
                    synchronizationFilter = synchronizationFilter,
                    relayURL = normalizedRelayUrl.url,
                    level = 0
                )
            }
            logger.i("negentropySynchronizeRequests: $negentropySynchronizeRequests")

            nostrRepository.queueNegentropySynchronizeRequest(
                negentropySynchronizeRequests
            )
        }
    }


    companion object {
        private const val TAG = "SearchMemberToAddToChatRoomViewModel"

        fun factory(
            activeUserPublicKey: HexKey,
            chatRoomId: String,
            relayHint: String?,
            initialSearchMemberToAddToChatRoomUIState: SearchMemberToAddToChatRoomUIState = SearchMemberToAddToChatRoomUIState.Loading,
            nostrRepository: NostrRepository,
            chatRepository: ChatRepository
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SearchMemberToAddToChatRoomViewModel(
                    activeUserPublicKey = activeUserPublicKey,
                    chatRoomId = chatRoomId,
                    relayHint = relayHint,
                    initialSearchMemberToAddToChatRoomUIState = initialSearchMemberToAddToChatRoomUIState,
                    nostrRepository = nostrRepository,
                    chatRepository = chatRepository
                )
            }
        }
    }
}