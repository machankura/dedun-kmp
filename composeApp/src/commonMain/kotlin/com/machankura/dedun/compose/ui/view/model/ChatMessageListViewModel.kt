package com.machankura.dedun.compose.ui.view.model

import com.machankura.dedun.compose.text.ProposedSpend
import com.machankura.dedun.compose.database.model.types.FrostSigningSubject
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
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
import com.machankura.dedun.compose.database.model.Participant
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.SynchronizationFilter
import com.machankura.dedun.compose.extensions.shortened
import com.machankura.dedun.compose.managers.FrostSigningManager
import com.machankura.dedun.compose.nostr.MemberProfileSync
import com.machankura.dedun.compose.nostr.Nip17Filters
import com.machankura.dedun.compose.nostr.Relays
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import com.machankura.dedun.compose.database.model.types.DkgApprovalStep
import com.machankura.dedun.compose.repository.DkgRepository
import com.machankura.dedun.compose.repository.GroupLightningRepository
import com.machankura.dedun.compose.database.model.types.IcebergApprovalStep
import com.machankura.dedun.compose.database.model.types.IcebergRitualStage
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.machankura.dedun.compose.database.model.intermdiate.LocalFrostSigningSession
import com.machankura.dedun.compose.nostr.lightning.GroupLightningWalletStatementEvent
import com.machankura.dedun.compose.wallet.GroupLightningRollout
import kotlinx.coroutines.flow.combine
import com.machankura.dedun.compose.repository.FrostSigningRepository
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.text.ProposedEvent
import com.machankura.dedun.compose.ui.view.state.ChatMessageListUIState
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip17Dm.settings.ChatMessageRelayListEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class ChatMessageListViewModel(
    initialChatMessageListUIState: ChatMessageListUIState,
    val localChatRoom: LocalChatRoom,
    val nostrRepository: NostrRepository,
    val chatRepository: ChatRepository,
    val frostSigningRepository: FrostSigningRepository,
    val dkgRepository: DkgRepository,
    val lightningRepository: GroupLightningRepository,
): ViewModel() {
    val logger = Logger.withTag(TAG)
    var chatMessageListUIState: ChatMessageListUIState by mutableStateOf(initialChatMessageListUIState)
        private set

    val isReceiverChatMessageRelayListMissing: MutableState<Boolean> = mutableStateOf(false)

    /**
     * The member the next message goes to privately, or null to send it to the room.
     *
     * Held here rather than on the composer because it survives recomposition and is read
     * by both the input and the send call. It is cleared on send: a private message is a
     * deliberate act each time, and leaving the composer armed after one is how somebody
     * sends the room's next message to one person, or one person's to the room.
     */
    var directMessageRecipient: Participant? by mutableStateOf(null)
        private set

    /** The message whose actions are open, or null. One at a time. */
    var openMessageActionsFor: Long? by mutableStateOf(null)
        private set

    /**
     * One proposal this member still owes an answer to, as the transcript's
     * standing notice has to say it.
     *
     * Summarised here rather than at render because the summary is parsed out of
     * stored JSON, and the notice sits in a scrolling list. Named after the first
     * event it signs, the way the proposal list names its rows -- a batch's later
     * items hang off its first, so that is what the proposal is about.
     */
    data class AwaitingProposal(
        val sessionId: String,

        /** The first of its events that could be read, if any could. */
        val lead: ProposedEvent.Summary?,

        /** The batch's size, readable or not. */
        val eventCount: Int,
    )

    /**
     * The sessions still waiting on this member to agree to sign.
     *
     * Held rather than asked for, because both its readers want it already
     * there: the transcript's standing notice renders off it, and
     * [hidesOtherDecisions] is consulted at the moment of a tap, which is not a
     * moment that can wait on a query.
     */
    var proposalsAwaitingYou: List<AwaitingProposal> by mutableStateOf(emptyList())
        private set

    /**
     * One ceremony in this room that still wants something from this member.
     *
     * The counterpart to [AwaitingProposal], for the other thing a room can be
     * waiting on somebody for. A NIP-17 room is where ceremonies happen, and
     * before this the only way to find one that wanted you was to scroll the
     * transcript to its request line -- which for a subgroup means scrolling past
     * whatever else the room has been used for.
     *
     * Either of the room's two ceremonies: the shared key's, and the Lightning
     * wallet's. They open different screens, so [ask] says which this is as well
     * as what it wants. docs/group-lightning-transcript.md, Phase 2.
     */
    data class AwaitingCeremony(
        val sessionId: String,

        val ask: Ask,

        /** Whether this ceremony is making a subgroup, for what the notice says. */
        val isSubgroup: Boolean,
    )

    /** What a ceremony wants of this member. */
    sealed interface Ask {
        /** One of the shared key ceremony's approval gates. */
        data class SharedKeyStep(val step: DkgApprovalStep) : Ask

        /**
         * The room the shared key derives, when that room does not exist yet and
         * this member is the one who would make it.
         *
         * A ceremony reaching COMPLETE finishes nothing on its own: the group has
         * a key and somebody still has to get its state signed and create the
         * room. That somebody is whoever opened it, and nothing else in the app
         * would ever tell them so.
         */
        data class RoomCreation(val chatRoomId: String) : Ask

        /** One of the Lightning wallet's key ceremony's approval gates. */
        data class LightningKeyStep(val step: IcebergApprovalStep) : Ask

        /**
         * The Lightning wallet's key, finished, that no wallet uses yet -- the
         * Lightning counterpart to [RoomCreation], and asked of the same person:
         * whoever opened the ceremony. A key is not a wallet until the group signs
         * a statement for it, and until then nothing can be paid into it.
         */
        data object LightningWalletOpening : Ask
    }

    /**
     * The ceremonies in this room that still want something from this member.
     *
     * Every one of them, not the room's newest: a room holds more than one the
     * moment a subgroup's admins are the whole group, and the one that wants you
     * is routinely not the one that happened last.
     */
    var ceremoniesAwaitingYou: List<AwaitingCeremony> by mutableStateOf(emptyList())
        private set

    /**
     * Whether opening [sessionId] by itself would hide other decisions the room
     * is waiting on this member for.
     *
     * A signing line names one proposal, and opening it is right while that is
     * the only thing being asked of them. It stops being right the moment there
     * are two: the second is not in the transcript beside the first -- it may be
     * pages up, or have arrived while they were reading -- so answering the one
     * they tapped and leaving would look exactly like being done.
     *
     * Only for a proposal that is itself waiting on them. A line about something
     * the group has already signed is history, and a reader who taps one is
     * asking to see that, not to be handed the queue.
     */
    fun hidesOtherDecisions(sessionId: String): Boolean =
        proposalsAwaitingYou.size > 1 &&
            proposalsAwaitingYou.any { it.sessionId == sessionId }

    fun startDirectMessage(participant: Participant) {
        logger.d("Arming a direct message to ${participant.participantPublicKey}")
        directMessageRecipient = participant
        openMessageActionsFor = null
    }

    fun cancelDirectMessage() {
        directMessageRecipient = null
    }

    fun openMessageActions(chatMessageId: Long?) {
        openMessageActionsFor = chatMessageId
    }

    /** The member behind a pubkey, if they are in this room. */
    fun participantFor(publicKey: HexKey?): Participant? =
        publicKey?.let { key ->
            localChatRoom.localParticipants
                .map { it.participant }
                .firstOrNull { it.participantPublicKey == key }
        }

    /**
     * What to call somebody, for a line that has to name them.
     *
     * Falls back to the key itself rather than to "unknown": a direct message names a
     * real member of this room, and a line that cannot say which one is worse than an
     * ugly one.
     */
    fun nameFor(publicKey: HexKey?): String {
        if (publicKey == null) return "someone"

        return localChatRoom.localParticipants
            .firstOrNull { it.participant.participantPublicKey == publicKey }
            ?.profile
            ?.humanReadableNameOrPubkey()
            ?: publicKey.shortened()
    }

    fun initiate() {
        logger.d("init")
        scheduleSynchronization()
        observeChatRoomFeed()
        observeProposalsAwaitingYou()
        observeCeremoniesAwaitingYou()
        askForGroupHistory()
        forwardGuardedWallets()
        resumeSigningSessions()
    }

    /**
     * Watches which of the room's proposals are waiting on this member.
     *
     * Observed rather than read once: a proposal arrives while the room is
     * open as often as before it is opened, and one answered on another device
     * stops being owed without anything happening here. Asked of the manager,
     * the way the proposal list asks, so the two cannot disagree about which
     * proposals still have a decision in them.
     */
    private fun observeProposalsAwaitingYou() {
        viewModelScope.launch(Dispatchers.IO) {
            frostSigningRepository
                .observeSessionsForChatRoom(localChatRoom.chatRoom.id)
                .collect { sessions ->
                    proposalsAwaitingYou = sessions.filter {
                        FrostSigningManager.isAwaitingApproval(it.session, it.items)
                    }.map { local ->
                        AwaitingProposal(
                            sessionId = local.session.id,
                            // The first item that can be read, not the first item:
                            // a batch whose lead is unreadable is still about
                            // something, and the notice should say what.
                            lead = if (local.session.subject == FrostSigningSubject.TRANSACTION) {
                                // Said without reading the PSBT: the notice only has
                                // to say what kind of thing is waiting, and the
                                // signing screen is where the spend is checked.
                                ProposedSpend.UNREADABLE.copy(detail = "")
                            } else if (local.session.subject == FrostSigningSubject.SWEEP) {
                                ProposedSpend.UNREADABLE_SWEEP.copy(detail = "")
                            } else {
                                local.items.firstNotNullOfOrNull { item ->
                                    Event.fromJsonOrNull(item.unsignedEventJson)
                                        ?.let { ProposedEvent.summarize(it) }
                                }
                            },
                            eventCount = local.items.size
                        )
                    }
                }
        }
    }

    /**
     * Watches which of the room's ceremonies are waiting on this member.
     *
     * Two different kinds of waiting, and both belong here. A participant is owed
     * an approval gate -- `pendingApproval` is the same reading the ritual screen
     * makes, so the two cannot disagree. The member who *opened* it is owed
     * something the protocol has no gate for: a finished ceremony still needs its
     * key state signed and its room created, and nothing else in the app would
     * ever say so.
     *
     * A ceremony whose room exists wants nothing from anybody, which is what
     * `roomAwaitingCreation` is asking. That keeps the notice from becoming
     * permanent furniture in every room that has ever held a ceremony.
     *
     * The Lightning wallet's ceremony is read the same way beside it: its gates
     * from the same `pendingApproval` its screen reads, and its finished key from
     * whether a wallet uses it -- see [lightningWalletAwaitingYou].
     */
    private fun observeCeremoniesAwaitingYou() {
        val roomId = localChatRoom.chatRoom.id
        viewModelScope.launch(Dispatchers.IO) {
            combine(
                dkgRepository.observeSessionsForChatRoom(roomId),
                lightningRepository.observeCeremonies(roomId),
                // Only for whether a wallet statement is already being signed:
                // once it is, the key has been put to the group and asking its
                // opener to open it again would start a second proposal.
                frostSigningRepository.observeSessionsForChatRoom(roomId),
                ::Triple,
            ).collect { (sessions, lightningCeremonies, signings) ->
                val sharedKey = sessions
                    .filter { it.stage != DkgRitualStage.FAILED }
                    .mapNotNull { session ->
                        val ask = dkgRepository.pendingApproval(session)?.let { Ask.SharedKeyStep(it) }
                            ?: session
                                .takeIf { it.coordinatorPublicKey == it.userPublicKey }
                                ?.let { dkgRepository.roomAwaitingCreation(it) }
                                ?.let { Ask.RoomCreation(it) }
                            ?: return@mapNotNull null

                        AwaitingCeremony(
                            sessionId = session.id,
                            ask = ask,
                            isSubgroup = session.parentChatRoomId != null,
                        )
                    }

                val lightningKey = lightningCeremonies
                    .filter { it.stage != IcebergRitualStage.FAILED }
                    .mapNotNull { session ->
                        lightningRepository.pendingApproval(session)?.let {
                            AwaitingCeremony(session.id, Ask.LightningKeyStep(it), isSubgroup = false)
                        }
                    }

                ceremoniesAwaitingYou = sharedKey + lightningKey + listOfNotNull(lightningWalletAwaitingYou(signings))
            }
        }
    }

    /**
     * The finished Lightning wallet key this member opened the ceremony for, while no
     * wallet uses it and no statement for it is being signed; null otherwise, and
     * always on a build that does not offer the wallet.
     */
    private suspend fun lightningWalletAwaitingYou(signings: List<LocalFrostSigningSession>): AwaitingCeremony? {
        if (!GroupLightningRollout.ENABLED) return null
        val key = lightningRepository.unusedKey(localChatRoom.chatRoom.id, GroupLightningRollout.NETWORK)
            ?.takeIf { it.coordinatorPublicKey.equals(it.userPublicKey, ignoreCase = true) }
            ?: return null

        val beingSigned = signings
            .filter { it.session.stage != FrostSigningStage.FAILED }
            .flatMap { it.items }
            .mapNotNull { Event.fromJsonOrNull(it.unsignedEventJson) }
            .filter { it.kind == GroupLightningWalletStatementEvent.KIND }
            .any { GroupLightningWalletStatementEvent.read(it.tags)?.ceremonyId == key.id }

        return if (beingSigned) null else AwaitingCeremony(key.id, Ask.LightningWalletOpening, isSubgroup = false)
    }

    /**
     * Ask the group for its signed record, if this device holds none of it.
     *
     * Opening the room is the trigger because it is the first moment this device
     * is demonstrably in the group's current epoch -- see
     * docs/member-chronicle.md. A member added after the work was done has no way
     * to see any of it otherwise: MLS gives them no history, and a group-signed
     * event never travels, so every device derives it or does without.
     *
     * Cheap to call every time. It stops at a room that already holds work and
     * at one still waiting on an answer, and a failure is not worth telling
     * anybody about: nothing acknowledges a request, so the next open asks again.
     */
    private fun askForGroupHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                chatRepository.requestGroupHistoryIfMissing(
                    chatRoomId = localChatRoom.chatRoom.id,
                    userPublicKey = localChatRoom.chatRoom.userPublicKey,
                )
            }.onFailure { logger.w(throwable = it) { "Could not ask for this group's history" } }
        }
    }

    /**
     * Tells the groups above this one about every guarded wallet it holds that
     * they have not heard of: the catch-up behind the arrival triggers, which
     * covers a wallet that arrived while this device was offline, one the
     * elected device never sent, and every one filed before forwarding existed.
     * `docs/guarded-wallet-forwarding.md`, D5. A Marmot room only -- a guardian
     * is a group with a key, and a group with a key has an MLS room.
     */
    private fun forwardGuardedWallets() {
        if (localChatRoom.chatRoom.mlsGroupState == null) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { chatRepository.forwardGuardedWallets(localChatRoom.chatRoom.id) }
                .onFailure { logger.w(throwable = it) { "Could not tell the groups above about this group's guarded wallets" } }
        }
    }

    /**
     * Lets this room's answered signing sessions take any step they are able to.
     *
     * Everything else in a session moves on a message arriving, and a device
     * killed part way through its own turn is waiting on one that has already
     * been delivered: the signer set is filed, so it will not be processed
     * twice, and the coordinator asking again cannot help, because what it asks
     * a quiet member for is the partial signature this device believes it sent.
     * Opening the room is the next moment the app has.
     *
     * Cheap to call every time, and safe to: the manager publishes nothing that
     * is already on file and touches no session whose owner has yet to answer.
     * See `FrostSigningRepository.resumeAnswered`.
     */
    private fun resumeSigningSessions() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                frostSigningRepository.resumeAnswered(localChatRoom)
            }.onFailure { logger.w(throwable = it) { "Could not resume this room's signing sessions" } }
        }
    }

    fun observeChatRoomFeed() {
        logger.d("observeChatRoomFeed")
        viewModelScope.launch(Dispatchers.IO) {
            chatRepository.observeChatMessageListByChatRoomId(
                localChatRoom.chatRoom.id
            ).distinctUntilChanged().collect { chatMessages ->
                logger.d("getChatMessageListByChatRoomId: $chatMessages")
                chatMessageListUIState = ChatMessageListUIState.Loaded(
                    chatMessageList = chatMessages
                )
            }
        }
    }

    /**
     * Finds out who this room's members are, and where they read their messages.
     *
     * This used to schedule the room's message sync as well — gift wraps for a NIP-17 room,
     * group events for an MLS one. Both are now covered by the subscriptions
     * LiveSubscriptionManager holds open for the whole account, so opening a chat no longer
     * asks for its messages; they are already arriving.
     *
     * What is left is discovery, not message sync, and it is worth doing the moment a chat is
     * opened rather than whenever a background pass gets to it:
     *
     *  - Any member still showing as "LOADING..." — every room, not just NIP-17 ones. See
     *    [MemberProfileSync] for why a Marmot group's members otherwise stay that way: the
     *    only ask for their kind:0 was made once, when the Welcome or the commit that added
     *    them arrived, and nothing they subsequently do prompts another.
     *  - A NIP-17 participant's kind-10050, without which we cannot address a message to
     *    them at all. An MLS room needs none: its messages go to the group's own relays.
     */
    fun scheduleSynchronization() {
        logger.d("scheduleSynchronization")

        viewModelScope.launch(Dispatchers.IO) {
            val unnamedMembers = MemberProfileSync.unresolvedMemberPublicKeys(
                rooms = listOf(localChatRoom),
                activeUserPublicKey = localChatRoom.chatRoom.userPublicKey,
            )

            if (unnamedMembers.isNotEmpty()) {
                logger.i("Members of ${localChatRoom.chatRoom.id} we cannot name yet: $unnamedMembers")
                nostrRepository.queueNegentropySynchronizeRequest(
                    MemberProfileSync.requests(unnamedMembers)
                )
            }

            if (localChatRoom.chatRoom.mlsGroupState == null) {
                localChatRoom.localParticipants.filter { it.participant.participantPublicKey != localChatRoom.chatRoom.userPublicKey }.forEach { recipients ->

                    val chatMessageRelayListEvent = chatRepository.getChatMessageRelayForPublicKey(recipients.participant.participantPublicKey)

                    // Opening a conversation used to refresh our own inbox here, with
                    // Nip17Filters.inbox on our own DM relays. That is now redundant rather
                    // than wrong: LiveSubscriptionManager holds exactly that filter open for
                    // the whole account and reconciles it on every foreground, so there is
                    // nothing left for opening a chat to ask for.
                    if (chatMessageRelayListEvent != null) return@forEach

                    isReceiverChatMessageRelayListMissing.value = true

                    val chatMessageRelayListFilter = SynchronizationFilter(
                        kinds = arrayOf(
                            ChatMessageRelayListEvent.KIND,
                        ),
                        authors = arrayOf(
                            recipients.participant.participantPublicKey
                        ),
                        limit = 50
                    )

                    val negentropySynchronizeRequests = Relays.DefaultDMRelayList.map { normalizedRelayUrl ->
                        NegentropySynchronizeRequest(
                            id = NegentropySynchronizeRequest.computeId(
                                relayURL = normalizedRelayUrl.url,
                                synchronizationFilter = chatMessageRelayListFilter
                            ),
                            // Only one case reaches here now, so the purpose is no longer a
                            // question.
                            purpose = "chat-message-relays",
                            synchronizationFilter = chatMessageRelayListFilter,
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
        }
    }


    fun sendMessage(textFieldState: TextFieldState) {
        if (textFieldState.text.isNotBlank()) {
            val text = textFieldState.text.toString()
            // Read and cleared together with the text, before the send suspends, so a
            // second message cannot inherit the first one's audience.
            val recipient = directMessageRecipient
            textFieldState.clearText()
            directMessageRecipient = null

            viewModelScope.launch(Dispatchers.IO) {
                chatRepository.sendChatMessage(
                    text = text,
                    localChatRoom = localChatRoom,
                    directMessageRecipientPublicKey = recipient?.participantPublicKey
                )
            }
        }
    }

    companion object {
        const val TAG = "ChatMessageListViewModel"

        fun factory(
            initialChatMessageListUIState: ChatMessageListUIState = ChatMessageListUIState.Loading,
            localChatRoom: LocalChatRoom,
            nostrRepository: NostrRepository,
            chatRepository: ChatRepository,
            frostSigningRepository: FrostSigningRepository,
            dkgRepository: DkgRepository,
            lightningRepository: GroupLightningRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ChatMessageListViewModel(
                    initialChatMessageListUIState = initialChatMessageListUIState,
                    localChatRoom = localChatRoom,
                    nostrRepository = nostrRepository,
                    chatRepository = chatRepository,
                    frostSigningRepository = frostSigningRepository,
                    dkgRepository = dkgRepository,
                    lightningRepository = lightningRepository,
                )
            }
        }
    }
}
