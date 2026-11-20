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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.ui.view.state.MemberAction
import com.machankura.dedun.compose.ui.view.state.MemberActionBlocker
import com.machankura.dedun.compose.ui.view.state.MemberActionUnavailableUIState

/**
 * Works out why [action] on [memberPublicKey] is not open, through the same
 * `ChatRepository.memberActionOptions` the member row read, and keeps working it
 * out while the screen is open: a change landing, a device catching up or a
 * rotation finishing can each clear a reason, and the screen should not go on
 * explaining one that is gone. docs/member-row-actions.md, D5 and Phase 4.
 */
class MemberActionUnavailableViewModel(
    val activeUserPublicKey: HexKey,
    val chatRoomId: String,
    val memberPublicKey: HexKey,
    val action: MemberAction?,
    initialUIState: MemberActionUnavailableUIState,
    private val nostrRepository: NostrRepository,
    private val chatRepository: ChatRepository,
) : ViewModel() {

    var uiState: MemberActionUnavailableUIState by mutableStateOf(initialUIState)
        private set

    private val logger = Logger.withTag(TAG)

    private var observer: Job? = null

    /** Once; a recomposition that calls this again must not open a second collect. */
    fun initiate() {
        reload()
        observe()
    }

    fun reload() {
        viewModelScope.launch(Dispatchers.IO) {
            uiState = runCatching { load() }
                .onFailure { if (it is CancellationException) throw it else logger.w(throwable = it) { "Couldn't explain $action in $chatRoomId" } }
                .getOrElse { MemberActionUnavailableUIState.Error }
        }
    }

    private suspend fun load(): MemberActionUnavailableUIState {
        val action = action ?: return MemberActionUnavailableUIState.NoLongerApplies
        val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)
            ?: return MemberActionUnavailableUIState.NoLongerApplies
        val option = chatRepository.memberActionOptions(localChatRoom)[memberPublicKey]
            ?.firstOrNull { it.action == action }
            ?: return MemberActionUnavailableUIState.NoLongerApplies

        val memberName = nameOf(memberPublicKey)
        if (option.isAvailable) return MemberActionUnavailableUIState.NowAvailable(memberName, action)

        val named = option.blockers.flatMap { blocker ->
            when (blocker) {
                is MemberActionBlocker.NotAnAdmin -> blocker.admins
                is MemberActionBlocker.SignaturesNotRequired -> blocker.waitingOn
                is MemberActionBlocker.RotationPending -> blocker.waitingOn
                else -> emptySet()
            }
        }.toSet()
        return MemberActionUnavailableUIState.Blocked(
            memberName = memberName,
            action = action,
            blockers = option.blockers,
            names = named.associateWith { nameOf(it) },
            adminRoomId = if (MemberActionBlocker.NoAdminsInThisRoom in option.blockers) chatRepository.adminRoomOf(chatRoomId) else null,
        )
    }

    private suspend fun nameOf(publicKey: HexKey): String =
        nostrRepository.getProfileWithPublicKey(publicKey)?.humanReadableNameOrPubkey()
            ?: publicKey.take(SHORTENED_PUBLIC_KEY_LENGTH)

    /**
     * Every room row this account holds, and the inputs of the room's shares: a
     * commit landing rewrites the room's row, and a rotation or a signing session
     * writes the others. Either may clear a reason.
     */
    @OptIn(FlowPreview::class)
    private fun observe() {
        if (observer != null) return
        observer = viewModelScope.launch(Dispatchers.IO) {
            merge(
                chatRepository.observeChatRoomListByPublicKey(activeUserPublicKey).map { },
                chatRepository.observeSharesInputs(chatRoomId),
            )
                .debounce(SETTLE_MILLIS)
                .collect { reload() }
        }
    }

    companion object {
        private const val TAG = "MemberActionUnavailableViewModel"
        private const val SHORTENED_PUBLIC_KEY_LENGTH = 12
        private const val SETTLE_MILLIS = 300L

        fun factory(
            activeUserPublicKey: HexKey,
            chatRoomId: String,
            memberPublicKey: HexKey,
            action: MemberAction?,
            initialUIState: MemberActionUnavailableUIState = MemberActionUnavailableUIState.Loading,
            nostrRepository: NostrRepository,
            chatRepository: ChatRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MemberActionUnavailableViewModel(
                    activeUserPublicKey = activeUserPublicKey,
                    chatRoomId = chatRoomId,
                    memberPublicKey = memberPublicKey,
                    action = action,
                    initialUIState = initialUIState,
                    nostrRepository = nostrRepository,
                    chatRepository = chatRepository,
                )
            }
        }
    }
}
