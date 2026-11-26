package com.machankura.dedun.compose.ui.view.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.machankura.dedun.compose.managers.GroupDmRelays
import com.machankura.dedun.compose.nostr.RelayUrlInput
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.GroupDmRepository
import com.machankura.dedun.compose.ui.view.state.GroupDmSetupUIState

/**
 * Asks the group to sign where it receives direct messages (D8 of
 * docs/group-threshold-ecdh.md). Proposing is the whole of it: the group opens its messages
 * when a quorum signs, not when the button is pressed.
 */
class GroupDmSetupViewModel(
    val chatRoomId: String,
    val activeUserPublicKey: HexKey,
    val chatRepository: ChatRepository,
    val groupDmRepository: GroupDmRepository,
) : ViewModel() {
    private val logger = Logger.withTag("GroupDmSetupViewModel")

    var uiState: GroupDmSetupUIState by mutableStateOf(GroupDmSetupUIState.Loading)
        private set

    var isActionPending: Boolean by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val room = chatRepository.getChatRoomByIdentifier(chatRoomId)
            uiState = if (room == null) {
                GroupDmSetupUIState.Error("Couldn't find the chat room")
            } else {
                GroupDmSetupUIState.Loaded(
                    localChatRoom = room,
                    relays = groupDmRepository.observeRelays(chatRoomId).first() ?: GroupDmRelays.defaultRelays,
                    canPropose = groupDmRepository.isCurrentAdmin(chatRoomId, activeUserPublicKey),
                )
            }
        }
    }

    /** The lines of [text] as relays, or null when any is not a public `wss://` address or there are none. */
    fun relaysOf(text: String): List<String>? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        return lines.map { RelayUrlInput.relayUrlOrNull(it)?.url ?: return null }.distinct()
    }

    fun propose(text: String, onSuccess: (sessionId: String) -> Unit, onFailure: () -> Unit) {
        val loaded = uiState as? GroupDmSetupUIState.Loaded ?: return
        val relays = relaysOf(text) ?: return onFailure()
        if (isActionPending) return
        isActionPending = true
        viewModelScope.launch(Dispatchers.IO) {
            val session = runCatching { groupDmRepository.proposeRelays(loaded.localChatRoom, activeUserPublicKey, relays) }
                .onFailure { logger.e("Couldn't propose the group's message relays", it) }
                .getOrNull()
            withContext(Dispatchers.Main) {
                isActionPending = false
                if (session != null) onSuccess(session.id) else onFailure()
            }
        }
    }

    companion object {
        fun factory(
            chatRoomId: String,
            activeUserPublicKey: HexKey,
            chatRepository: ChatRepository,
            groupDmRepository: GroupDmRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { GroupDmSetupViewModel(chatRoomId, activeUserPublicKey, chatRepository, groupDmRepository) }
        }
    }
}
