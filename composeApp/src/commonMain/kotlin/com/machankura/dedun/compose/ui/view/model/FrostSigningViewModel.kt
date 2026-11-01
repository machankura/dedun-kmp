package com.machankura.dedun.compose.ui.view.model

import com.machankura.dedun.compose.wallet.PayoutRule
import com.machankura.dedun.compose.repository.PayoutRepository
import com.machankura.dedun.compose.wallet.GroupSpend
import com.machankura.dedun.compose.wallet.lightning.GroupSweep
import com.machankura.dedun.compose.database.model.types.FrostSigningSubject
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
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import com.machankura.dedun.compose.database.model.FrostSignerMessage
import com.machankura.dedun.compose.database.model.FrostSigningItem
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.nostr.frost.FrostSigningEvents
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.FrostSigningRepository
import com.machankura.dedun.compose.repository.PayoutDestinationRepository
import com.machankura.dedun.compose.managers.GroupPayoutDestinations
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.ui.view.state.FrostSigningUIState

class FrostSigningViewModel(
    val chatRoomId: String,
    val sessionId: String,
    val activeUserPublicKey: HexKey,
    initialFrostSigningUIState: FrostSigningUIState,
    val chatRepository: ChatRepository,
    val frostSigningRepository: FrostSigningRepository,
    /** Where the group pays its members, for naming a spend's outputs. The no-op names none. */
    val payoutDestinationRepository: PayoutDestinationRepository = PayoutDestinationRepository.NO_OP,
    /** Whose each coin is, for reading a spend as a payout: `docs/payouts-only.md`. The no-op reads none. */
    val payoutRepository: PayoutRepository = PayoutRepository.NO_OP,
): ViewModel() {

    var frostSigningUIState: FrostSigningUIState by mutableStateOf(initialFrostSigningUIState)
        private set

    private val logger = Logger.withTag(TAG)

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    /**
     * Loads the room, then watches the session for as long as the screen lives.
     *
     * A session moves on messages arriving from other members, so a screen that
     * read it once would sit still while the rest of the group signed around it.
     *
     * Who can take part is read off the ceremony the session names, not off the
     * room: the room can hold members who were added after its key was made and
     * hold no share of it, and the screen is a list of whose door to knock on.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)
            if (localChatRoom == null) {
                frostSigningUIState = FrostSigningUIState.Error("Couldn't find the group")
                return@launch
            }

            frostSigningUIState = FrostSigningUIState.Loaded(localChatRoom = localChatRoom)

            val sessions = frostSigningRepository.observeSessionById(sessionId)
            val shareHolders = sessions.flatMapLatest { session ->
                session?.let { frostSigningRepository.observeShareHolders(it.dkgSessionId) }
                    ?: flowOf(emptySet())
            }

            combine(
                sessions,
                frostSigningRepository.observeMessages(sessionId),
                frostSigningRepository.observeItems(sessionId),
                shareHolders
            ) { session, messages, items, holders -> Observed(session, messages, items, holders) }
                .collect { (session, messages, items, holders) ->
                    // Read per emission rather than derived from `messages`: who
                    // the session is waiting on is the manager's rule, and it is
                    // not the same rule at every stage. See
                    // `FrostSigningRepository.membersOwing`.
                    val owed = session?.let { frostSigningRepository.membersOwing(it) } ?: emptySet()

                    // Checked once: the PSBT is write-once, so what it says cannot
                    // change under a session that is still being collected.
                    if (session?.subject == FrostSigningSubject.TRANSACTION && checkedSpend == null) {
                        checkedSpend = frostSigningRepository.checkedSpend(session.id)
                        checkedSpend?.let { payees = payeesOf(it, localChatRoom) }
                        // A spend from the room's own wallet, read as every signer reads it.
                        payout = checkedSpend?.takeIf { it.spender == GroupSpend.Spender.Owner }?.let { payoutRepository.payoutOf(chatRoomId, it) }
                    }
                    if (session?.subject == FrostSigningSubject.SWEEP && checkedSweep == null) {
                        checkedSweep = frostSigningRepository.checkedSweep(session.id)
                    }

                    frostSigningUIState = FrostSigningUIState.Loaded(
                        localChatRoom = localChatRoom,
                        session = session,
                        items = items,
                        shareHolders = holders,
                        offeredNonce = messages
                            .filter { it.kind == FrostSigningEvents.NONCE }
                            .map { it.signerPublicKey }
                            .toSet(),
                        signed = messages
                            .filter { it.kind == FrostSigningEvents.PARTIAL_SIGNATURE }
                            .map { it.signerPublicKey }
                            .toSet(),
                        declined = messages
                            .filter { it.kind == FrostSigningEvents.DECLINE }
                            .map { it.signerPublicKey }
                            .toSet(),
                        silent = owed,
                        spend = checkedSpend,
                        sweep = checkedSweep,
                        payees = payees,
                        payout = payout,
                        names = localChatRoom.localParticipants.associate { participant ->
                            participant.participant.participantPublicKey.lowercase() to
                                (participant.profile?.humanReadableNameOrPubkey() ?: participant.participant.participantPublicKey.take(8))
                        },
                    )
                }
        }
    }

    private var checkedSpend: GroupSpend.Checked? = null
    private var payees: Map<String, String> = emptyMap()
    private var payout: PayoutRule.Result? = null

    /**
     * Which of [spend]'s payments go to a member's payout address, by script: each current
     * member's own signed destination, matched byte for byte against the output -- so the name
     * the screen puts on it is this device's finding, not the proposer's claim.
     */
    private suspend fun payeesOf(spend: GroupSpend.Checked, localChatRoom: LocalChatRoom): Map<String, String> {
        val destinations = payoutDestinationRepository.destinationsIn(chatRoomId, spend.account.chain)
        return GroupPayoutDestinations.payeesOf(
            payments = spend.outputs.filter { !it.isChange }.map { it.txOut.publicKeyScript },
            destinations = destinations.values,
        ) { member ->
            localChatRoom.localParticipants
                .firstOrNull { it.participant.participantPublicKey.equals(member, ignoreCase = true) }
                ?.profile?.humanReadableNameOrPubkey() ?: member.take(8)
        }
    }
    private var checkedSweep: GroupSweep.Checked? = null

    /** One emission of everything the screen is drawn from -- the fourth flow outgrew `Triple`. */
    private data class Observed(
        val session: FrostSigningSession?,
        val messages: List<FrostSignerMessage>,
        val items: List<FrostSigningItem>,
        val shareHolders: Set<HexKey>,
    )

    /** The events the group is being asked to sign, for showing them before they agree. */
    fun proposedEvents(items: List<FrostSigningItem>): List<Event> =
        items.mapNotNull { Event.fromJsonOrNull(it.unsignedEventJson) }

    fun signedEvents(items: List<FrostSigningItem>): List<Event> =
        frostSigningRepository.signedEvents(items)

    fun approve(onDone: () -> Unit) {
        val state = frostSigningUIState as? FrostSigningUIState.Loaded ?: return
        val sessionId = state.session?.id ?: return

        if (isActionPending.value) return
        isActionPending.value = true

        viewModelScope.launch(Dispatchers.IO) {
            frostSigningRepository.approve(
                localChatRoom = state.localChatRoom,
                sessionId = sessionId
            )

            isActionPending.value = false

            viewModelScope.launch(Dispatchers.Main) { onDone.invoke() }
        }
    }

    /**
     * Asks the members this session has heard nothing from to take another look.
     *
     * Unlike [approve] and [decline] this does not navigate away: the point of
     * pressing it is to go on watching whether anybody answers, and the screen
     * is the only place that says who has. [onResult] is handed whether anything
     * actually went out, so the caller can say which happened -- a resend inside
     * the cooldown, or one where everybody has since answered, publishes nothing
     * and should not claim to have.
     */
    fun resend(onResult: (sent: Boolean) -> Unit) {
        val state = frostSigningUIState as? FrostSigningUIState.Loaded ?: return
        val sessionId = state.session?.id ?: return

        if (isActionPending.value) return
        isActionPending.value = true

        viewModelScope.launch(Dispatchers.IO) {
            val sent = frostSigningRepository.resend(
                localChatRoom = state.localChatRoom,
                sessionId = sessionId
            )

            isActionPending.value = false

            viewModelScope.launch(Dispatchers.Main) { onResult.invoke(sent) }
        }
    }

    fun decline(onDone: () -> Unit) {
        val state = frostSigningUIState as? FrostSigningUIState.Loaded ?: return
        val sessionId = state.session?.id ?: return

        if (isActionPending.value) return
        isActionPending.value = true

        viewModelScope.launch(Dispatchers.IO) {
            frostSigningRepository.decline(
                localChatRoom = state.localChatRoom,
                sessionId = sessionId
            )

            isActionPending.value = false

            viewModelScope.launch(Dispatchers.Main) { onDone.invoke() }
        }
    }

    companion object {
        private const val TAG = "FrostSigningViewModel"

        fun factory(
            chatRoomId: String,
            sessionId: String,
            activeUserPublicKey: HexKey,
            initialFrostSigningUIState: FrostSigningUIState = FrostSigningUIState.Loading,
            chatRepository: ChatRepository,
            frostSigningRepository: FrostSigningRepository,
            payoutDestinationRepository: PayoutDestinationRepository = PayoutDestinationRepository.NO_OP,
            payoutRepository: PayoutRepository = PayoutRepository.NO_OP,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                FrostSigningViewModel(
                    chatRoomId = chatRoomId,
                    sessionId = sessionId,
                    activeUserPublicKey = activeUserPublicKey,
                    initialFrostSigningUIState = initialFrostSigningUIState,
                    chatRepository = chatRepository,
                    frostSigningRepository = frostSigningRepository,
                    payoutDestinationRepository = payoutDestinationRepository,
                    payoutRepository = payoutRepository,
                )
            }
        }
    }
}
