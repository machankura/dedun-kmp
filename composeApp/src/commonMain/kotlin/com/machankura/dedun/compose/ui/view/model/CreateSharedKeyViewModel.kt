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
import com.machankura.dedun.compose.managers.Quorum
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.managers.ChillDkgRitualManager
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.DkgRepository
import com.machankura.dedun.compose.ui.view.state.CreateSharedKeyUIState
import com.machankura.dedun.compose.ui.view.state.CreateSharedKeyUIState.Refused.Reason

/**
 * Starts a group's key ceremony in a NIP-17 room: the room's first group, or
 * another beside the ones its members have already made there
 * (docs/nip17-multiple-ceremonies.md).
 *
 * Everything about *starting* that the ceremony screen used to carry: the
 * quorum, the name, the size check, and the proposal. The ceremony screen only
 * shows one now, so nothing it draws can be mistaken for a way to open another.
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
    /** The failed ceremony this is a second try at, if it is one. See `CreateSharedKeyRoute.retrying`. */
    val retrying: String? = null,
) : ViewModel() {

    var uiState: CreateSharedKeyUIState by mutableStateOf(initialUIState)
        private set

    private val logger = Logger.withTag(TAG)

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    /**
     * The quorum the ceremony will generate a key for. Pre-filled with the same
     * majority default the group-creation screen offers.
     */
    val threshold: MutableState<Int> = mutableStateOf(Quorum.MINIMUM)

    /**
     * What the group being made is called, and so the room its key makes. Starts as
     * the room's name for the room's first group -- the room was named for it -- and
     * empty for any after it, which have to be named (D2).
     */
    val name: MutableState<String> = mutableStateOf("")

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

            // The groups the room's members have already made here, which in a
            // NIP-17 room is every ceremony it holds of its own: subgroups run in
            // their parent's Marmot room. None of them is a reason not to make
            // another; they are what the screen says these people already have, and
            // why the next one needs a name of its own.
            val groups = dkgRepository.observeOwnSessionsForChatRoom(chatRoomId).first()

            // A second try starts where the first left off: its name and quorum.
            val failed = retrying
                ?.let { dkgRepository.observeSessionById(it).first() }
                ?.takeIf { it.chatRoomId == chatRoomId && it.parentChatRoomId == null }

            val participantCount = ChillDkgRitualManager.memberPublicKeys(localChatRoom).size
            threshold.value = (failed?.threshold ?: Quorum.default(participantCount))
                .coerceIn(quorumRange(participantCount))

            name.value = failed?.subject
                ?: localChatRoom.chatRoom.subject?.takeIf { groups.isEmpty() }
                ?: ""

            uiState = CreateSharedKeyUIState.Loaded(
                localChatRoom = localChatRoom,
                participantCount = participantCount,
                groups = groups,
            )
        }
    }

    fun quorumRange(): IntRange {
        val loaded = uiState as? CreateSharedKeyUIState.Loaded
            ?: return Quorum.range(Quorum.MINIMUM)

        return quorumRange(loaded.participantCount)
    }

    // Below the minimum the range runs backwards and is empty, which `coerceIn`
    // rejects outright. There is no ceremony to run at that size either.
    private fun quorumRange(participantCount: Int): IntRange =
        Quorum.range(maxOf(participantCount, Quorum.MINIMUM))

    fun setThreshold(value: Int) {
        if (isActionPending.value) return

        threshold.value = value.coerceIn(quorumRange())
    }

    fun setName(value: String) {
        if (isActionPending.value) return

        name.value = value
    }

    /**
     * Whether the ceremony can be opened as filled in: always for the room's first
     * group, which the room names if nobody does, and only once named for any
     * after it.
     */
    fun canCreate(): Boolean {
        val loaded = uiState as? CreateSharedKeyUIState.Loaded ?: return false
        return !loaded.needsName || name.value.isNotBlank()
    }

    /**
     * Opens the ceremony, making this device its coordinator, and hands over to
     * the screen that shows it.
     */
    fun create(onCreated: (dkgSessionId: String) -> Unit) {
        if (isActionPending.value) return

        val loaded = uiState as? CreateSharedKeyUIState.Loaded ?: return
        if (!canCreate()) return

        val nostrPrivateKey = activeIdentityStateFlow.value?.nostrPrivateKey
        if (nostrPrivateKey == null) {
            uiState = CreateSharedKeyUIState.Error(CreateSharedKeyUIState.Error.Reason.NO_KEYS)
            return
        }

        isActionPending.value = true

        viewModelScope.launch(Dispatchers.IO) {
            // A ceremony still running under this name comes back here rather than
            // a second one -- `proposeRitual` folds a repeated ask into it -- and the
            // screen hands over to it the same way.
            val session = dkgRepository.proposeRitual(
                localChatRoom = loaded.localChatRoom,
                userPublicKey = activeUserPublicKey,
                nostrPrivateKey = nostrPrivateKey.value.toByteArray(),
                threshold = threshold.value,
                subject = name.value.trim().takeIf { it.isNotEmpty() },
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
            retrying: String? = null,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                CreateSharedKeyViewModel(
                    chatRoomId = chatRoomId,
                    activeUserPublicKey = activeUserPublicKey,
                    initialUIState = initialUIState,
                    activeIdentityStateFlow = activeIdentityStateFlow,
                    chatRepository = chatRepository,
                    dkgRepository = dkgRepository,
                    retrying = retrying,
                )
            }
        }
    }
}
