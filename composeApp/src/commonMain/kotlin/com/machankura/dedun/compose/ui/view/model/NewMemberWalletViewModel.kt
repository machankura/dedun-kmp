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
import com.machankura.dedun.compose.managers.MemberWalletManager
import com.machankura.dedun.compose.repository.MemberWalletRepository
import com.machankura.dedun.compose.ui.view.state.GroupChoice
import com.machankura.dedun.compose.ui.view.state.NewMemberWalletUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Asking a group to guard a wallet only this member can spend from.
 *
 * The screen offers nothing that would be refused. Every refusal is read before
 * the button is drawn and again inside `MemberWalletManager.request`, the way
 * the subgroup picker refuses: the screen keeps a member out of a dead end, the
 * manager keeps a bug out of the room.
 */
class NewMemberWalletViewModel(
    val activeUserPublicKey: HexKey,
    private val chatRoomId: String?,
    private val chain: Chain,
    initialUIState: NewMemberWalletUIState,
    private val memberWalletRepository: MemberWalletRepository,
) : ViewModel() {
    private val logger = Logger.withTag(TAG)

    var uiState: NewMemberWalletUIState by mutableStateOf(initialUIState)
        private set

    val label: MutableState<String> = mutableStateOf("")

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            uiState = when (val chosen = chatRoomId) {
                null -> NewMemberWalletUIState.Picking(
                    memberWalletRepository.groupsOf(activeUserPublicKey)
                        .map { GroupChoice(it, refusal(it.chatRoom.id)) }
                        // A group that has never opened to its members' wallets, or
                        // has closed, is not a choice, so it is not on the list. A
                        // group whose key -- and so whose policy -- this device has
                        // not been told yet stays, with the button that asks: it may
                        // well be open. docs/member-wallet-delay.md.
                        .filter { it.refusal !in NOT_A_CHOICE }
                )
                else -> choose(chosen) ?: NewMemberWalletUIState.Error("Couldn't find the group")
            }
        }
    }

    fun pick(chatRoomId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            uiState = choose(chatRoomId) ?: NewMemberWalletUIState.Error("Couldn't find the group")
        }
    }

    private suspend fun choose(chatRoomId: String): NewMemberWalletUIState.Chosen? {
        val room = memberWalletRepository.groupsOf(activeUserPublicKey).firstOrNull { it.chatRoom.id == chatRoomId } ?: return null
        return NewMemberWalletUIState.Chosen(
            room = room,
            refusal = refusal(chatRoomId),
            reach = memberWalletRepository.reachOf(chatRoomId),
            delays = memberWalletRepository.delaysOf(chatRoomId),
        )
    }

    private suspend fun refusal(chatRoomId: String): MemberWalletManager.Refusal? =
        memberWalletRepository.refusal(chatRoomId, chain)

    fun askForGroupKey() {
        val chosen = uiState as? NewMemberWalletUIState.Chosen ?: return
        viewModelScope.launch(Dispatchers.IO) {
            memberWalletRepository.askForGroupKey(chosen.room.chatRoom.id)
            uiState = chosen.copy(askedForKey = true)
        }
    }

    fun setLabel(value: String) {
        label.value = value.take(LABEL_LENGTH)
    }

    /** Asks the chosen group; [onAsked] gets the group's name, [onFailure] runs when it could not. */
    fun ask(onAsked: () -> Unit, onFailure: () -> Unit) {
        val chosen = uiState as? NewMemberWalletUIState.Chosen ?: return
        if (chosen.refusal != null || isActionPending.value) return
        isActionPending.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val asked = runCatching {
                memberWalletRepository.request(chosen.room.chatRoom.id, chain, label.value.takeIf { it.isNotBlank() })
            }.onFailure { logger.e("Couldn't ask ${chosen.room.chatRoom.id} to guard a wallet", it) }.isSuccess
            withContext(Dispatchers.Main) {
                isActionPending.value = false
                if (asked) onAsked() else onFailure()
            }
        }
    }

    companion object {
        /** Refusals that say a group is not guarding members' wallets, rather than that this device cannot ask yet. */
        private val NOT_A_CHOICE = setOf(MemberWalletManager.Refusal.NOT_OFFERED, MemberWalletManager.Refusal.CLOSED)

        private const val TAG = "NewMemberWalletViewModel"

        /** The longest label a request carries -- `MemberWalletRequestEvent.MAX_LABEL_LENGTH`. */
        const val LABEL_LENGTH = 64

        fun factory(
            activeUserPublicKey: HexKey,
            chatRoomId: String?,
            chain: Chain,
            initialUIState: NewMemberWalletUIState = NewMemberWalletUIState.Loading,
            memberWalletRepository: MemberWalletRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                NewMemberWalletViewModel(activeUserPublicKey, chatRoomId, chain, initialUIState, memberWalletRepository)
            }
        }
    }
}
