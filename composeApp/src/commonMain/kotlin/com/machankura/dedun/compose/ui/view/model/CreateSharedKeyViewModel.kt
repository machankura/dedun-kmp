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
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.machankura.dedun.compose.database.model.types.ChatRoomType
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.managers.ChillDkgRitualManager
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.DkgRepository
import com.machankura.dedun.compose.ui.view.state.CreateSharedKeyUIState
import com.machankura.dedun.compose.ui.view.state.CreateSharedKeyUIState.Refused.Reason

/**
 * Starts a NIP-17 group's own key ceremony.
 *
 * Everything about *starting* that the ceremony screen used to carry: the
 * quorum, the size check, and the proposal. The ceremony screen only shows one
 * now, so nothing it draws can be mistaken for a way to open another.
 *
 * The refusals are made here rather than by hiding a button, so the screen can
 * say why, and `ChillDkgRitualManager.proposeRitual` makes the Marmot one again
 * underneath -- a screen not drawing something is not a guard.
 */
class CreateSharedKeyViewModel(
    val chatRoomId: String,
    val activeUserPublicKey: HexKey,
    initialUIState: CreateSharedKeyUIState,
    val activeIdentityStateFlow: StateFlow<Identity?>,
    val chatRepository: ChatRepository,
    val dkgRepository: DkgRepository,
) : ViewModel() {

    var uiState: CreateSharedKeyUIState by mutableStateOf(initialUIState)
        private set

    private val logger = Logger.withTag(TAG)

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    /**
     * The quorum the ceremony will generate a key for. Pre-filled with the same
     * majority default the group-creation screen offers.
     */
    val threshold: MutableState<Int> = mutableStateOf(ChatRoomType.MINIMUM_QUORUM)

    fun initiate() {
        uiState = CreateSharedKeyUIState.Loading

        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)
            if (localChatRoom == null) {
                uiState = CreateSharedKeyUIState.Error(CreateSharedKeyUIState.Error.Reason.ROOM_NOT_FOUND)
                return@launch
            }

            if (ChillDkgRitualManager.isMarmotRoom(localChatRoom)) {
                uiState = CreateSharedKeyUIState.Refused(Reason.MARMOT_ROOM)
                return@launch
            }

            if (!ChillDkgRitualManager.canRunRitual(localChatRoom)) {
                uiState = CreateSharedKeyUIState.Refused(Reason.TOO_FEW_MEMBERS)
                return@launch
            }

            // The room's own, which in a NIP-17 room is every ceremony it holds:
            // subgroups run in their parent's Marmot room. A finished one counts --
            // the group has its key, and the ceremony screen is where the next step
            // after it is offered.
            val own = dkgRepository.observeLatestSessionForChatRoom(chatRoomId, parentChatRoomId = null)
                .first()
            if (own != null && own.stage != DkgRitualStage.FAILED) {
                uiState = CreateSharedKeyUIState.Existing(own.id)
                return@launch
            }

            val participantCount = ChillDkgRitualManager.memberPublicKeys(localChatRoom).size
            threshold.value = ChatRoomType.defaultQuorum(participantCount)
                .coerceIn(quorumRange(participantCount))

            uiState = CreateSharedKeyUIState.Loaded(
                localChatRoom = localChatRoom,
                participantCount = participantCount,
            )
        }
    }

    fun quorumRange(): IntRange {
        val loaded = uiState as? CreateSharedKeyUIState.Loaded
            ?: return ChatRoomType.quorumRange(ChatRoomType.MINIMUM_QUORUM)

        return quorumRange(loaded.participantCount)
    }

    // Below the minimum the range runs backwards and is empty, which `coerceIn`
    // rejects outright. There is no ceremony to run at that size either.
    private fun quorumRange(participantCount: Int): IntRange =
        ChatRoomType.quorumRange(maxOf(participantCount, ChatRoomType.MINIMUM_QUORUM))

    fun setThreshold(value: Int) {
        if (isActionPending.value) return

        threshold.value = value.coerceIn(quorumRange())
    }

    /**
     * Opens the ceremony, making this device its coordinator, and hands over to
     * the screen that shows it.
     */
    fun create(onCreated: (dkgSessionId: String) -> Unit) {
        if (isActionPending.value) return

        val loaded = uiState as? CreateSharedKeyUIState.Loaded ?: return

        val nostrPrivateKey = activeIdentityStateFlow.value?.nostrPrivateKey
        if (nostrPrivateKey == null) {
            uiState = CreateSharedKeyUIState.Error(CreateSharedKeyUIState.Error.Reason.NO_KEYS)
            return
        }

        isActionPending.value = true

        viewModelScope.launch(Dispatchers.IO) {
            // A ceremony somebody else opened since this screen loaded comes back
            // here rather than a second one -- `proposeRitual` checks again.
            val session = dkgRepository.proposeRitual(
                localChatRoom = loaded.localChatRoom,
                userPublicKey = activeUserPublicKey,
                nostrPrivateKey = nostrPrivateKey.value.toByteArray(),
                threshold = threshold.value
            )

            isActionPending.value = false

            if (session == null) {
                logger.e("Failed to start a key ceremony in $chatRoomId")
                uiState = CreateSharedKeyUIState.Error(CreateSharedKeyUIState.Error.Reason.NOT_STARTED)
                return@launch
            }

            withContext(Dispatchers.Main) { onCreated(session.id) }
        }
    }

    companion object {
        private const val TAG = "CreateSharedKeyViewModel"

        fun factory(
            chatRoomId: String,
            activeUserPublicKey: HexKey,
            initialUIState: CreateSharedKeyUIState = CreateSharedKeyUIState.Loading,
            activeIdentityStateFlow: StateFlow<Identity?>,
            chatRepository: ChatRepository,
            dkgRepository: DkgRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                CreateSharedKeyViewModel(
                    chatRoomId = chatRoomId,
                    activeUserPublicKey = activeUserPublicKey,
                    initialUIState = initialUIState,
                    activeIdentityStateFlow = activeIdentityStateFlow,
                    chatRepository = chatRepository,
                    dkgRepository = dkgRepository,
                )
            }
        }
    }
}
