package com.machankura.dedun.compose.ui.view.model

import com.machankura.dedun.compose.wallet.GroupLightningRollout
import fr.acinq.phoenix.managers.NodeParamsManager
import com.machankura.dedun.compose.nostr.MarmotProfile
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.ui.composable.navigation.routes.HomeRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.view.state.ChatRoomDetailUIState
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import com.machankura.dedun.compose.database.model.types.MarmotReindexReport
import com.machankura.dedun.compose.managers.MemberWalletManager
import com.machankura.dedun.compose.repository.MemberWalletRepository
import com.vitorpamplona.quartz.nip01Core.core.Event

class ChatRoomDetailViewModel(
    val chatRoomId: String,
    val activeUserPublicKey: HexKey,
    val relayHint: String?,
    initialChatRoomDetailUIState: ChatRoomDetailUIState,
    val nostrRepository: NostrRepository,
    val chatRepository: ChatRepository,
    /** The member wallets this group guards -- `docs/member-guarded-wallets.md`. The no-op lists none. */
    val memberWalletRepository: MemberWalletRepository = MemberWalletRepository.NO_OP,
): ViewModel() {

    var chatRoomDetailUIState: ChatRoomDetailUIState by mutableStateOf(initialChatRoomDetailUIState)
        private set

    /**
     * Where the room's replay has got to, for the button that started it.
     *
     * Held here rather than in [ChatRoomDetailUIState] because a replay does not
     * change what the screen is showing -- the message list is observed elsewhere
     * and updates itself -- it only changes what the button has to say for itself.
     */
    var reindexState: ReindexState by mutableStateOf(ReindexState.Idle)
        private set

    sealed interface ReindexState {
        data object Idle : ReindexState

        data object Running : ReindexState

        data class Done(val report: MarmotReindexReport) : ReindexState

        data class Failed(val message: String) : ReindexState
    }



    private val logger = Logger.withTag(TAG)

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    fun initiateChatRoomDetail() {
        logger.d("compressed (most likely chat room): $chatRoomId")
        // Get ChatRoomById
        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)

            logger.d("localChatRoom: $localChatRoom")
            chatRoomDetailUIState = if (localChatRoom == null) {
                // Initiate new chat... and navigate to new chat...
                ChatRoomDetailUIState.Error("Missing chat room")
            } else {
                // One read of the capability for the two things that need it. It
                // walks the room's ceremonies looking for a share, so asking twice
                // would do the same walk twice for one answer.
                val canSign = localChatRoom.chatRoom.mlsGroupState != null &&
                    chatRepository.canSign(chatRoomId)

                // Governance is the current profile's: a leaf the group's admin policy
                // names, in a room still open. Read off the stored state once.
                val group = localChatRoom.chatRoom.takeIf { it.marmotProfile() == MarmotProfile.CURRENT }?.toMlsGroup()
                val isDisbanded = group?.currentGroupState()?.isDisbanded == true
                val canGovern = group != null && group.isLocalAdmin() && !isDisbanded && localChatRoom.chatRoom.leftGroupAt == null

                // A direct chat is a Marmot room of two with no key, and not a group
                // that guards anybody's wallet: no section, no button.
                // docs/member-wallet-delay.md.
                val couldGuard = memberWalletRepository.couldGuard(chatRoomId)

                ChatRoomDetailUIState.Loaded(
                    localChatRoom = localChatRoom,
                    groupKeyState = chatRepository.groupKeyState(chatRoomId),
                    signedGroupKeyStateEvent = chatRepository.signedGroupKeyStateEvent(chatRoomId),
                    subgroups = chatRepository.subgroupsOf(chatRoomId),
                    parentChatRoomId = chatRepository.parentOf(chatRoomId),
                    walletPolicy = chatRepository.walletPolicyOf(chatRoomId),
                    guardianDelay = chatRepository.guardiansOf(chatRoomId)?.firstOrNull()?.delay,
                    groupWallet = chatRepository.groupWallet(chatRoomId, NodeParamsManager.chain),
                    lightningParticipants = if (GroupLightningRollout.ENABLED) chatRepository.lightningParticipants(chatRoomId, GroupLightningRollout.NETWORK) else emptySet(),
                    canAddSubgroup = canSign,
                    canGovern = canGovern,
                    isDisbanded = isDisbanded,
                    memberWallets = memberWalletRepository.memberWalletsOf(chatRoomId),
                    // Offered wherever the new-wallet screen could get somewhere:
                    // a group, a profile that signs, a network it is open on. A key
                    // this device does not know yet is that screen's to ask for.
                    canAskForMemberWallet = !isDisbanded && couldGuard && memberWalletRepository.refusal(chatRoomId, NodeParamsManager.chain).let {
                        it == null || it == MemberWalletManager.Refusal.KEY_UNKNOWN
                    },
                    canSponsor = canSign,
                    memberWalletPolicy = memberWalletRepository.policyOf(chatRoomId),
                    // Everybody in a Marmot group sees whether it guards members'
                    // wallets; only a signer is offered to change it.
                    showsMemberWalletPolicy = !isDisbanded && couldGuard,
                )
            }
        }
    }

    /**
     * Puts a member's request for a guarded wallet to the group, as this device:
     * a signing session over the certificate it asks for, which proposing counts
     * as approving. [onSuccess] gets the session, for the screen to move onto.
     */
    fun putToGroup(request: Event, onSuccess: (sessionId: String) -> Unit, onFailure: () -> Unit) {
        if (isActionPending.value) return
        isActionPending.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val session = runCatching { memberWalletRepository.putToGroup(chatRoomId, request) }
                .onFailure { logger.e("Couldn't put a member's wallet to $chatRoomId", it) }
                .getOrNull()
            viewModelScope.launch(Dispatchers.Main) {
                isActionPending.value = false
                if (session != null) onSuccess(session.id) else onFailure()
            }
        }
    }

    /**
     * Removes [memberPublicKey] as an admin, and reloads the screen. The commit is
     * applied when a relay accepts it, so the member's row stays until then; the
     * line saying they were removed is there at once.
     */
    fun removeMember(localChatRoom: LocalChatRoom, memberPublicKey: HexKey, onDone: (String?) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            val failure = runCatching { chatRepository.removeMember(localChatRoom, memberPublicKey) }
                .exceptionOrNull()
            initiateChatRoomDetail()
            viewModelScope.launch(Dispatchers.Main) { onDone(failure?.message) }
        }
    }

    /** Disbands the group as an admin, then leaves the screen: there is nothing left to show. */
    fun disbandGroup(localChatRoom: LocalChatRoom, onPopBackToRoute: (Route) -> Unit, onFailed: (String?) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            val failure = runCatching { chatRepository.disbandChatRoom(localChatRoom) }.exceptionOrNull()
            viewModelScope.launch(Dispatchers.Main) {
                if (failure == null) {
                    onPopBackToRoute.invoke(HomeRoute(activeUserPublicKey = activeUserPublicKey))
                } else {
                    onFailed(failure.message)
                }
            }
        }
    }

    fun leaveGroup(
        localChatRoom: LocalChatRoom,
        onPopBackToRoute: (Route) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            chatRepository.leaveChatRoom(
                localChatRoom,
                onCompletion = {
                    viewModelScope.launch(Dispatchers.Main) {
                        onPopBackToRoute.invoke(
                            HomeRoute(
                                activeUserPublicKey = activeUserPublicKey,
                            )
                        )
                    }
                }
            )
        }
    }

    /**
     * Reads the room's stored marmot group events again.
     *
     * Nostr relays impose no ordering, so a group event can arrive before the group
     * can read it -- a message from an epoch whose commit has not landed, a commit
     * for an epoch ahead of the local one. Nothing revisits those once the gap
     * fills, which is what this is for.
     *
     * Only events with nothing to show for them are replayed, so pressing this on a
     * room where nothing went wrong changes nothing. The feed is observed, so
     * anything recovered appears in the message list without a refresh.
     */
    fun reindexMarmotGroupEvents() {
        if (reindexState is ReindexState.Running) return

        reindexState = ReindexState.Running
        viewModelScope.launch(Dispatchers.IO) {
            reindexState = try {
                ReindexState.Done(
                    chatRepository.reindexMarmotGroupEvents(
                        chatRoomId = chatRoomId,
                        userPublicKey = activeUserPublicKey,
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.e("Failed to reindex $chatRoomId", e)
                ReindexState.Failed(e.message ?: "Reindex failed")
            }
        }
    }

    fun softDeleteGroup(
        localChatRoom: LocalChatRoom,
        onPopBackToRoute: (Route) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            chatRepository.softDeleteChatRoom(
                chatRoom = localChatRoom.chatRoom,
                onCompletion = {
                    viewModelScope.launch(Dispatchers.Main) {
                        onPopBackToRoute.invoke(
                            HomeRoute(
                                activeUserPublicKey = activeUserPublicKey,
                            )
                        )
                    }
                }
            )
        }
    }


    companion object {
        private const val TAG = "ChatRoomDetailViewModel"

        fun factory(
            activeUserPublicKey: HexKey,
            chatRoomId: String,
            relayHint: String?,
            initialChatRoomDetailUIState: ChatRoomDetailUIState = ChatRoomDetailUIState.Loading,
            nostrRepository: NostrRepository,
            chatRepository: ChatRepository,
            memberWalletRepository: MemberWalletRepository = MemberWalletRepository.NO_OP,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ChatRoomDetailViewModel(
                    activeUserPublicKey = activeUserPublicKey,
                    chatRoomId = chatRoomId,
                    relayHint = relayHint,
                    initialChatRoomDetailUIState = initialChatRoomDetailUIState,
                    nostrRepository = nostrRepository,
                    chatRepository = chatRepository,
                    memberWalletRepository = memberWalletRepository,
                )
            }
        }
    }
}