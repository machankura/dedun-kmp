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
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.DepositRepository
import com.machankura.dedun.compose.ui.view.state.DepositsUIState
import fr.acinq.bitcoin.Chain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch

/**
 * Every deposit into the group and whose it is, with each member's tally: what the group page's
 * section opens. `docs/member-deposits.md`, Phase 8.
 */
class DepositsViewModel(
    val chatRoomId: String,
    val chain: Chain,
    initialUIState: DepositsUIState,
    private val chatRepository: ChatRepository,
    private val depositRepository: DepositRepository,
) : ViewModel() {
    var uiState: DepositsUIState by mutableStateOf(initialUIState)
        private set

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)
                ?: return@launch run { uiState = DepositsUIState.Error("Couldn't find the group") }
            val names = localChatRoom.localParticipants.associate { participant ->
                participant.participant.participantPublicKey.lowercase() to
                    (participant.profile?.humanReadableNameOrPubkey() ?: participant.participant.participantPublicKey.take(8))
            }
            uiState = DepositsUIState.Loaded(
                localChatRoom = localChatRoom,
                deposits = depositRepository.deposits(chatRoomId, chain),
                tallies = depositRepository.tallies(chatRoomId, chain),
                names = names,
                canSign = localChatRoom.chatRoom.mlsGroupState != null && chatRepository.canSign(chatRoomId),
            )
        }
    }

    companion object {
        fun factory(
            chatRoomId: String,
            chain: Chain,
            initialUIState: DepositsUIState = DepositsUIState.Loading,
            chatRepository: ChatRepository,
            depositRepository: DepositRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { DepositsViewModel(chatRoomId, chain, initialUIState, chatRepository, depositRepository) }
        }
    }
}
