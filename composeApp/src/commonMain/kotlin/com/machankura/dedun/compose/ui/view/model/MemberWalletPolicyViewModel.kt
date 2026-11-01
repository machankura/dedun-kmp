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
import com.machankura.dedun.compose.managers.GuardianDerivation
import com.machankura.dedun.compose.nostr.wallet.MemberWalletPolicyEvent
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.FrostSigningRepository
import com.machankura.dedun.compose.repository.MemberWalletRepository
import com.machankura.dedun.compose.ui.view.state.MemberWalletPolicyUIState
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Whether a group guards its members' wallets, at what delay, and closing it
 * again -- `docs/member-wallet-delay.md`, Phase 6.
 *
 * Proposes a policy the way opening a wallet proposes a statement: a signing
 * session in the group's room, which proposing counts as approving. Nothing
 * changes until a quorum signs, and every policy reaches only the wallets asked
 * for after it -- one already guarded keeps its delay, and closing never
 * releases it.
 */
class MemberWalletPolicyViewModel(
    private val activeUserPublicKey: HexKey,
    private val chatRoomId: String,
    initialUIState: MemberWalletPolicyUIState,
    private val chatRepository: ChatRepository,
    private val memberWalletRepository: MemberWalletRepository,
    private val frostSigningRepository: FrostSigningRepository,
) : ViewModel() {
    private val logger = Logger.withTag(TAG)

    var uiState: MemberWalletPolicyUIState by mutableStateOf(initialUIState)
        private set

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    /** The delay being chosen, starting from the group's own or the default. */
    val delay = GuardianDelayInput()

    /** Whether [initiate] has set [delay] from the group's policy, which it does once. */
    private var started = false

    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val room = chatRepository.getChatRoomByIdentifier(chatRoomId)
            if (room == null) {
                uiState = MemberWalletPolicyUIState.Error("Couldn't find the group")
                return@launch
            }
            val policy = memberWalletRepository.policyOf(chatRoomId)
            val canSign = room.chatRoom.mlsGroupState != null && chatRepository.canSign(chatRoomId)
            if (!started) {
                started = true
                delay.reset((policy as? MemberWalletPolicyEvent.Policy.Open)?.delay ?: GuardianDerivation.DEFAULT_DELAY)
            }
            uiState = MemberWalletPolicyUIState.Loaded(room, policy, canSign)
        }
    }

    /**
     * Why opening at the chosen delay cannot be proposed, or null when it can:
     * a delay out of range, or the delay the group already has -- a quorum
     * asked to restate itself is asked for nothing.
     */
    fun openProblem(): String? {
        delay.problem()?.let { return it }
        val loaded = uiState as? MemberWalletPolicyUIState.Loaded ?: return "Still loading"
        if (!loaded.canSign) return "This device can't put a change to the group."
        val current = (loaded.policy as? MemberWalletPolicyEvent.Policy.Open)?.delay
        if (current == delay.delay()) return "That is already this group's delay."
        return null
    }

    /** Whether closing can be proposed: only a group that is open. */
    fun canClose(): Boolean = (uiState as? MemberWalletPolicyUIState.Loaded)?.let { it.isOpen && it.canSign } == true

    /** Asks the group to guard members' wallets at the chosen delay. */
    fun proposeOpen(onSuccess: (sessionId: String) -> Unit, onFailure: () -> Unit) {
        if (openProblem() != null) return
        propose(MemberWalletPolicyEvent.open(delay.delay() ?: return), onSuccess, onFailure)
    }

    /** Asks the group to stop guarding new members' wallets. */
    fun proposeClose(onSuccess: (sessionId: String) -> Unit, onFailure: () -> Unit) {
        if (!canClose()) return
        propose(MemberWalletPolicyEvent.closed(), onSuccess, onFailure)
    }

    private fun propose(template: EventTemplate<*>, onSuccess: (sessionId: String) -> Unit, onFailure: () -> Unit) {
        val loaded = uiState as? MemberWalletPolicyUIState.Loaded ?: return
        if (isActionPending.value) return
        isActionPending.value = true

        viewModelScope.launch(Dispatchers.IO) {
            val session = runCatching {
                frostSigningRepository.proposeSigning(
                    localChatRoom = loaded.localChatRoom,
                    userPublicKey = activeUserPublicKey,
                    kind = template.kind,
                    tags = template.tags,
                    content = template.content,
                )
            }.onFailure { logger.e("Failed to propose a member-wallet policy for $chatRoomId", it) }.getOrNull()

            withContext(Dispatchers.Main) {
                isActionPending.value = false
                if (session != null) onSuccess(session.id) else onFailure()
            }
        }
    }

    companion object {
        private const val TAG = "MemberWalletPolicyViewModel"

        fun factory(
            activeUserPublicKey: HexKey,
            chatRoomId: String,
            initialUIState: MemberWalletPolicyUIState = MemberWalletPolicyUIState.Loading,
            chatRepository: ChatRepository,
            memberWalletRepository: MemberWalletRepository,
            frostSigningRepository: FrostSigningRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MemberWalletPolicyViewModel(activeUserPublicKey, chatRoomId, initialUIState, chatRepository, memberWalletRepository, frostSigningRepository)
            }
        }
    }
}
