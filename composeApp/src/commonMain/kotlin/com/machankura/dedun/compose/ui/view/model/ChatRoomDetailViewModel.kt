package com.machankura.dedun.compose.ui.view.model

import com.machankura.dedun.compose.repository.DepositRepository
import com.machankura.dedun.compose.wallet.GroupLightningRollout
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthRollout
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
import co.touchlab.kermit.Logger
import com.vitorpamplona.marmot.groups.currentGroupState
import com.vitorpamplona.marmot.groups.isLocalAdmin
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.MarmotReindexReport
import com.machankura.dedun.compose.managers.MemberWalletManager
import com.machankura.dedun.compose.managers.isGoverned
import com.machankura.dedun.compose.repository.MemberWalletRepository
import com.machankura.dedun.compose.repository.PayoutDestinationRepository
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestination
import com.machankura.dedun.compose.wallet.PayoutAddress
import com.machankura.dedun.compose.wallet.UnusedAddressCheck
import com.machankura.dedun.compose.network.LightningAddressResolver
import fr.acinq.lightning.MilliSatoshi
import kotlinx.coroutines.withContext
import com.machankura.dedun.compose.database.model.types.ShareStanding
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.ui.composable.navigation.routes.HomeRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.view.state.ChatRoomDetailUIState
import com.machankura.dedun.compose.ui.view.state.SharesSummary

class ChatRoomDetailViewModel(
    val chatRoomId: String,
    val activeUserPublicKey: HexKey,
    val relayHint: String?,
    initialChatRoomDetailUIState: ChatRoomDetailUIState,
    val nostrRepository: NostrRepository,
    val chatRepository: ChatRepository,
    /** The member wallets this group guards -- `docs/member-guarded-wallets.md`. The no-op lists none. */
    val memberWalletRepository: MemberWalletRepository = MemberWalletRepository.NO_OP,
    /** Where this group pays its members -- `docs/group-payout-destination.md`. The no-op has no section. */
    val payoutDestinationRepository: PayoutDestinationRepository = PayoutDestinationRepository.NO_OP,
    /** Who deposits with the group -- `docs/member-deposits.md`. The no-op opens nobody. */
    val depositRepository: DepositRepository = DepositRepository.NO_OP,
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
                // A room whose changes need the group's signature takes none from this
                // build: a bare commit would fork it. docs/giza-into-dedun.md, Phase 1.
                val isGoverned = group?.isGoverned() == true
                val canGovern = group != null && group.isLocalAdmin() && !isDisbanded && !isGoverned && localChatRoom.chatRoom.leftGroupAt == null

                // A direct chat is a Marmot room of two with no key, and not a group
                // that guards anybody's wallet: no section, no button.
                // docs/member-wallet-delay.md.
                val couldGuard = memberWalletRepository.couldGuard(chatRoomId)

                // docs/group-payout-destination.md: a group still going has a Payouts
                // section, and every member row says where that member is paid.
                val payouts = payoutDestinationRepository.standing(chatRoomId, NodeParamsManager.chain)?.takeIf { it.isGroup }

                ChatRoomDetailUIState.Loaded(
                    localChatRoom = localChatRoom,
                    groupKeyState = chatRepository.groupKeyState(chatRoomId),
                    signedGroupKeyStateEvent = chatRepository.signedGroupKeyStateEvent(chatRoomId),
                    subgroups = chatRepository.subgroupsOf(chatRoomId),
                    parentChatRoomId = chatRepository.parentOf(chatRoomId),
                    walletPolicy = chatRepository.walletPolicyOf(chatRoomId),
                    guardianDelay = chatRepository.guardiansOf(chatRoomId)?.firstOrNull()?.delay,
                    groupWallet = chatRepository.groupWallet(chatRoomId, NodeParamsManager.chain),
                    // One query per network a Wavelength wallet is offered on, which the
                    // row for that network needs before it can say whether it waits. A
                    // read that fails is logged and comes back null, which leaves the row
                    // disabled with its reason -- the safe direction for a gate.
                    wavelengthOnchainChains = if (GroupWavelengthRollout.ENABLED) {
                        GroupWavelengthRollout.NETWORKS.filter { chatRepository.groupWallet(chatRoomId, it) != null }.toSet()
                    } else emptySet(),
                    lightningParticipants = if (GroupLightningRollout.ENABLED) chatRepository.lightningParticipants(chatRoomId, GroupLightningRollout.NETWORK) else emptySet(),
                    wavelengthParticipants = chatRepository.wavelengthParticipants(chatRoomId),
                    canAddSubgroup = canSign,
                    canGovern = canGovern,
                    isGoverned = isGoverned,
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
                    payouts = payouts,
                    payoutDestinations = if (payouts != null) payoutDestinationRepository.destinationsIn(chatRoomId, NodeParamsManager.chain) else emptyMap(),
                    tallies = if (payouts != null) depositRepository.tallies(chatRoomId, NodeParamsManager.chain) else null,
                    openableDepositors = if (payouts != null && canSign) {
                        localChatRoom.localParticipants.map { it.participant.participantPublicKey.lowercase() }
                            .filter { depositRepository.canBeOpened(chatRoomId, NodeParamsManager.chain, it) }
                            .toSet()
                    } else emptySet(),
                    shares = chatRepository.sharesSummary(localChatRoom),
                )
            }
            localChatRoom?.let { observeShares(it.chatRoom.id) }
        }
    }

    private var sharesObserver: Job? = null

    /**
     * Keeps the Shares section following the group: a key state signed, a
     * proposal declined, a refresh moved on by another device, all while this
     * screen is open (docs/rotation-waits-for-key-state.md, Phase 3). Only the
     * section is read again -- the rest of the screen has its own paths.
     */
    @OptIn(FlowPreview::class)
    private fun observeShares(roomId: String) {
        if (sharesObserver != null) return
        sharesObserver = viewModelScope.launch(Dispatchers.IO) {
            chatRepository.observeSharesInputs(roomId)
                // A signing session writes a row per message; read once it settles.
                .debounce(SHARES_SETTLE_MILLIS)
                .collect {
                    val loaded = chatRoomDetailUIState as? ChatRoomDetailUIState.Loaded ?: return@collect
                    val shares = chatRepository.sharesSummary(loaded.localChatRoom)
                    (chatRoomDetailUIState as? ChatRoomDetailUIState.Loaded)?.let { chatRoomDetailUIState = it.copy(shares = shares) }
                }
        }
    }

    /**
     * Asks the group to open [member]'s deposits, as this device, which holds a share: a signing
     * session over their depositor statement. `docs/member-deposits.md`, Phase 5.
     */
    fun openDeposits(member: HexKey, onSuccess: (sessionId: String) -> Unit, onFailure: () -> Unit) {
        val loaded = chatRoomDetailUIState as? ChatRoomDetailUIState.Loaded ?: return
        if (isActionPending.value) return
        isActionPending.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val session = runCatching { depositRepository.open(loaded.localChatRoom, NodeParamsManager.chain, member) }.getOrNull()
            withContext(Dispatchers.Main) {
                isActionPending.value = false
                if (session != null) onSuccess(session.id) else onFailure()
            }
        }
    }

    /**
     * Whether [destination]'s onchain address has been paid since it was set, asked when its
     * sheet opens -- an address the group has paid once is used, and the payer should ask for a
     * new one. `docs/group-payout-destination.md`, Phase 5.
     */
    fun usageOf(destination: GroupPayoutDestination, onResult: (UnusedAddressCheck.Usage) -> Unit) {
        val script = destination.onchainAddress?.let { PayoutAddress.parseOrNull(destination.chain, it) }?.script ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val usage = payoutDestinationRepository.usageOf(chatRoomId, destination.chain, script)
            withContext(Dispatchers.Main) { onResult(usage) }
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
    fun removeMember(localChatRoom: LocalChatRoom, memberPublicKey: HexKey, onDone: (failure: String?, rotationHeld: Boolean) -> Unit = { _, _ -> }) {
        viewModelScope.launch(Dispatchers.IO) {
            // Asked before the removal, while they are still in the room's roster.
            val heldShare = chatRepository.holdsShare(localChatRoom, memberPublicKey)
            // Dedun's: a rotation renews the onchain key's shares and cannot touch the
            // Lightning wallet's Iceberg key, which has no refresh. The offer says so
            // for a member who holds a share of that too. docs/giza-into-dedun.md, Phase 7.
            val heldLightningShare = GroupLightningRollout.ENABLED &&
                memberPublicKey.lowercase() in chatRepository.lightningParticipants(localChatRoom.chatRoom.id, GroupLightningRollout.NETWORK)
            val failure = runCatching { chatRepository.removeMember(localChatRoom, memberPublicKey) }
                .exceptionOrNull()
            // Offered only when a rotation could start now; while the last one is
            // unfinished the offer would only be refused
            // (docs/rotation-waits-for-key-state.md, Phase 2).
            val rotation = if (failure == null && heldShare) chatRepository.sharesSummary(localChatRoom)?.rotation else null
            initiateChatRoomDetail()
            viewModelScope.launch(Dispatchers.Main) {
                onDone(failure?.message, rotation is SharesSummary.Rotation.Held)
                // The removal takes them out of the room, not out of the key: they
                // can still help sign until the shares are rotated without them.
                // A second act, offered rather than folded in -- it needs every
                // remaining holder (docs/frost-share-refresh.md, Phase 9).
                if (rotation == SharesSummary.Rotation.Available) rotationOffer = RotationOffer(localChatRoom, memberPublicKey, heldLightningShare)
            }
        }
    }

    /**
     * Runs one Shares section action, reloads the screen, and reports: null for
     * done, or why not. Every one of them is a proposal or an answer the group
     * goes on with in the background, so done means sent, not finished.
     */
    private fun sharesAction(onDone: (String?) -> Unit, action: suspend () -> String?) {
        // One at a time: the section's buttons read this, so a second tap while the
        // first is on its way does nothing rather than reporting twice.
        if (isActionPending.value) return
        isActionPending.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val failure = try {
                action()
            } finally {
                initiateChatRoomDetail()
                viewModelScope.launch(Dispatchers.Main) { isActionPending.value = false }
            }
            viewModelScope.launch(Dispatchers.Main) { onDone(failure) }
        }
    }

    fun rotateShares(localChatRoom: LocalChatRoom, onDone: (String?) -> Unit) =
        sharesAction(onDone) { chatRepository.rotateShares(localChatRoom) }

    fun approveShares(localChatRoom: LocalChatRoom, approval: SharesSummary.PendingApproval, onDone: (String?) -> Unit) =
        sharesAction(onDone) { chatRepository.approveShares(localChatRoom, approval) }

    fun proposeKeyState(localChatRoom: LocalChatRoom, onDone: (String?) -> Unit) =
        sharesAction(onDone) { chatRepository.proposeKeyState(localChatRoom) }

    fun abandonRotation(localChatRoom: LocalChatRoom, sessionId: String, onDone: (String?) -> Unit) =
        sharesAction(onDone) { chatRepository.abandonRotation(localChatRoom, sessionId) }

    fun askToBeRepaired(localChatRoom: LocalChatRoom, onDone: (String?) -> Unit) =
        sharesAction(onDone) { chatRepository.askToBeRepaired(localChatRoom) }

    /**
     * A rotation this device's owner is being asked to confirm, and who it leaves
     * out: the members stale at this generation, withheld again as stale. The
     * proposal approves the proposer, so this is where they decide who is withheld
     * (docs/rotation-waits-for-key-state.md, Phase 4).
     */
    data class RotationConfirmation(val localChatRoom: LocalChatRoom, val leftOut: List<String>)

    var rotationConfirmation: RotationConfirmation? by mutableStateOf(null)
        private set

    fun askToRotate(localChatRoom: LocalChatRoom, shares: SharesSummary) {
        rotationConfirmation = RotationConfirmation(
            localChatRoom,
            shares.members.filter { it.standing == ShareStanding.STALE }.map { it.name },
        )
    }

    fun dismissRotationConfirmation() {
        rotationConfirmation = null
    }

    /** Proposes the rotation [rotationConfirmation] asked about. */
    fun confirmRotation(confirmation: RotationConfirmation, onDone: (String?) -> Unit) {
        rotationConfirmation = null
        rotateShares(confirmation.localChatRoom, onDone)
    }

    /** A removed member who still holds a share of the room's key, for the offer to rotate without them. */
    data class RotationOffer(
        val localChatRoom: LocalChatRoom,
        val memberPublicKey: HexKey,
        /** They also hold a share of the Lightning wallet's key, which no rotation reaches. */
        val holdsLightningShare: Boolean = false,
    )

    var rotationOffer: RotationOffer? by mutableStateOf(null)
        private set

    fun dismissRotationOffer() {
        rotationOffer = null
    }

    /** Proposes the rotation [rotationOffer] offered. [onDone] gets null on success, or why it could not. */
    fun rotateSharesWithout(offer: RotationOffer, onDone: (String?) -> Unit) {
        rotationOffer = null
        viewModelScope.launch(Dispatchers.IO) {
            val failure = chatRepository.rotateSharesWithout(offer.localChatRoom, offer.memberPublicKey)
            viewModelScope.launch(Dispatchers.Main) { onDone(failure) }
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

    /**
     * Leaves the group, then leaves the screen. A refusal -- an admin, who has to
     * hand over admin first -- goes to [onFailed] in the words the refusal used, and
     * the screen stays. It used to escape the coroutine, which nobody saw and which
     * on Android is a crash. See docs/member-admin-promotion.md, Phase 10.
     */
    fun leaveGroup(
        localChatRoom: LocalChatRoom,
        onPopBackToRoute: (Route) -> Unit,
        onFailed: (String?) -> Unit = {},
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val failure = runCatching {
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
            }.exceptionOrNull()
            if (failure is CancellationException) throw failure
            if (failure != null) {
                logger.w(throwable = failure) { "Couldn't leave $chatRoomId" }
                viewModelScope.launch(Dispatchers.Main) { onFailed(failure.message) }
            }
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

        /** How long the Shares section's inputs have to be still before it is read again. */
        internal const val SHARES_SETTLE_MILLIS = 250L

        fun factory(
            activeUserPublicKey: HexKey,
            chatRoomId: String,
            relayHint: String?,
            initialChatRoomDetailUIState: ChatRoomDetailUIState = ChatRoomDetailUIState.Loading,
            nostrRepository: NostrRepository,
            chatRepository: ChatRepository,
            memberWalletRepository: MemberWalletRepository = MemberWalletRepository.NO_OP,
            payoutDestinationRepository: PayoutDestinationRepository = PayoutDestinationRepository.NO_OP,
            depositRepository: DepositRepository = DepositRepository.NO_OP,
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
                    payoutDestinationRepository = payoutDestinationRepository,
                    depositRepository = depositRepository,
                )
            }
        }
    }
}